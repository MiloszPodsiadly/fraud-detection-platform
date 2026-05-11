package com.frauddetection.alert.controller;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueCursorObservabilityTest {

    private final FraudCaseManagementService service = mock(FraudCaseManagementService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
    private final FraudCaseController controller = new FraudCaseController(
            service,
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            metrics,
            auditService
    );

    @Test
    void shouldRecordInvalidCursorMetricsForServiceRejectedCursorWithoutSensitiveLabels() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class), any(String.class), any(Sort.Order.class)))
                .thenThrow(new FraudCaseWorkQueueQueryException("INVALID_CURSOR", "Invalid fraud case work queue cursor."));

        assertThatThrownBy(() -> controller.workQueue(0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, params("cursor", "raw-cursor-value")))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class);

        verify(metrics).recordFraudCaseWorkQueueRequest("invalid_cursor");
        verify(metrics).recordFraudCaseWorkQueueQuery("invalid_cursor", "createdAt");
        verify(metrics, never()).recordFraudCaseWorkQueueQuery("invalid_cursor", "raw-cursor-value");
        verify(metrics, never()).recordFraudCaseWorkQueueQuery("invalid_cursor", "queryHash");
    }

    @Test
    void shouldRecordInvalidCursorMetricsForCursorPageConflict() {
        assertThatThrownBy(() -> controller.workQueue(9, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, params("cursor", "raw-cursor-value", "page", "9")))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_CURSOR_PAGE_COMBINATION");

        verify(metrics).recordFraudCaseWorkQueueRequest("invalid_cursor");
        verify(metrics).recordFraudCaseWorkQueueQuery("invalid_cursor", "createdAt");
        verify(service, never()).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class), any(String.class), any(Sort.Order.class));
    }

    @Test
    void shouldAuditInvalidCursorAsRejectedSensitiveReadAttempt() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fraud-cases/work-queue");
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class), any(String.class), any(Sort.Order.class)))
                .thenThrow(new FraudCaseWorkQueueQueryException("INVALID_CURSOR", "Invalid fraud case work queue cursor."));

        assertThatThrownBy(() -> controller.workQueue(0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, request, params("cursor", "raw-cursor-value")))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class);

        verify(auditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq(null),
                eq(ReadAccessAuditOutcome.REJECTED),
                eq(request)
        );
    }

    private LinkedMultiValueMap<String, String> params(String... values) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            params.add(values[index], values[index + 1]);
        }
        return params;
    }
}
