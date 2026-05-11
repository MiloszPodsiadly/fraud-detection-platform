package com.frauddetection.alert.controller;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueFailureMetricsTest {

    @Test
    void shouldRecordFailureMetricsWithoutFalseSuccessWhenServiceFails() {
        FraudCaseManagementService service = mock(FraudCaseManagementService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseController controller = new FraudCaseController(
                service,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                metrics,
                mock(SensitiveReadAuditService.class)
        );
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("mongo unavailable"));

        assertThatThrownBy(() -> controller.workQueue(
                0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, new LinkedMultiValueMap<>()
        )).isInstanceOf(IllegalStateException.class);

        verify(metrics).recordFraudCaseWorkQueueRequest("failure");
        verify(metrics).recordFraudCaseWorkQueueQuery("failure", "createdAt");
        verify(metrics, never()).recordFraudCaseWorkQueueRequest("success");
    }
}
