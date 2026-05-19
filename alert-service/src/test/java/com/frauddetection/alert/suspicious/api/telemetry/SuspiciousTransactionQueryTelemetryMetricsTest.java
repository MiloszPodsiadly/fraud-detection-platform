package com.frauddetection.alert.suspicious.api.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionQueryTelemetryMetricsTest {

    @Test
    void recordsQueryTimerWithLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousTransactionQueryTelemetryRecorder recorder =
                new SuspiciousTransactionQueryTelemetryRecorder(registry, Duration.ofMillis(500));

        recorder.record(new SuspiciousTransactionQueryTelemetrySnapshot(
                "search",
                "success",
                "multi_filter",
                "3_plus",
                "11_50",
                "true",
                "false",
                "100_250ms",
                Duration.ofMillis(120)
        ));

        assertThat(registry.get(SuspiciousTransactionQueryTelemetryRecorder.QUERY_METRIC)
                .tag("endpoint", "search")
                .tag("outcome", "success")
                .tag("queryShape", "multi_filter")
                .tag("filterCountBucket", "3_plus")
                .tag("resultSizeBucket", "11_50")
                .tag("hasNext", "true")
                .tag("cursorUsed", "false")
                .timer()
                .count()).isEqualTo(1L);

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals(SuspiciousTransactionQueryTelemetryRecorder.QUERY_METRIC))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .containsOnly(
                                "endpoint",
                                "outcome",
                                "queryShape",
                                "filterCountBucket",
                                "resultSizeBucket",
                                "hasNext",
                                "cursorUsed"
                        )
                        .doesNotContain("durationBucket", "customerId", "linkedAlertId", "cursor", "rawFilters"));
    }

    @Test
    void invalidSlowThresholdFallsBackToSafeDefaultAndTinyThresholdIsClamped() {
        assertThat(SuspiciousTransactionQueryTelemetryRecorder.normalizedThreshold(Duration.ZERO))
                .isEqualTo(SuspiciousTransactionQueryTelemetryRecorder.DEFAULT_SLOW_QUERY_THRESHOLD);
        assertThat(SuspiciousTransactionQueryTelemetryRecorder.normalizedThreshold(Duration.ofMillis(1)))
                .isEqualTo(SuspiciousTransactionQueryTelemetryRecorder.MIN_SLOW_QUERY_THRESHOLD);
    }
}
