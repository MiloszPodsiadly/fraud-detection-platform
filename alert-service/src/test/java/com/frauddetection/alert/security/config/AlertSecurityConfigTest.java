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
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryController;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryListResponse;
import com.frauddetection.alert.governance.audit.GovernanceAdvisoryProjectionService;
import com.frauddetection.alert.governance.audit.GovernanceAuditController;
import com.frauddetection.alert.governance.audit.GovernanceAuditDecision;
import com.frauddetection.alert.governance.audit.GovernanceAuditEventResponse;
import com.frauddetection.alert.governance.audit.GovernanceAuditService;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.mapper.ScoredTransactionResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.DemoAuthHeaderParser;
import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        ScoredTransactionController.class,
        GovernanceAdvisoryController.class,
        GovernanceAuditController.class
})
@Import({
        AlertSecurityConfig.class,
        DemoAuthSecurityConfig.class,
        AnalystAuthenticationFactory.class,
        DemoAuthHeaderParser.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        AlertResponseMapper.class,
        FraudCaseResponseMapper.class,
        ScoredTransactionResponseMapper.class,
        AlertServiceExceptionHandler.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.demo-auth.enabled=true")
class AlertSecurityConfigTest {

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
    private AlertServiceMetrics alertServiceMetrics;

    @MockBean
    private GovernanceAuditService governanceAuditService;

    @MockBean
    private GovernanceAdvisoryProjectionService governanceAdvisoryProjectionService;

    @Test
    void shouldReturn401WhenAuthenticationIsMissingForAnalystEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"))
                .andExpect(jsonPath("$.message").value("Authentication is required."));
        mockMvc.perform(get("/api/v1/alerts/alert-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/alerts/alert-1/assistant-summary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDecisionRequest())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fraud-cases"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fraud-cases/case-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/transactions/scored"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/governance/advisories"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/governance/advisories/advisory-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/governance/advisories/advisory-1/audit"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/governance/advisories/advisory-1/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnStable401BodyForInvalidDemoRoleWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").with(demoUser("UNKNOWN_ROLE")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.details[0]").value("reason:invalid_demo_auth"));
    }

    @Test
    void shouldAllowReadOnlyAnalystToListAlerts() throws Exception {
        when(alertManagementUseCase.listAlerts(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/alerts").with(demoUser("READ_ONLY_ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void shouldAllowReadOnlyAnalystToReadScoredTransactions() throws Exception {
        when(transactionMonitoringUseCase.listScoredTransactions(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/transactions/scored").with(demoUser("READ_ONLY_ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void shouldAllowReadOnlyAnalystToReadGovernanceAuditHistoryOnly() throws Exception {
        when(governanceAdvisoryProjectionService.listAdvisories(any(), any()))
                .thenReturn(new GovernanceAdvisoryListResponse("AVAILABLE", 0, 200, List.of()));
        when(governanceAuditService.history("advisory-1"))
                .thenReturn(new com.frauddetection.alert.governance.audit.GovernanceAuditHistoryResponse(
                        "advisory-1",
                        "AVAILABLE",
                        List.of()
                ));

        mockMvc.perform(get("/governance/advisories").with(demoUser("READ_ONLY_ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        mockMvc.perform(get("/governance/advisories/advisory-1/audit").with(demoUser("READ_ONLY_ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisory_event_id").value("advisory-1"));

        mockMvc.perform(post("/governance/advisories/advisory-1/audit")
                        .with(demoUser("READ_ONLY_ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowReadOnlyAnalystToListFraudCases() throws Exception {
        when(fraudCaseManagementService.listCases(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/fraud-cases").with(demoUser("READ_ONLY_ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void shouldReturn403WhenReadOnlyAnalystSubmitsDecision() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .with(demoUser("READ_ONLY_ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDecisionRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"))
                .andExpect(jsonPath("$.message").value("Insufficient permissions."));
    }

    @Test
    void shouldAllowAnalystToSubmitDecision() throws Exception {
        when(alertManagementUseCase.submitDecision(eq("alert-1"), any()))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        AnalystDecision.CONFIRMED_FRAUD,
                        AlertStatus.RESOLVED,
                        "event-1",
                        Instant.parse("2026-04-20T10:00:00Z")
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .with(demoUser("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDecisionRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultingStatus").value("RESOLVED"));
    }

    @Test
    void shouldAllowAnalystToRecordGovernanceAudit() throws Exception {
        when(governanceAuditService.appendAudit(eq("advisory-1"), any()))
                .thenReturn(new GovernanceAuditEventResponse(
                        "audit-1",
                        "advisory-1",
                        GovernanceAuditDecision.ACKNOWLEDGED,
                        null,
                        "analyst-1",
                        "analyst-1",
                        List.of("ANALYST"),
                        Instant.parse("2026-04-26T00:00:00Z"),
                        "python-logistic-fraud-model",
                        "2026-04-21.trained.v1",
                        "HIGH",
                        "HIGH",
                        "SUFFICIENT_DATA"
                ));

        mockMvc.perform(post("/governance/advisories/advisory-1/audit")
                        .with(demoUser("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actor_id").value("analyst-1"));
    }

    @Test
    void shouldAllowFraudOpsAdminToSubmitDecision() throws Exception {
        when(alertManagementUseCase.submitDecision(eq("alert-1"), any()))
                .thenReturn(new SubmitAnalystDecisionResponse(
                        "alert-1",
                        AnalystDecision.CONFIRMED_FRAUD,
                        AlertStatus.RESOLVED,
                        "event-1",
                        Instant.parse("2026-04-20T10:00:00Z")
                ));

        mockMvc.perform(post("/api/v1/alerts/alert-1/decision")
                        .with(demoUser("FRAUD_OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDecisionRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultingStatus").value("RESOLVED"));
    }

    @Test
    void shouldReturn403WhenReadOnlyAnalystUpdatesFraudCase() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(demoUser("READ_ONLY_ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"))
                .andExpect(jsonPath("$.message").value("Insufficient permissions."));
    }

    @Test
    void shouldReturn403WhenAnalystUpdatesFraudCaseWithoutReviewerAuthority() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(demoUser("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"))
                .andExpect(jsonPath("$.message").value("Insufficient permissions."));
    }

    @Test
    void shouldAllowReviewerToUpdateFraudCase() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any()))
                .thenReturn(fraudCaseDocument());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(demoUser("REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
    }

    @Test
    void shouldAllowFraudOpsAdminToUpdateFraudCase() throws Exception {
        when(fraudCaseManagementService.updateCase(eq("case-1"), any()))
                .thenReturn(fraudCaseDocument());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(demoUser("FRAUD_OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateFraudCaseRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
    }

    private RequestPostProcessor demoUser(String roles) {
        return request -> {
            request.addHeader(DemoAuthHeaders.USER_ID, "analyst-1");
            request.addHeader(DemoAuthHeaders.ROLES, roles);
            return request;
        };
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
