package com.barte.poc.mtls;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Segunda camada de validacao do /consumer/ping, alem do mTLS ja validado
 * pelo ALB: o cliente precisa mandar tambem um Bearer token (o mesmo
 * id_token do /auth/token/step-ca), validado aqui contra o Keycloak via
 * JWKS (mesma estrategia "cache local, sem chamada por request" que o
 * step-ca ja usa pro "ott" - ver README do poc-certificate).
 *
 * /public/ping e /auth/token continuam sem exigir token - sao os endpoints
 * que existem justamente pra provar/emitir credenciais.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final KeycloakProperties keycloakProperties;

    public SecurityConfig(KeycloakProperties keycloakProperties) {
        this.keycloakProperties = keycloakProperties;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/ping", "/auth/token").permitAll()
                        .requestMatchers("/consumer/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Validacao padrao (assinatura via JWKS, iss, exp) + checagem extra de
     * audience: so aceita token emitido para o client "step-ca-oidc" (o
     * mesmo usado pra assinar o certificado) - defesa em profundidade, evita
     * que qualquer JWT valido de outro client do mesmo Keycloak passe aqui.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        String issuerUri = keycloakProperties.issuer();
        var decoder = (org.springframework.security.oauth2.jwt.NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = jwt -> jwt.getAudience().contains(keycloakProperties.stepcaClientId())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "aud nao contem " + keycloakProperties.stepcaClientId(), null));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
        return decoder;
    }
}
