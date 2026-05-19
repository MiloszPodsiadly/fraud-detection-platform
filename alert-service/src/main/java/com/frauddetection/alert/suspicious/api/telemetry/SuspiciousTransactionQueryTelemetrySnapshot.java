package com.frauddetection.alert.suspicious.api.telemetry;

import io.micrometer.core.instrument.Tags;

import java.time.Duration;

public record SuspiciousTransactionQueryTelemetrySnapshot(
        String endpoint,
        String outcome,
        String queryShape,
        String filterCountBucket,
        String resultSizeBucket,
        String hasNext,
        String cursorUsed,
        String durationBucket,
        Duration duration
) {

    public SuspiciousTransactionQueryTelemetrySnapshot {
        endpoint = safe(endpoint, "search");
        outcome = safe(outcome, "error");
        queryShape = safe(queryShape, "unknown");
        filterCountBucket = safe(filterCountBucket, "0");
        resultSizeBucket = safe(resultSizeBucket, "unknown");
        hasNext = safe(hasNext, "unknown");
        cursorUsed = safe(cursorUsed, "unknown");
        durationBucket = safe(durationBucket, "500ms_plus");
        duration = duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }

    public Tags metricTags() {
        return Tags.of(
                "endpoint", endpoint,
                "outcome", outcome,
                "queryShape", queryShape,
                "filterCountBucket", filterCountBucket,
                "resultSizeBucket", resultSizeBucket,
                "hasNext", hasNext,
                "cursorUsed", cursorUsed
        );
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
