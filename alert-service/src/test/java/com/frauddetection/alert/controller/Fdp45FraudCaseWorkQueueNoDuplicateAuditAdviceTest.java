package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.ReadAccessAuditAction;
import com.frauddetection.alert.audit.read.ReadAccessAuditClassifier;
import com.frauddetection.alert.audit.read.ReadAccessAuditEventDocument;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditRepository;
import com.frauddetection.alert.audit.read.ReadAccessAuditResponseAdvice;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.ReadAccessResultCountExtractor;
import com.frauddetection.alert.audit.read.SensitiveReadAuditPolicy;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class Fdp45FraudCaseWorkQueueNoDuplicateAuditAdviceTest {

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
        when(policy.failClosed()).thenReturn(false);
        when(currentAnalystUser.get()).thenReturn(Optional.of(new AnalystPrincipal(
                "analyst-1",
                Set.of(AnalystRole.ANALYST),
                Set.of("fraud-case:read")
        )));
        when(fraudCaseManagementService.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(item()), 0, 20, false, null));
    }

    @Test
    void shouldAuditCurrentWorkQueueSuccessExactlyOnceWithBoundedQueryMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-cases/work-queue")
                        .queryParam("page", "0")
                        .queryParam("size", "20")
                        .queryParam("sort", "createdAt,desc"))
                .andExpect(status().isOk());

        ReadAccessAuditEventDocument document = onlyAuditDocument();
        assertThat(document.action()).isEqualTo(ReadAccessAuditAction.READ);
        assertThat(document.outcome()).isEqualTo(ReadAccessAuditOutcome.SUCCESS);
        assertThat(document.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE);
        assertThat(document.resourceType()).isEqualTo(ReadAccessResourceType.FRAUD_CASE);
        assertThat(document.resultCount()).isEqualTo(1);
        assertThat(document.page()).isEqualTo(0);
        assertThat(document.size()).isEqualTo(20);
        assertThat(document.queryHash()).hasSize(32);
        assertThat(document.toString()).doesNotContain("createdAt,desc", "sort=");
    }

    @Test
    void shouldAuditLegacyWorkQueueSuccessExactlyOnce() throws Exception {
        mockMvc.perform(get("/api/fraud-cases/work-queue")
                        .queryParam("page", "0")
                        .queryParam("size", "20"))
                .andExpect(status().isOk());

        ReadAccessAuditEventDocument document = onlyAuditDocument();
        assertThat(document.outcome()).isEqualTo(ReadAccessAuditOutcome.SUCCESS);
        assertThat(document.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE);
        assertThat(document.resultCount()).isEqualTo(1);
    }

    private ReadAccessAuditEventDocument onlyAuditDocument() {
        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private FraudCaseWorkQueueItemResponse item() {
        return new FraudCaseWorkQueueItemResponse(
                "case-1",
                "FC-1",
                FraudCaseStatus.OPEN,
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "investigator-1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T11:00:00Z"),
                3600L,
                1200L,
                FraudCaseSlaStatus.WITHIN_SLA,
                Instant.parse("2026-05-11T10:00:00Z"),
                1
        );
    }
}
