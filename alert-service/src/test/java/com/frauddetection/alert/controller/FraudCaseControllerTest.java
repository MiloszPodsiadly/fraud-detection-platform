package com.frauddetection.alert.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.FraudCaseSummaryResponse;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.fraudcase.FraudCaseConflictException;
import com.frauddetection.alert.fraudcase.FraudCaseIdempotencyConflictException;
import com.frauddetection.alert.fraudcase.FraudCaseIdempotencyInProgressException;
import com.frauddetection.alert.fraudcase.FraudCaseInvalidIdempotencyKeyException;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @Test
    void shouldCreateFraudCase() throws Exception {
        when(fraudCaseManagementService.createCase(any(), eq("create-key-1"))).thenReturn(caseDocument());

        CreateFraudCaseRequest request = new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        );

        mockMvc.perform(post("/api/fraud-cases")
                        .header("X-Idempotency-Key", "create-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.caseNumber").value("FC-20260510-ABCDEF12"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void shouldSearchFraudCases() throws Exception {
        when(fraudCaseManagementService.searchCases(
                eq(FraudCaseStatus.OPEN),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(new FraudCaseSummaryResponse(
                "case-1",
                "FC-20260510-ABCDEF12",
                FraudCaseStatus.OPEN,
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "investigator-1",
                List.of("alert-1"),
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:00:00Z")
        ))));

        mockMvc.perform(get("/api/fraud-cases").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].caseId").value("case-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldReturnConflictForLifecycleViolation() throws Exception {
        when(fraudCaseManagementService.assignCase(eq("case-1"), any(), eq("assign-key-1")))
                .thenThrow(new FraudCaseConflictException("Closed case cannot be assigned."));

        mockMvc.perform(post("/api/fraud-cases/case-1/assign")
                        .header("X-Idempotency-Key", "assign-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignedInvestigatorId\":\"investigator-1\",\"actorId\":\"lead-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("reason:FRAUD_CASE_LIFECYCLE_CONFLICT"));
    }

    @Test
    void shouldReturnConflictForRepeatedCloseAndReopen() throws Exception {
        when(fraudCaseManagementService.closeCase(eq("case-1"), any(), eq("close-key-1")))
                .thenThrow(new FraudCaseConflictException("Forbidden fraud case status transition: CLOSED -> CLOSED"));
        when(fraudCaseManagementService.reopenCase(eq("case-1"), any(), eq("reopen-key-1")))
                .thenThrow(new FraudCaseConflictException("Forbidden fraud case status transition: REOPENED -> REOPENED"));

        mockMvc.perform(post("/api/fraud-cases/case-1/close")
                        .header("X-Idempotency-Key", "close-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"closureReason\":\"Done\",\"actorId\":\"lead-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("reason:FRAUD_CASE_LIFECYCLE_CONFLICT"));

        mockMvc.perform(post("/api/fraud-cases/case-1/reopen")
                        .header("X-Idempotency-Key", "reopen-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"New evidence\",\"actorId\":\"lead-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("reason:FRAUD_CASE_LIFECYCLE_CONFLICT"));
    }

    @Test
    void shouldRejectInvalidCreateRequest() throws Exception {
        mockMvc.perform(post("/api/fraud-cases")
                        .header("X-Idempotency-Key", "create-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertIds\":[],\"priority\":\"HIGH\",\"actorId\":\"analyst-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnLocalMissingIdempotencyErrorForLifecyclePost() throws Exception {
        mockMvc.perform(post("/api/fraud-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alertIds":["alert-1"],"priority":"HIGH","riskLevel":"CRITICAL","actorId":"analyst-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:MISSING_IDEMPOTENCY_KEY"));

        verify(fraudCaseManagementService, never()).createCase(any(), any());
    }

    @Test
    void shouldReturnMissingIdempotencyForNotesWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/fraud-cases/case-1/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"note\",\"actorId\":\"analyst-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:MISSING_IDEMPOTENCY_KEY"));

        verify(fraudCaseManagementService, never()).addNote(any(), any(), any());
    }

    @Test
    void shouldMapInvalidConflictAndInProgressIdempotencyErrorsSafely() throws Exception {
        when(fraudCaseManagementService.createCase(any(), eq("invalid key")))
                .thenThrow(new FraudCaseInvalidIdempotencyKeyException());
        when(fraudCaseManagementService.createCase(any(), eq("conflict-key")))
                .thenThrow(new FraudCaseIdempotencyConflictException());
        when(fraudCaseManagementService.addNote(eq("case-1"), any(), eq("progress-key")))
                .thenThrow(new FraudCaseIdempotencyInProgressException());

        String invalid = mockMvc.perform(post("/api/fraud-cases")
                        .header("X-Idempotency-Key", "invalid key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alertIds":["alert-1"],"priority":"HIGH","riskLevel":"CRITICAL","actorId":"analyst-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:INVALID_IDEMPOTENCY_KEY"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(invalid)
                .doesNotContain("invalid key")
                .doesNotContain("requestHash")
                .doesNotContain("FraudCaseInvalidIdempotencyKeyException")
                .doesNotContain("java.lang");

        String conflict = mockMvc.perform(post("/api/fraud-cases")
                        .header("X-Idempotency-Key", "conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alertIds":["alert-1"],"priority":"HIGH","riskLevel":"CRITICAL","actorId":"analyst-1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("code:IDEMPOTENCY_KEY_CONFLICT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(conflict)
                .doesNotContain("conflict-key")
                .doesNotContain("requestHash")
                .doesNotContain("FraudCaseIdempotencyConflictException")
                .doesNotContain("java.lang");

        String inProgress = mockMvc.perform(post("/api/fraud-cases/case-1/notes")
                        .header("X-Idempotency-Key", "progress-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"note\",\"actorId\":\"analyst-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("code:IDEMPOTENCY_KEY_IN_PROGRESS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(inProgress)
                .doesNotContain("progress-key")
                .doesNotContain("requestHash")
                .doesNotContain("FraudCaseIdempotencyInProgressException")
                .doesNotContain("java.lang");
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
}
