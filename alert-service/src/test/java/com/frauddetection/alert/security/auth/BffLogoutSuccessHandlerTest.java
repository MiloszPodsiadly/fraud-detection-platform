package com.frauddetection.alert.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class BffLogoutSuccessHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsProviderLogoutUrlWithoutEmbeddingBearerOrIdToken() throws Exception {
        BffLogoutSuccessHandler handler = new BffLogoutSuccessHandler(new BffSecurityProperties(
                true,
                "http://localhost:4173/",
                "http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout",
                "analyst-console-ui"
        ), objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onLogoutSuccess(new MockHttpServletRequest(), response, null);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        String logoutUrl = body.get("logoutUrl").asText();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(logoutUrl).startsWith("http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout");
        assertThat(logoutUrl).contains("client_id=analyst-console-ui");
        assertThat(logoutUrl).contains("post_logout_redirect_uri=");
        assertThat(logoutUrl).doesNotContain("Bearer");
        assertThat(logoutUrl).doesNotContain("id_token_hint");
    }

    @Test
    void fallsBackToLocalRootWhenProviderLogoutIsNotConfigured() throws Exception {
        BffLogoutSuccessHandler handler = new BffLogoutSuccessHandler(new BffSecurityProperties(
                true,
                "/",
                "",
                "analyst-console-ui"
        ), objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onLogoutSuccess(new MockHttpServletRequest(), response, null);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("logoutUrl").asText()).isEqualTo("/");
    }
}
