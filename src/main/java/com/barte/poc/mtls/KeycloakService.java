package com.barte.poc.mtls;

import java.security.SecureRandom;
import java.util.Base64;
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
 * Cria (se necessario) o usuario no realm poc-terminal e emite um token para
 * ele.
 *
 * IMPORTANTE (ver README, secao "papel deste servico"): o step-ca valida um
 * ID Token OIDC (JWT assinado pelo Keycloak), nao um access_token opaco. Por
 * isso pedimos scope=openid e devolvemos o campo id_token da resposta do
 * Keycloak como "access_token" no contrato deste endpoint - o nome do campo
 * segue a nomenclatura combinada entre os 4 repositorios, mas o valor
 * precisa ser o id_token, senao o step-ca rejeita na hora de assinar o CSR.
 */
@Service
public class KeycloakService {

    private final RestTemplate restTemplate;
    private final KeycloakProperties properties;
    private final SecureRandom random = new SecureRandom();

    public KeycloakService(RestTemplate restTemplate, KeycloakProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public String issueTokenForEmail(String email) {
        String adminAccessToken = fetchAdminAccessToken();
        String userId = findUserIdByEmail(email, adminAccessToken)
                .orElseGet(() -> createUser(email, adminAccessToken));

        String temporaryPassword = generateTemporaryPassword();
        resetUserPassword(userId, temporaryPassword, adminAccessToken);

        return issueUserIdToken(email, temporaryPassword);
    }

    private String fetchAdminAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.backendClientId());
        form.add("client_secret", properties.backendClientSecret());

        Map<String, Object> response = postForm(tokenEndpoint(), form);
        return (String) response.get("access_token");
    }

    private Optional<String> findUserIdByEmail(String email, String adminAccessToken) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/admin/realms/{realm}/users")
                .queryParam("email", email)
                .queryParam("exact", true)
                .buildAndExpand(properties.realm())
                .toUriString();

        HttpHeaders headers = bearerHeaders(adminAccessToken);
        var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), List.class);
        List<Map<String, Object>> users = response.getBody();

        if (users == null || users.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((String) users.get(0).get("id"));
    }

    private String createUser(String email, String adminAccessToken) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/admin/realms/{realm}/users")
                .buildAndExpand(properties.realm())
                .toUriString();

        Map<String, Object> body = Map.of(
                "username", email,
                "email", email,
                "enabled", true,
                "emailVerified", true);

        HttpHeaders headers = bearerHeaders(adminAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);

        // Keycloak nao devolve o id no corpo do POST (retorna so o header
        // Location) - buscamos de novo por simplicidade.
        return findUserIdByEmail(email, adminAccessToken)
                .orElseThrow(() -> new IllegalStateException("Usuario " + email + " nao encontrado logo apos criacao"));
    }

    private void resetUserPassword(String userId, String password, String adminAccessToken) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl())
                .path("/admin/realms/{realm}/users/{userId}/reset-password")
                .buildAndExpand(properties.realm(), userId)
                .toUriString();

        Map<String, Object> body = Map.of(
                "type", "password",
                "value", password,
                "temporary", false);

        HttpHeaders headers = bearerHeaders(adminAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
    }

    private String issueUserIdToken(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.backendClientId());
        form.add("client_secret", properties.backendClientSecret());
        form.add("username", email);
        form.add("password", password);
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

    private String generateTemporaryPassword() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
