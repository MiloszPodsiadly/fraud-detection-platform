package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = FraudCaseController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import({FraudCaseResponseMapper.class, AlertResponseMapper.class, AlertServiceExceptionHandler.class})
class FraudCaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockBean
    private AlertServiceMetrics alertServiceMetrics;

    @MockBean
    private SensitiveReadAuditService sensitiveReadAuditService;

    @Test
    void shouldReturnDedicatedWorkQueueSlice() throws Exception {
        when(fraudCaseManagementService.workQueue(
                eq(FraudCaseStatus.OPEN),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(workQueueSlice());

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].caseId").value("case-1"))
                .andExpect(jsonPath("$.content[0].linkedAlertCount").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void shouldRejectUnsupportedWorkQueueSortAndUnknownFilters() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").param("sort", "customerId,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:UNSUPPORTED_SORT_FIELD"));

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").param("customerId", "customer-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:UNSUPPORTED_FILTER"));

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:INVALID_PAGE_REQUEST"));
    }

    @Test
    void shouldReturnFraudCaseDetail() throws Exception {
        when(fraudCaseManagementService.getCase("case-1")).thenReturn(caseDocument());

        mockMvc.perform(get("/api/v1/fraud-cases/case-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void shouldPatchFraudCaseThroughCurrentUpdateSurface() throws Exception {
        when(fraudCaseManagementService.updateCase(any(), any(), any()))
                .thenReturn(new UpdateFraudCaseResponse(
                        SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED,
                        "command-1",
                        "hash-1",
                        "case-1",
                        null,
                        null,
                        null
                ));

        mockMvc.perform(patch("/api/v1/fraud-cases/case-1")
                        .header("X-Idempotency-Key", "update-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_REVIEW","analystId":"analyst-1","decisionReason":"review","tags":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_id").value("case-1"))
                .andExpect(jsonPath("$.operation_status").value("COMMITTED_EVIDENCE_CONFIRMED"));
    }

    private FraudCaseDocument caseDocument() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setCaseNumber("FC-20260510-ABCDEF12");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setLinkedAlertIds(List.of("alert-1"));
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
                "investigator-1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:00:00Z"),
                3600L,
                3600L,
                FraudCaseSlaStatus.WITHIN_SLA,
                Instant.parse("2026-05-11T10:00:00Z"),
                1
        );
    }

    private FraudCaseWorkQueueSliceResponse workQueueSlice() {
        return new FraudCaseWorkQueueSliceResponse(List.of(workQueueItem()), 0, 20, false, null);
    }
}
