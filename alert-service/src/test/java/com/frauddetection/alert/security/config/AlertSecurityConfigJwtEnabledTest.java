package com.frauddetection.alert.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.assistant.AnalystCaseSummaryUseCase;
import com.frauddetection.alert.controller.AlertController;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.controller.ScoredTransactionController;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.JwtAnalystAuthenticationConverter;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        AlertController.class,
        FraudCaseController.class,
        ScoredTransactionController.class
})
@Import({
        AlertSecurityConfig.class,
        JwtResourceServerSecurityConfig.class,
        AnalystAuthenticationFactory.class,
        JwtAnalystAuthenticationConverter.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        AlertResponseMapper.class,
        FraudCaseResponseMapper.class,
        ScoredTransactionResponseMapper.class,
        AlertServiceExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.security.jwt.enabled=true",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.user-id-claim=sub",
        "app.security.jwt.access-claim=groups"
})
class AlertSecurityConfigJwtEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AlertManagementUseCase alertManagementUseCase;

    @MockBean
    private AnalystCaseSummaryUseCase analystCaseSummaryUseCase;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockBean
    private TransactionMonitoringUseCase transactionMonitoringUseCase;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private AlertServiceMetrics alertServiceMetrics;

    @BeforeEach
    void setUp() {
        when(alertManagementUseCase.listAlerts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(fraudCaseManagementService.listCases(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(jwtDecoder.decode(startsWith("token-"))).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "token-analyst" -> jwt("analyst-1", List.of("fraud-analyst"));
            case "token-readonly" -> jwt("readonly-1", List.of("fraud-readonly-analyst"));
            case "token-reviewer" -> jwt("reviewer-1", List.of("fraud-reviewer"));
            case "token-admin" -> jwt("admin-1", List.of("fraud-ops-admin"));
            default -> throw new IllegalArgumentException("Unexpected token");
        });
    }

    @Test
    void shouldReturn401WhenJwtResourceServerIsEnabledAndBearerTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
    }

    @Test
    void shouldAllowValidJwtWithMappedAuthorityToListAlerts() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer token-analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void shouldReturn403WhenJwtDoesNotMapToRequiredAuthority() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .header("Authorization", "Bearer token-readonly")
                        .header("X-Idempotency-Key", "idem-readonly-jwt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDecisionRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Insufficient permissions."))
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
    }

    @Test
    void shouldAllowFraudOpsAdminJwtToOverrideWriteChecks() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any()))
                .thenReturn(fraudCaseDocument());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("Authorization", "Bearer token-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
    }

    @Test
    void shouldAllowJwtMappedReviewerToUpdateFraudCase() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any()))
                .thenReturn(fraudCaseDocument());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("Authorization", "Bearer token-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
    }

    private org.springframework.security.oauth2.jwt.Jwt jwt(String userId, List<String> groups) {
        return new org.springframework.security.oauth2.jwt.Jwt(
                "token-value",
                Instant.parse("2026-04-23T10:00:00Z"),
                Instant.parse("2026-04-23T11:00:00Z"),
                Map.of("alg", "none"),
                Map.of("sub", userId, "groups", groups)
        );
    }

    private SubmitAnalystDecisionRequest submitDecisionRequest() {
        return new SubmitAnalystDecisionRequest(
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Manual confirmation",
                List.of("kyc"),
                Map.of()
        );
    }

    private UpdateFraudCaseRequest updateFraudCaseRequest() {
        return new UpdateFraudCaseRequest(
                FraudCaseStatus.CONFIRMED_FRAUD,
                "analyst-1",
                "Confirmed rapid transfer abuse.",
                List.of("rapid-transfer")
        );
    }

    private FraudCaseDocument fraudCaseDocument() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setCustomerId("cust-1");
        document.setStatus(FraudCaseStatus.CONFIRMED_FRAUD);
        document.setTransactionIds(List.of());
        document.setTransactions(List.of());
        return document;
    }
}
