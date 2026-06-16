package com.frauddetection.alert.security.config;

import tools.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventType;
import com.frauddetection.alert.api.FraudCaseTimelineLinkedEntityType;
import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.controller.FraudCaseEvidenceSummaryController;
import com.frauddetection.alert.controller.FraudCaseEvidenceTimelineController;
import com.frauddetection.alert.controller.FraudCaseWorkQueueSummaryController;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.evidence.EvidenceStatus;
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
import com.frauddetection.alert.service.FraudCaseEvidenceSummaryService;
import com.frauddetection.alert.service.FraudCaseEvidenceTimelineService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FraudCaseController.class, FraudCaseEvidenceSummaryController.class, FraudCaseEvidenceTimelineController.class, FraudCaseWorkQueueSummaryController.class})
@Import({
        AlertSecurityConfig.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        SecurityDeniedAccessTelemetrySliceTestConfig.class,
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

    @MockitoBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockitoBean
    private FraudCaseEvidenceSummaryService fraudCaseEvidenceSummaryService;

    @MockitoBean
    private FraudCaseEvidenceTimelineService fraudCaseEvidenceTimelineService;

    @MockitoBean
    private FraudCaseQueryService fraudCaseQueryService;

    @MockitoBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockitoBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @Test
    void shouldRequireAuthenticationForFraudCaseReadAndMutationEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details[0]").value("reason:missing_credentials"));
    }

    @Test
    void shouldReturnForbiddenWhenAuthorityIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue/summary")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
    }

    @Test
    void shouldAllowReadAuthorityForCurrentReadPaths() throws Exception {
        when(fraudCaseManagementService.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse(List.of(workQueueItem()), 0, 20, false, null));
        when(fraudCaseQueryService.globalFraudCaseSummary())
                .thenReturn(new FraudCaseWorkQueueSummaryResponse(42, Instant.parse("2026-05-12T10:00:00Z")));
        when(fraudCaseManagementService.getCase("case-1")).thenReturn(caseDocument());
        when(fraudCaseEvidenceSummaryService.summary("case-1")).thenReturn(evidenceSummary());
        when(fraudCaseEvidenceTimelineService.timeline("case-1")).thenReturn(evidenceTimeline());

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].linkedAlertCount").value(1));
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue/summary").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFraudCases").value(42))
                .andExpect(jsonPath("$.scope").value("GLOBAL_FRAUD_CASES"))
                .andExpect(jsonPath("$.snapshotConsistentWithWorkQueue").value(false));
        mockMvc.perform(get("/api/v1/fraud-cases/case-1").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.aggregateEvidenceStatus").value("AVAILABLE"));
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline").with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.events[0].eventType").value("FRAUD_CASE_CREATED"));
    }

    @Test
    void FraudCaseEvidenceTimelineRequiresFraudCaseReadTest() throws Exception {
        when(fraudCaseEvidenceTimelineService.timeline("case-1")).thenReturn(evidenceTimeline());

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ)))
                .andExpect(status().isOk());
    }

    @Test
    void FraudCaseEvidenceTimelineRejectsAnonymousTest() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void FraudCaseEvidenceTimelineRejectsAlertReadOnlyTest() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline")
                        .with(userWith(AnalystAuthority.ALERT_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void FraudCaseEvidenceTimelineRejectsSuspiciousTransactionReadOnlyTest() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline")
                        .with(userWith(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void FraudCaseEvidenceTimelineRejectsUnrelatedAuthorityTest() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline")
                        .with(userWith(AnalystAuthority.TRANSACTION_MONITOR_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotRequireSuspiciousTransactionReadAuthorityTest() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary")
                        .with(userWith(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void FraudCaseEvidenceSummaryDoesNotUseAlertReadAuthorityAsSubstituteTest() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary")
                        .with(userWith(AnalystAuthority.ALERT_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyMutationsForReadOnlyAuthorityAndAllowUpdateAuthorityOnCurrentPath() throws Exception {
        when(fraudCaseManagementService.updateCase(any(), any(), any()))
                .thenReturn(new UpdateFraudCaseResponse(
                        SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED,
                        "command-1",
                        "hash-1",
                        "case-1",
                        null,
                        responseMapper.toResponse(caseDocument()),
                        null
                ));

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ))
                        .header("X-Idempotency-Key", "case-update-readonly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload()))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .header("X-Idempotency-Key", "case-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"));
    }

    @Test
    void shouldValidateMissingIdempotencyAfterAuthorization() throws Exception {
        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_UPDATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:MISSING_IDEMPOTENCY_KEY"));

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .with(userWith(AnalystAuthority.FRAUD_CASE_READ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("reason:insufficient_authority"));
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

    private String updatePayload() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "status", "IN_REVIEW",
                "analystId", "analyst-1",
                "decisionReason", "Manual investigation",
                "tags", List.of()
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

    private FraudCaseEvidenceSummaryResponse evidenceSummary() {
        return new FraudCaseEvidenceSummaryResponse(
                "case-1",
                EvidenceStatus.AVAILABLE,
                List.of("HIGH_AMOUNT_ACTIVITY"),
                List.of(),
                List.of(),
                List.of(),
                1,
                1,
                false,
                false,
                false,
                null,
                Instant.parse("2026-05-12T10:00:00Z")
        );
    }

    private FraudCaseEvidenceTimelineResponse evidenceTimeline() {
        return new FraudCaseEvidenceTimelineResponse(
                "case-1",
                List.of(new FraudCaseTimelineEventResponse(
                        "FRAUD_CASE_CREATED_000001",
                        FraudCaseTimelineEventType.FRAUD_CASE_CREATED,
                        Instant.parse("2026-05-12T10:00:00Z"),
                        com.frauddetection.alert.evidence.EvidenceSource.ALERT_SERVICE,
                        EvidenceStatus.AVAILABLE,
                        "Fraud case created",
                        "Read-only timeline event derived from existing fraud-case read data.",
                        FraudCaseTimelineLinkedEntityType.FRAUD_CASE,
                        false
                )),
                false,
                false,
                false,
                null,
                Instant.parse("2026-05-12T10:00:00Z")
        );
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
