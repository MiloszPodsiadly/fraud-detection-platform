package com.frauddetection.alert.security.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BffLogoutSuccessHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);

    @Test
    void returnsProviderLogoutUrlWithoutEmbeddingBearerToken() throws Exception {
        BffLogoutSuccessHandler handler = new BffLogoutSuccessHandler(new BffSecurityProperties(
                true,
                "http://localhost:4173/",
                "http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout",
                "analyst-console-ui"
        ), objectMapper, metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getIdToken()).thenReturn(new OidcIdToken(
                "id-token-1",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("sub", "opsadmin")
        ));
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(oidcUser, "n/a");

        handler.onLogoutSuccess(new MockHttpServletRequest(), response, authentication);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        String logoutUrl = body.get("logoutUrl").asText();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(logoutUrl).startsWith("http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout");
        assertThat(logoutUrl).contains("client_id=analyst-console-ui");
        assertThat(logoutUrl).contains("post_logout_redirect_uri=");
        assertThat(logoutUrl).contains("id_token_hint=id-token-1");
        assertThat(logoutUrl).doesNotContain("Bearer");
        assertThat(meterRegistry.get("bff_logout_requests_total")
                .tag("outcome", "success")
                .tag("redirect", "provider")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void omitsIdTokenHintWhenAuthenticationIsNotOidcBacked() throws Exception {
        BffLogoutSuccessHandler handler = new BffLogoutSuccessHandler(new BffSecurityProperties(
                true,
                "http://localhost:4173/",
                "http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout",
                "analyst-console-ui"
        ), objectMapper, metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onLogoutSuccess(new MockHttpServletRequest(), response, null);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("logoutUrl").asText()).doesNotContain("id_token_hint");
    }

    @Test
    void fallsBackToLocalRootWhenProviderLogoutIsNotConfigured() throws Exception {
        BffLogoutSuccessHandler handler = new BffLogoutSuccessHandler(new BffSecurityProperties(
                true,
                "/",
                "",
                "analyst-console-ui"
        ), objectMapper, metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onLogoutSuccess(new MockHttpServletRequest(), response, null);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("logoutUrl").asText()).isEqualTo("/");
        assertThat(meterRegistry.get("bff_logout_requests_total")
                .tag("outcome", "success")
                .tag("redirect", "local")
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
