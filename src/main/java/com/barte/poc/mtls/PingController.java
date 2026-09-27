package com.barte.poc.mtls;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    /**
     * Porta 80, listener HTTP normal do ALB - qualquer cliente pode chamar,
     * sem certificado nenhum. So prova que a porta 80 "normal" funciona.
     */
    @GetMapping("/public/ping")
    public Map<String, String> publicPing() {
        return Map.of("status", "ok");
    }

    /**
     * So deve ser roteavel pelo listener mTLS (porta 8443) do ALB - a regra
     * de bloqueio no listener 80 (fixed-response 404 para /consumer/*) e
     * criada no Terraform deste repositorio (alb-rules.tf), nao aqui no
     * codigo. O ALB injeta o subject do certificado do cliente no header
     * X-Amzn-Mtls-Clientcert-Subject quando o mutual_authentication esta em
     * modo "verify" - so ecoamos esse valor, sem validacao adicional (a
     * validacao real e feita pelo trust store do ALB).
     *
     * Segunda camada: alem do mTLS, o SecurityConfig exige um Bearer token
     * valido (assinatura/iss/exp/aud verificados via JWKS do Keycloak) -
     * sem isso a request nem chega aqui (401 antes do controller).
     */
    @GetMapping("/consumer/ping")
    public Map<String, String> consumerPing(
            @RequestHeader(value = "X-Amzn-Mtls-Clientcert-Subject", required = false) String certSubject,
            @AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "status", "ok",
                "cert_subject", certSubject == null ? "desconhecido" : certSubject,
                "token_subject", jwt.getSubject());
    }
}
