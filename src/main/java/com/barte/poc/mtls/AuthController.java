package com.barte.poc.mtls;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint publico (porta 80, listener HTTP do ALB compartilhado). Recebe o
 * serial number do terminal, cria o usuario no Keycloak se necessario (sem
 * senha), e devolve um token pronto para ser usado no POST /1.0/sign do
 * step-ca.
 */
@RestController
public class AuthController {

    public record TokenRequest(String serialNumber) {
    }

    public record TokenResponse(String accessToken) {
    }

    private final KeycloakService keycloakService;

    public AuthController(KeycloakService keycloakService) {
        this.keycloakService = keycloakService;
    }

    @PostMapping("/auth/token")
    public TokenResponse issueToken(@RequestBody TokenRequest request) {
        if (request.serialNumber() == null || request.serialNumber().isBlank()) {
            throw new IllegalArgumentException("serialNumber e obrigatorio");
        }
        String token = keycloakService.issueTokenForSerialNumber(request.serialNumber());
        return new TokenResponse(token);
    }
}
