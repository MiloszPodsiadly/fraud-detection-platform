package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

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

        FraudCaseWorkQueueSummaryController controller = new FraudCaseWorkQueueSummaryController(
                queryService,
                metrics,
                Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC)
        );

        assertThat(controller.summary()).isEqualTo(response);
        verify(metrics).recordFraudCaseWorkQueueSummaryOutcome(eq("success"), any());
    }

    @Test
    void shouldRecordFailureMetricWhenSummaryReadFails() {
        FraudCaseQueryService queryService = mock(FraudCaseQueryService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        when(queryService.globalFraudCaseSummary()).thenThrow(new IllegalStateException("mongo unavailable"));

        FraudCaseWorkQueueSummaryController controller = new FraudCaseWorkQueueSummaryController(
                queryService,
                metrics,
                Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(controller::summary).isInstanceOf(IllegalStateException.class);
        verify(metrics).recordFraudCaseWorkQueueSummaryOutcome(eq("failure"), any());
    }

    @Test
    void shouldMeasureLatencyWithInjectedClockInsteadOfWallClock() {
        FraudCaseQueryService queryService = mock(FraudCaseQueryService.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-05-12T10:00:00Z"));
        FraudCaseWorkQueueSummaryResponse response = new FraudCaseWorkQueueSummaryResponse(
                46L,
                Instant.parse("2026-05-12T10:00:00Z")
        );
        when(queryService.globalFraudCaseSummary()).thenAnswer(invocation -> {
            clock.advance(Duration.ofMillis(37));
            return response;
        });

        FraudCaseWorkQueueSummaryController controller = new FraudCaseWorkQueueSummaryController(queryService, metrics, clock);

        controller.summary();

        ArgumentCaptor<Duration> latency = ArgumentCaptor.forClass(Duration.class);
        verify(metrics).recordFraudCaseWorkQueueSummaryOutcome(eq("success"), latency.capture());
        assertThat(latency.getValue()).isEqualTo(Duration.ofMillis(37));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
