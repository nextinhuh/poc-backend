package com.barte.poc.mtls;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cobre a segunda camada de validacao do /consumer/ping (SecurityConfig):
 * sem Bearer token -> 401 (mTLS sozinho nao basta mais); com token -> 200 e
 * o "sub" do JWT aparece em token_subject. O JwtDecoder real e mockado
 * porque ele busca o JWKS do Keycloak via rede no boot do contexto
 * (JwtDecoders.fromIssuerLocation) - nao queremos depender de um Keycloak
 * de verdade rodando so pra este teste.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsumerPingSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void semTokenRetorna401() throws Exception {
        mockMvc.perform(get("/consumer/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void comTokenValidoRetorna200ComTokenSubject() throws Exception {
        mockMvc.perform(get("/consumer/ping").with(jwt().jwt(j -> j.subject("terminal-uuid-teste"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_subject").value("terminal-uuid-teste"));
    }

    @Test
    void publicPingContinuaSemExigirToken() throws Exception {
        mockMvc.perform(get("/public/ping"))
                .andExpect(status().isOk());
    }

    @Test
    void terminalPodeComRoleCertaRetorna200() throws Exception {
        mockMvc.perform(get("/consumer/terminal-pode/ping")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_terminal_pode"))))
                .andExpect(status().isOk());
    }

    @Test
    void terminalPodeSemRoleRetorna403() throws Exception {
        mockMvc.perform(get("/consumer/terminal-pode/ping").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminalNaoPodeComRoleTerminalPodeRetorna403() throws Exception {
        // prova que os dois endpoints exigem roles diferentes - ter
        // "terminal_pode" nao da acesso ao endpoint de "terminal_nao_pode".
        mockMvc.perform(get("/consumer/terminal-nao-pode/ping")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_terminal_pode"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminalNaoPodeComRoleCertaRetorna200() throws Exception {
        mockMvc.perform(get("/consumer/terminal-nao-pode/ping")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_terminal_nao_pode"))))
                .andExpect(status().isOk());
    }
}
