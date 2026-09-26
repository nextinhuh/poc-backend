package com.barte.poc.mtls;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Cria (se necessario) o usuario-terminal no realm poc-terminal - username =
 * serial number do hardware - e emite um ID Token OIDC para ele via Direct
 * Access Grant (grant_type=password) contra o client step-ca-oidc.
 *
 * Historico: a primeira versao deste servico tentava emitir o token via
 * OAuth2 Token Exchange (RFC 8693), impersonando o terminal com a
 * identidade do proprio client poc-backend. Validamos que o Keycloak
 * (Token Exchange v1) nunca devolve id_token nesse grant, independente dos
 * parametros usados (scope=openid, audience, requested_token_type) - e uma
 * limitacao do mecanismo, nao uma config errada. O step-ca exige um ID
 * Token de verdade (aud batendo no client step-ca-oidc), entao trocamos
 * para Direct Access Grant, que e o unico grant que realmente autentica o
 * "usuario" e por isso emite id_token. Isso exige que o usuario-terminal
 * tenha senha - cada terminal ganha uma, derivada deterministicamente do
 * serial number (ver derivePassword), sem persistir nada em lugar nenhum.
 *
 * Ver README, secao "papel deste servico", para o passo a passo de
 * configuracao do realm que isso exige (Direct Access Grants habilitado no
 * client step-ca-oidc, permissoes de Users->view/manage).
 */
@Service
public class KeycloakService {

    private final RestTemplate restTemplate;
    private final KeycloakProperties properties;
    private final TerminalAuthProperties terminalAuthProperties;

    public KeycloakService(
            RestTemplate restTemplate,
            KeycloakProperties properties,
            TerminalAuthProperties terminalAuthProperties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.terminalAuthProperties = terminalAuthProperties;
    }

    public String issueTokenForSerialNumber(String serialNumber) {
        String serviceAccountToken = fetchServiceAccountToken();

        findUserIdByUsername(serialNumber, serviceAccountToken)
                .orElseGet(() -> createUser(serialNumber, serviceAccountToken));

        return loginAsTerminal(serialNumber);
    }

    private String fetchServiceAccountToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.backendClientId());
        form.add("client_secret", properties.backendClientSecret());

        Map<String, Object> response = postForm(tokenEndpoint(), form);
        return (String) response.get("access_token");
    }

    private Optional<String> findUserIdByUsername(String username, String serviceAccountToken) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/admin/realms/{realm}/users")
                .queryParam("username", username)
                .queryParam("exact", true)
                .buildAndExpand(properties.realm())
                .toUriString();

        HttpHeaders headers = bearerHeaders(serviceAccountToken);
        var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), List.class);
        List<Map<String, Object>> users = response.getBody();

        if (users == null || users.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((String) users.get(0).get("id"));
    }

    private String createUser(String serialNumber, String serviceAccountToken) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/admin/realms/{realm}/users")
                .buildAndExpand(properties.realm())
                .toUriString();

        // Nome/email mock (nao sao usados pra nada alem de satisfazer o User
        // Profile do Keycloak - sem eles o login falha com "Account is not
        // fully set up", erro real encontrado ao validar este fluxo).
        // firstName = serial number, pra identificar o terminal facilmente
        // no console. Senha derivada (ver derivePassword) - o backend nunca
        // precisa lembrar/persistir ela, so recalcula igual da proxima vez.
        Map<String, Object> body = Map.of(
                "username", serialNumber,
                "enabled", true,
                "firstName", serialNumber,
                "lastName", "Terminal",
                "email", serialNumber + "@terminal.mock",
                "emailVerified", true,
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", derivePassword(serialNumber),
                        "temporary", false)));

        HttpHeaders headers = bearerHeaders(serviceAccountToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);

        // Keycloak nao devolve o id no corpo do POST (retorna so o header
        // Location) - buscamos de novo por simplicidade.
        return findUserIdByUsername(serialNumber, serviceAccountToken)
                .orElseThrow(() -> new IllegalStateException(
                        "Terminal " + serialNumber + " nao encontrado logo apos criacao"));
    }

    /**
     * Senha = seed + serialNumber (concatenacao simples). O seed nasce uma
     * unica vez no Terraform (random_password) e nunca e persistido pelo
     * backend - a senha e sempre recalculada, nunca guardada. Mais simples
     * que um HMAC(seed, serialNumber), mas criptograficamente mais fraco: se
     * o seed vazar, quem souber o serial number (nao e segredo) recria a
     * senha de qualquer terminal. Aceitavel para esta POC; considerar HMAC
     * como evolucao futura se isso for pra producao real.
     */
    private String derivePassword(String serialNumber) {
        return terminalAuthProperties.authSeed() + serialNumber;
    }

    private String loginAsTerminal(String serialNumber) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.stepcaClientId());
        form.add("client_secret", properties.stepcaClientSecret());
        form.add("username", serialNumber);
        form.add("password", derivePassword(serialNumber));
        form.add("scope", "openid");

        Map<String, Object> response = postForm(tokenEndpoint(), form);
        return (String) response.get("id_token");
    }

    private Map<String, Object> postForm(String url, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.postForObject(url, new HttpEntity<>(form, headers), Map.class);
    }

    private String tokenEndpoint() {
        return UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/realms/{realm}/protocol/openid-connect/token")
                .buildAndExpand(properties.realm())
                .toUriString();
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
