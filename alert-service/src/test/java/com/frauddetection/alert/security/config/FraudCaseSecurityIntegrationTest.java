package com.frauddetection.alert.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.FraudCaseAuditResponse;
import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.error.ApiAccessDeniedHandler;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import com.frauddetection.alert.security.error.SecurityErrorResponseWriter;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCaseController.class)
@Import({
        AlertSecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        FraudCaseResponseMapper.class,
        AlertResponseMapper.class,
        AlertServiceExceptionHandler.class
})
class FraudCaseSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FraudCaseResponseMapper responseMapper;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @Test
    void shouldRequireAuthenticationForFraudCaseReadAndMutationEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));

        mockMvc.perform(post("/api/v1/fraud-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
    }

    @Test
    void shouldReturnForbiddenWhenAuthorityIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
    }

    @Test
    void shouldAllowReadAuthorityForCurrentAndLegacyReadPaths() throws Exception {
        when(fraudCaseManagementService.listCases(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(caseDocument())));
        when(fraudCaseManagementService.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse(List.of(workQueueItem()), 0, 20, false, null));
        when(fraudCaseManagementService.getCase("case-1")).thenReturn(caseDocument());

        mockMvc.perform(get("/api/v1/fraud-cases").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].caseId").value("case-1"));
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].linkedAlertCount").value(1));
        mockMvc.perform(get("/api/fraud-cases/case-1").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
    }

    @Test
    void shouldDenyMutationsForReadOnlyAuthorityAndAllowUpdateAuthorityOnBothPaths() throws Exception {
        when(fraudCaseManagementService.createCase(any(), any())).thenReturn(responseMapper.toResponse(caseDocument()));
        when(fraudCaseManagementService.assignCase(any(), any(), any())).thenReturn(responseMapper.toResponse(caseDocument()));

        mockMvc.perform(post("/api/v1/fraud-cases")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ))
                        .header("X-Idempotency-Key", "case-create-readonly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/fraud-cases")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .header("X-Idempotency-Key", "case-create-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));

        mockMvc.perform(post("/api/fraud-cases/case-1/assign")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .header("X-Idempotency-Key", "case-assign-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedInvestigatorId\":\"investigator-1\",\"actorId\":\"lead-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
    }

    @Test
    void shouldValidateMissingIdempotencyAfterAuthorization() throws Exception {
        mockMvc.perform(post("/api/v1/fraud-cases")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:MISSING_IDEMPOTENCY_KEY"));

        mockMvc.perform(post("/api/v1/fraud-cases")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
    }

    @Test
    void shouldRequireDedicatedAuthorityForFraudCaseAuditTrail() throws Exception {
        when(fraudCaseManagementService.auditTrail("case-1")).thenReturn(List.of(new FraudCaseAuditResponse(
                "audit-1",
                "case-1",
                FraudCaseAuditAction.CASE_CREATED,
                "analyst-1",
                Instant.parse("2026-05-10T10:00:00Z"),
                null,
                FraudCaseStatus.OPEN,
                java.util.Map.of()
        )));

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/audit")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/audit")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_AUDIT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actorId").value("analyst-1"));

        mockMvc.perform(get("/api/fraud-cases/case-1/audit")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_AUDIT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CASE_CREATED"));
    }

    @Test
    void shouldNotExposeInternalExceptionDetails() throws Exception {
        when(fraudCaseManagementService.getCase("case-1")).thenThrow(new IllegalStateException("database stack trace marker"));

        mockMvc.perform(get("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("database stack trace marker"))));
    }

    private RequestPostProcessor userWith(String... authorities) {
        return authentication(new UsernamePasswordAuthenticationToken(
                "analyst-1",
                null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
        ));
    }

    private String createPayload() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "alertIds", List.of("alert-1"),
                "priority", "HIGH",
                "riskLevel", "CRITICAL",
                "reason", "Manual investigation",
                "actorId", "analyst-1"
        ));
    }

    private FraudCaseDocument caseDocument() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setCaseNumber("FC-20260510-ABCDEF12");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setLinkedAlertIds(List.of("alert-1"));
        document.setTransactionIds(List.of());
        document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        return document;
    }

    private FraudCaseWorkQueueItemResponse workQueueItem() {
        return new FraudCaseWorkQueueItemResponse(
                "case-1",
                "FC-20260510-ABCDEF12",
                FraudCaseStatus.OPEN,
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                null,
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:00:00Z"),
                60L,
                60L,
                FraudCaseSlaStatus.WITHIN_SLA,
                Instant.parse("2026-05-11T10:00:00Z"),
                1
        );
    }
}
