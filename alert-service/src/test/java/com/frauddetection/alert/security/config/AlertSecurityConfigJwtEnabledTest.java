package com.frauddetection.alert.security.config;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.assistant.AnalystCaseSummaryUseCase;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.audit.read.ReadAccessAuditEventDocument;
import com.frauddetection.alert.audit.read.ReadAccessAuditRepository;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.controller.AlertController;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.controller.ScoredTransactionController;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
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
import com.frauddetection.alert.fraudcase.FraudCaseManagementService;
import com.frauddetection.alert.service.ScoredTransactionSearchPolicy;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        SecurityDeniedAccessTelemetrySliceTestConfig.class,
        JwtAlertReadAuditSliceTestConfiguration.class,
        AlertResponseMapper.class,
        FraudCaseResponseMapper.class,
        ScoredTransactionResponseMapper.class,
        EngineIntelligenceResponseMapper.class,
        ScoredTransactionSearchPolicy.class,
        AlertServiceExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.security.jwt.enabled=true",
        "app.security.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json",
        "app.security.jwt.issuer-uri=https://issuer.example.test",
        "app.security.jwt.expected-audience=fraud-detection-platform-api",
        "app.security.jwt.user-id-claim=sub",
        "app.security.jwt.access-claim=groups",
        "app.sensitive-reads.audit.fail-closed=true"
})
class AlertSecurityConfigJwtEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AlertManagementUseCase alertManagementUseCase;

    @MockitoBean
    private AnalystCaseSummaryUseCase analystCaseSummaryUseCase;

    @MockitoBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockitoBean
    private TransactionMonitoringUseCase transactionMonitoringUseCase;

    @MockitoBean
    private EngineIntelligenceReadService engineIntelligenceReadService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockitoBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockitoBean
    private ReadAccessAuditRepository readAccessAuditRepository;

    @BeforeEach
    void setUp() {
        when(alertManagementUseCase.listAlerts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(jwtDecoder.decode(startsWith("token-"))).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "token-analyst" -> jwt("analyst-1", List.of("fraud-analyst"));
            case "token-invalid" -> throw new org.springframework.security.oauth2.jwt.BadJwtException("invalid token");
            case "token-no-alert-read" -> jwt("analyst-no-alert-read", List.of("unmapped-group"));
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

        org.mockito.ArgumentCaptor<ReadAccessAuditEventDocument> auditEvent =
                org.mockito.ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(readAccessAuditRepository, times(1)).save(auditEvent.capture());
        org.assertj.core.api.Assertions.assertThat(auditEvent.getValue().actorId())
                .isEqualTo(PolicyGovernanceActor.identityId("https://issuer.example.test", "analyst-1"));
        org.assertj.core.api.Assertions.assertThat(auditEvent.getValue().endpointCategory())
                .isEqualTo(com.frauddetection.alert.audit.read.ReadAccessEndpointCategory.ALERT_LIST);
        org.assertj.core.api.Assertions.assertThat(auditEvent.getValue().resourceType())
                .isEqualTo(com.frauddetection.alert.audit.read.ReadAccessResourceType.ALERT);
        org.assertj.core.api.Assertions.assertThat(auditEvent.getValue().outcome())
                .isEqualTo(com.frauddetection.alert.audit.read.ReadAccessAuditOutcome.SUCCESS);
        verifyNoInteractions(sensitiveReadAuditService);
    }

    @Test
    void shouldDenyAlertListBeforeCallingProtectedUseCaseOrPersistingAudit() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer token-no-alert-read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(alertManagementUseCase, readAccessAuditRepository, sensitiveReadAuditService);
    }

    @Test
    void shouldReturn401WhenJwtIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer token-invalid"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(readAccessAuditRepository);
    }

    @Test
    void shouldReturn503WithoutAlertContentWhenMandatoryReadAuditPersistenceFails() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private mongo detail"))
                .when(readAccessAuditRepository).save(any());

        var response = mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer token-analyst"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse();

        org.assertj.core.api.Assertions.assertThat(response.getContentAsString())
                .contains("Sensitive read audit unavailable.")
                .doesNotContain("private mongo detail", "customer", "transaction");
        verify(readAccessAuditRepository, times(1)).save(any());
        verifyNoInteractions(sensitiveReadAuditService);
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
        when(fraudCaseManagementService.updateCase(eq("case-1"), any(), eq("case-update-1")))
                .thenReturn(updateFraudCaseResponse());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("Authorization", "Bearer token-admin")
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"));
    }

    @Test
    void shouldAllowJwtMappedReviewerToUpdateFraudCase() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any(), eq("case-update-1")))
                .thenReturn(updateFraudCaseResponse());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("Authorization", "Bearer token-reviewer")
                        .header("X-Idempotency-Key", "case-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"));
    }

    private org.springframework.security.oauth2.jwt.Jwt jwt(String userId, List<String> groups) {
        return new org.springframework.security.oauth2.jwt.Jwt(
                "token-value",
                Instant.parse("2026-04-23T10:00:00Z"),
                Instant.parse("2026-04-23T11:00:00Z"),
                Map.of("alg", "none"),
                Map.of("iss", "https://issuer.example.test", "sub", userId,
                        "aud", List.of("fraud-detection-platform-api"), "groups", groups)
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

    private UpdateFraudCaseResponse updateFraudCaseResponse() {
        FraudCaseDocument document = fraudCaseDocument();
        FraudCaseResponse updated = new FraudCaseResponse(
                document.getCaseId(),
                document.getCustomerId(),
                null,
                document.getStatus(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of()
        );
        return new UpdateFraudCaseResponse(
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                null,
                "idem-hash",
                "case-1",
                null,
                updated,
                null
        );
    }
}
