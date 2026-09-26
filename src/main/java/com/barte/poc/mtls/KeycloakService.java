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
 * serial number do hardware, sem senha - e emite um token para ele via OAuth2
 * Token Exchange (RFC 8693), impersonando o usuario com a identidade do
 * proprio client poc-backend (client_credentials). Ver README, secao "papel
 * deste servico", para o passo a passo de configuracao do realm que isso
 * exige (feature token-exchange, permissoes Users->impersonate e
 * Clients->poc-backend->token-exchange).
 *
 * IMPORTANTE: o step-ca valida um ID Token OIDC (JWT assinado pelo Keycloak),
 * nao um access_token opaco. Por isso pedimos scope=openid no exchange e
 * devolvemos o campo id_token da resposta como "accessToken" no contrato
 * deste endpoint.
 */
@Service
public class KeycloakService {

    private final RestTemplate restTemplate;
    private final KeycloakProperties properties;

    public KeycloakService(RestTemplate restTemplate, KeycloakProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public String issueTokenForSerialNumber(String serialNumber) {
        String serviceAccountToken = fetchServiceAccountToken();

        findUserIdByUsername(serialNumber, serviceAccountToken)
                .orElseGet(() -> createUser(serialNumber, serviceAccountToken));

        return exchangeTokenForSubject(serviceAccountToken, serialNumber);
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

        // Sem email, sem credentials - o terminal nunca faz login com senha,
        // so e alvo de token exchange feito pelo poc-backend.
        Map<String, Object> body = Map.of(
                "username", serialNumber,
                "enabled", true);

        HttpHeaders headers = bearerHeaders(serviceAccountToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);

        // Keycloak nao devolve o id no corpo do POST (retorna so o header
        // Location) - buscamos de novo por simplicidade.
        return findUserIdByUsername(serialNumber, serviceAccountToken)
                .orElseThrow(() -> new IllegalStateException(
                        "Terminal " + serialNumber + " nao encontrado logo apos criacao"));
    }

    private String exchangeTokenForSubject(String serviceAccountToken, String serialNumber) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.add("client_id", properties.backendClientId());
        form.add("client_secret", properties.backendClientSecret());
        form.add("subject_token", serviceAccountToken);
        form.add("requested_subject", serialNumber);
        form.add("scope", "openid");

        Map<String, Object> response = postForm(tokenEndpoint(), form);
        Object idToken = response.get("id_token");
        return idToken != null ? (String) idToken : (String) response.get("access_token");
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
