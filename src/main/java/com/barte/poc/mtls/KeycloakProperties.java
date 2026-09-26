package com.barte.poc.mtls;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
        String baseUrl,
        String realm,
        String backendClientId,
        String backendClientSecret,
        String stepcaClientId,
        String stepcaClientSecret) {
}
