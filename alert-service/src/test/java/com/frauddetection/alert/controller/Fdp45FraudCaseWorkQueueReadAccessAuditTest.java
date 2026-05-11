package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueReadAccessAuditTest {

    @Test
    void shouldAuditSuccessfulWorkQueueReadsAfterServiceReturn() {
        FraudCaseManagementService service = mock(FraudCaseManagementService.class);
        SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
        FraudCaseController controller = new FraudCaseController(
                service,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                mock(AlertServiceMetrics.class),
                auditService
        );
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(item()), 0, 20, false, null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fraud-cases/work-queue");

        controller.workQueue(0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, request, new LinkedMultiValueMap<>());

        verify(auditService).audit(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq(null),
                eq(1),
                eq(request)
        );
    }

    @Test
    void shouldNotAuditSuccessfulReadWhenQueryFails() {
        FraudCaseManagementService service = mock(FraudCaseManagementService.class);
        SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
        FraudCaseController controller = new FraudCaseController(
                service,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                mock(AlertServiceMetrics.class),
                auditService
        );
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("query failed"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.workQueue(
                0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, new MockHttpServletRequest(), new LinkedMultiValueMap<>()
        )).isInstanceOf(IllegalStateException.class);
        verify(auditService, never()).audit(any(), any(), any(), any(), any());
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
