package com.barte.poc.mtls;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.convert.converter.Converter;

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
                        // regras mais especificas antes da generica /consumer/** -
                        // ordem importa no authorizeHttpRequests.
                        .requestMatchers("/consumer/terminal-pode/**").hasRole("terminal_pode")
                        .requestMatchers("/consumer/terminal-nao-pode/**").hasRole("terminal_nao_pode")
                        .requestMatchers("/consumer/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * O Spring, por padrao, so olha o claim "scope"/"scp" pra montar
     * authorities - nao e assim que o Keycloak representa roles. Aqui
     * extraimos "realm_access.roles" do token (injetado automaticamente
     * pelo client scope "roles", default em qualquer client) e viramos
     * GrantedAuthority com o prefixo ROLE_, que e o que hasRole(...) espera.
     */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        Converter<Jwt, java.util.Collection<GrantedAuthority>> realmRolesConverter = jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.getOrDefault("roles", List.of());
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        };

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(realmRolesConverter);
        return converter;
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
