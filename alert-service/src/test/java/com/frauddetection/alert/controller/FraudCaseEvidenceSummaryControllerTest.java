package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.service.FraudCaseEvidenceSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = FraudCaseEvidenceSummaryController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(AlertServiceExceptionHandler.class)
class FraudCaseEvidenceSummaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCaseEvidenceSummaryService service;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @Test
    void FraudCaseEvidenceSummaryEndpointReturnsReadOnlyProjectionTest() throws Exception {
        when(service.summary("case-1")).thenReturn(response());

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.aggregateEvidenceStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.evidenceItemCount").value(1))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fraudConfirmed"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("finalOutcome"))));
    }

    @Test
    void FraudCaseEvidenceSummaryMissingCaseReturnsSafeNotFoundTest() throws Exception {
        when(service.summary("missing-case")).thenThrow(new FraudCaseNotFoundException("missing-case"));

        mockMvc.perform(get("/api/v1/fraud-cases/missing-case/evidence-summary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("reason:FRAUD_CASE_NOT_FOUND"));
    }

    @Test
    void FraudCaseEvidenceSummaryAuditTest() throws Exception {
        when(service.summary("case-1")).thenReturn(response());

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary"))
                .andExpect(status().isOk());

        verify(sensitiveReadAuditService).audit(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_SUMMARY),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(1),
                any()
        );
    }

    @Test
    void FraudCaseEvidenceSummaryMissingCaseAuditedAsRejectedTest() throws Exception {
        when(service.summary("missing-case")).thenThrow(new FraudCaseNotFoundException("missing-case"));

        mockMvc.perform(get("/api/v1/fraud-cases/missing-case/evidence-summary"))
                .andExpect(status().isNotFound());

        verify(sensitiveReadAuditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_SUMMARY),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("missing-case"),
                eq(ReadAccessAuditOutcome.REJECTED),
                any()
        );
    }

    @Test
    void FraudCaseEvidenceSummaryUnexpectedExceptionAuditedAsFailedTest() throws Exception {
        when(service.summary("case-1")).thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(get("/api/v1/fraud-cases/case-1/evidence-summary"))
                .andExpect(status().isInternalServerError());

        verify(sensitiveReadAuditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_EVIDENCE_SUMMARY),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq("case-1"),
                eq(ReadAccessAuditOutcome.FAILED),
                any()
        );
    }

    private FraudCaseEvidenceSummaryResponse response() {
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
                Instant.parse("2026-05-22T10:00:00Z")
        );
    }
}
