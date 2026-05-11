package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.ReadAccessAuditRepository;
import com.frauddetection.alert.audit.read.ReadAccessAuditResponseAdvice;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessResultCountExtractor;
import com.frauddetection.alert.audit.read.SensitiveReadAuditPolicy;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@Import({
        AlertResponseMapper.class,
        FraudCaseResponseMapper.class,
        AlertServiceExceptionHandler.class,
        ReadAccessAuditClassifier.class,
        ReadAccessResultCountExtractor.class,
        ReadAccessAuditResponseAdvice.class,
        ReadAccessAuditService.class,
        SensitiveReadAuditService.class
})
class Fdp45FraudCaseWorkQueueAuditPrecedenceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCaseManagementService fraudCaseManagementService;

    @MockBean
    private ReadAccessAuditRepository repository;

    @MockBean
    private CurrentAnalystUser currentAnalystUser;

    @MockBean
    private SensitiveReadAuditPolicy policy;

    @MockBean
    private AlertServiceMetrics metrics;

    @BeforeEach
    void setUp() {
        when(currentAnalystUser.get()).thenReturn(Optional.of(new AnalystPrincipal(
                "analyst-1",
                Set.of(AnalystRole.ANALYST),
                Set.of("fraud-case:read")
        )));
    }

    @Test
    void shouldReturnBadRequestForInvalidQueryWhenRejectedAuditPersists() throws Exception {
        when(policy.failClosed()).thenReturn(false);

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue")
                        .queryParam("assignee", "analyst-1")
                        .queryParam("assignedInvestigatorId", "analyst-2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code:INVALID_FILTER"));
    }

    @Test
    void shouldReturnServiceUnavailableForInvalidQueryWhenFailClosedAuditIsUnavailable() throws Exception {
        when(policy.failClosed()).thenReturn(true);
        doThrow(new IllegalStateException("audit store unavailable")).when(repository).save(any());

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue")
                        .queryParam("assignee", "analyst-1")
                        .queryParam("assignedInvestigatorId", "analyst-2"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldReturnServiceUnavailableForServiceFailureWhenFailClosedAuditIsUnavailable() throws Exception {
        when(policy.failClosed()).thenReturn(true);
        doThrow(new IllegalStateException("audit store unavailable")).when(repository).save(any());
        when(fraudCaseManagementService.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("mongo unavailable"));

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldReturnServiceUnavailableAndAvoidSuccessMetricsWhenSuccessAuditFailsClosed() throws Exception {
        when(policy.failClosed()).thenReturn(true);
        doThrow(new IllegalStateException("audit store unavailable")).when(repository).save(any());
        when(fraudCaseManagementService.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));

        mockMvc.perform(get("/api/v1/fraud-cases/work-queue"))
                .andExpect(status().isServiceUnavailable());

        verify(metrics, never()).recordFraudCaseWorkQueueRequest("success");
        verify(metrics, never()).recordFraudCaseWorkQueueQuery(org.mockito.ArgumentMatchers.eq("success"), any());
    }
}
