package com.barte.poc.mtls;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed usado para derivar a senha de cada usuario-terminal no Keycloak (ver
 * KeycloakService.derivePassword). Gerado uma unica vez pelo Terraform
 * (random_password) e injetado via SSM/env var - nao e uma configuracao do
 * Keycloak, por isso fica separado de KeycloakProperties.
 */
@ConfigurationProperties(prefix = "terminal")
public record TerminalAuthProperties(String authSeed) {
}
