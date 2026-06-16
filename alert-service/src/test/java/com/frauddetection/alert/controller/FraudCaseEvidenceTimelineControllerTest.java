package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventType;
import com.frauddetection.alert.api.FraudCaseTimelineLinkedEntityType;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.observability.FraudCaseReadModelMetrics;
import com.frauddetection.alert.observability.FraudCaseReadModelOutcome;
import com.frauddetection.alert.service.FraudCaseEvidenceTimelineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = FraudCaseEvidenceTimelineController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class FraudCaseEvidenceTimelineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudCaseEvidenceTimelineService service;

    @MockitoBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @MockitoBean
    private FraudCaseReadModelMetrics metrics;

    @Test
    void FraudCaseEvidenceTimelineEndpointReturnsReadOnlyProjectionTest() throws Exception {
        when(service.timeline("case-1")).thenReturn(response());

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.events[0].eventType").value("FRAUD_CASE_CREATED"))
                .andExpect(jsonPath("$.events[0].eventKey").value("FRAUD_CASE_CREATED_000001"))
                .andExpect(content().string(not(containsString("fraudConfirmed"))))
                .andExpect(content().string(not(containsString("finalOutcome"))));
    }

    @Test
    void FraudCaseEvidenceTimelineMissingCaseReturnsSafeNotFoundTest() throws Exception {
        when(service.timeline("missing-case")).thenThrow(new FraudCaseNotFoundException("missing-case"));

        mockMvc.perform(get("/api/v1/fraud-cases/missing-case/evidence-timeline"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:FRAUD_CASE_NOT_FOUND"));
    }

    @Test
    void FraudCaseEvidenceTimelineAuditTest() throws Exception {
        when(service.timeline("case-1")).thenReturn(response());

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isOk());

        verify(sensitiveReadAuditService).audit(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(1),
                any()
        );
        verify(metrics).recordEvidenceTimeline(FraudCaseReadModelOutcome.AVAILABLE);
    }

    @Test
    void FraudCaseEvidenceTimelineMissingCaseAuditedAsRejectedTest() throws Exception {
        when(service.timeline("missing-case")).thenThrow(new FraudCaseNotFoundException("missing-case"));

        mockMvc.perform(get("/api/v1/fraud-cases/missing-case/evidence-timeline"))
                .andExpect(status().isNotFound());

        verify(sensitiveReadAuditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("missing-case"),
                eq(ReadAccessAuditOutcome.REJECTED),
                any()
        );
        verify(metrics).recordEvidenceTimeline(FraudCaseReadModelOutcome.NOT_FOUND);
    }

    @Test
    void FraudCaseEvidenceTimelineUnexpectedExceptionAuditedAsFailedTest() throws Exception {
        when(service.timeline("case-1")).thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("database unavailable"))));

        verify(sensitiveReadAuditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(ReadAccessAuditOutcome.FAILED),
                any()
        );
        verify(metrics).recordEvidenceTimeline(FraudCaseReadModelOutcome.ERROR);
    }

    @Test
    void FraudCaseEvidenceTimelineAuditFailureFollowsExistingSensitiveReadPolicyTest() throws Exception {
        when(service.timeline("case-1")).thenReturn(response());
        doThrow(new IllegalStateException("audit backend unavailable")).when(sensitiveReadAuditService).audit(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(1),
                any()
        );

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("audit backend unavailable"))));

        verify(sensitiveReadAuditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(ReadAccessAuditOutcome.FAILED),
                any()
        );
        verify(metrics).recordEvidenceTimeline(FraudCaseReadModelOutcome.ERROR);
    }

    @Test
    void FraudCaseEvidenceTimelineMetricFailureDoesNotChangeReadOrAuditBehaviorTest() throws Exception {
        when(service.timeline("case-1")).thenReturn(response());
        doThrow(new IllegalStateException("metrics backend unavailable"))
                .when(metrics)
                .recordEvidenceTimeline(FraudCaseReadModelOutcome.AVAILABLE);

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(content().string(not(containsString("metrics backend unavailable"))));

        verify(sensitiveReadAuditService).audit(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(1),
                any()
        );
    }

    @Test
    void FraudCaseEvidenceTimelineMetricFailureDoesNotAlterNotFoundBehaviorTest() throws Exception {
        when(service.timeline("missing-case")).thenThrow(new FraudCaseNotFoundException("missing-case"));
        doThrow(new IllegalStateException("metrics backend unavailable"))
                .when(metrics)
                .recordEvidenceTimeline(FraudCaseReadModelOutcome.NOT_FOUND);

        mockMvc.perform(get("/api/v1/fraud-cases/missing-case/evidence-timeline"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:FRAUD_CASE_NOT_FOUND"))
                .andExpect(content().string(not(containsString("metrics backend unavailable"))));

        verify(sensitiveReadAuditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("missing-case"),
                eq(ReadAccessAuditOutcome.REJECTED),
                any()
        );
    }

    @Test
    void FraudCaseEvidenceTimelineMetricFailureDoesNotMaskUnexpectedFailureTest() throws Exception {
        when(service.timeline("case-1")).thenThrow(new IllegalStateException("database unavailable"));
        doThrow(new IllegalStateException("metrics backend unavailable"))
                .when(metrics)
                .recordEvidenceTimeline(FraudCaseReadModelOutcome.ERROR);

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-timeline"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("database unavailable"))))
                .andExpect(content().string(not(containsString("metrics backend unavailable"))));

        verify(sensitiveReadAuditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_TIMELINE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(ReadAccessAuditOutcome.FAILED),
                any()
        );
    }

    private FraudCaseEvidenceTimelineResponse response() {
        return new FraudCaseEvidenceTimelineResponse(
                "case-1",
                List.of(new FraudCaseTimelineEventResponse(
                        "FRAUD_CASE_CREATED_000001",
                        FraudCaseTimelineEventType.FRAUD_CASE_CREATED,
                        Instant.parse("2026-05-22T10:00:00Z"),
                        EvidenceSource.ALERT_SERVICE,
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
                Instant.parse("2026-05-22T10:00:00Z")
        );
    }
}
