package com.frauddetection.alert.security.config;

import com.frauddetection.alert.assistant.AnalystCaseSummaryUseCase;
import com.frauddetection.alert.controller.AlertController;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.DemoAuthHeaderParser;
import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import com.frauddetection.alert.security.auth.JwtAnalystAuthenticationConverter;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.service.AlertManagementUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
@Import({
        AlertSecurityConfig.class,
        DemoAuthSecurityConfig.class,
        JwtResourceServerSecurityConfig.class,
        AnalystAuthenticationFactory.class,
        DemoAuthHeaderParser.class,
        JwtAnalystAuthenticationConverter.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        AlertResponseMapper.class,
        AlertServiceExceptionHandler.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.demo-auth.enabled=true",
        "app.security.jwt.enabled=true",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.user-id-claim=sub",
        "app.security.jwt.access-claim=groups"
})
class AlertSecurityConfigJwtPreferredOverDemoTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertManagementUseCase alertManagementUseCase;

    @MockBean
    private AnalystCaseSummaryUseCase analystCaseSummaryUseCase;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private AlertServiceMetrics alertServiceMetrics;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(alertManagementUseCase.listAlerts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void shouldIgnoreDemoHeadersWhenJwtAuthIsEnabled() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header(DemoAuthHeaders.USER_ID, "analyst-1")
                        .header(DemoAuthHeaders.ROLES, "FRAUD_OPS_ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.details[0]").value("reason:invalid_demo_auth"));
    }

    @Test
    void shouldAuthenticateFromJwtEvenWhenDemoHeadersArePresent() throws Exception {
        when(jwtDecoder.decode(eq("token-analyst"))).thenReturn(jwt("analyst-1", List.of("fraud-analyst")));

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer token-analyst")
                        .header(DemoAuthHeaders.USER_ID, "different-demo-user")
                        .header(DemoAuthHeaders.ROLES, "FRAUD_OPS_ADMIN"))
                .andExpect(status().isOk());
    }

    private Jwt jwt(String userId, List<String> groups) {
        return new Jwt(
                "token-value",
                Instant.parse("2026-04-23T10:00:00Z"),
                Instant.parse("2026-04-23T11:00:00Z"),
                Map.of("alg", "none"),
                Map.of("sub", userId, "groups", groups)
        );
    }
}
