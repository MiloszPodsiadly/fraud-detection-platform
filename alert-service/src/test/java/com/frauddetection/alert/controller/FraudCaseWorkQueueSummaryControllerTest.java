package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseQueryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudCaseWorkQueueSummaryControllerTest {

    @Test
    void shouldReturnGlobalSummaryFromReadOnlyQueryService() {
        FraudCaseQueryService queryService = mock(FraudCaseQueryService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        FraudCaseWorkQueueSummaryResponse response = new FraudCaseWorkQueueSummaryResponse(
                46L,
                Instant.parse("2026-05-12T10:00:00Z")
        );
        when(queryService.globalFraudCaseSummary()).thenReturn(response);

        FraudCaseWorkQueueSummaryController controller = new FraudCaseWorkQueueSummaryController(queryService, metrics);

        assertThat(controller.summary()).isEqualTo(response);
        verify(metrics).recordFraudCaseWorkQueueSummaryOutcome(eq("success"), any());
    }

    @Test
    void shouldRecordFailureMetricWhenSummaryReadFails() {
        FraudCaseQueryService queryService = mock(FraudCaseQueryService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        when(queryService.globalFraudCaseSummary()).thenThrow(new IllegalStateException("mongo unavailable"));

        FraudCaseWorkQueueSummaryController controller = new FraudCaseWorkQueueSummaryController(queryService, metrics);

        assertThatThrownBy(controller::summary).isInstanceOf(IllegalStateException.class);
        verify(metrics).recordFraudCaseWorkQueueSummaryOutcome(eq("failure"), any());
    }
}
