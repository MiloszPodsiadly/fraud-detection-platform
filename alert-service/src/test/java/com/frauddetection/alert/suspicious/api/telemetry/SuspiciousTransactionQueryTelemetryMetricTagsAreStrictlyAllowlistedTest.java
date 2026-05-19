package com.frauddetection.alert.suspicious.api.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionQueryTelemetryMetricTagsAreStrictlyAllowlistedTest {

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "endpoint",
            "outcome",
            "queryShape",
            "filterCountBucket",
            "resultSizeBucket",
            "hasNext",
            "cursorUsed"
    );

    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "customerId",
            "accountId",
            "transactionId",
            "suspiciousTransactionId",
            "sourceEventId",
            "correlationId",
            "linkedAlertId",
            "cursor",
            "cursorToken",
            "decodedCursor",
            "reasonCodes",
            "modelName",
            "modelVersion",
            "rawFilters",
            "rawQuery",
            "exceptionMessage",
            "durationBucket",
            "durationMillis",
            "status+risk+customer",
            "filterCombinationRaw"
    );

    @Test
    void timerTagKeysAreStrictlyAllowlisted() {
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
                "true",
                "100_250ms",
                Duration.ofMillis(100)
        ));

        registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals(SuspiciousTransactionQueryTelemetryRecorder.QUERY_METRIC))
                .forEach(id -> assertThat(id.getTags().stream().map(tag -> tag.getKey()).toList())
                        .containsExactlyInAnyOrderElementsOf(ALLOWED_TAG_KEYS)
                        .doesNotContainAnyElementsOf(FORBIDDEN_TAG_KEYS));
    }

    @Test
    void timerTagValuesAreNormalizedToBoundedSets() {
        SuspiciousTransactionQueryTelemetrySnapshot snapshot = new SuspiciousTransactionQueryTelemetrySnapshot(
                "customer-secret-123",
                "raw-exception-message",
                "rawFilters={customerId=customer-secret-123}",
                "4",
                "101",
                "maybe",
                "cursor-token-secret",
                "durationMillis=123",
                Duration.ofMillis(123)
        );

        assertThat(snapshot.metricTags().stream().map(tag -> tag.getValue()).toList())
                .containsExactlyInAnyOrder(
                        "search",
                        "error",
                        "unknown",
                        "0",
                        "unknown",
                        "unknown",
                        "unknown"
                )
                .doesNotContain(
                        "customer-secret-123",
                        "raw-exception-message",
                        "rawFilters={customerId=customer-secret-123}",
                        "cursor-token-secret",
                        "durationMillis=123"
                );
    }
}
