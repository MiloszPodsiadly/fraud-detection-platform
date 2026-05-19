package com.frauddetection.alert.suspicious.api.telemetry;

import io.micrometer.core.instrument.Tags;

import java.time.Duration;
import java.util.Set;

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

    private static final Set<String> ENDPOINTS = Set.of("search", "read");
    private static final Set<String> OUTCOMES = Set.of("success", "not_found", "validation_error", "forbidden", "error");
    private static final Set<String> QUERY_SHAPES = Set.of(
            "id_lookup",
            "unfiltered",
            "status",
            "risk",
            "customer",
            "linked_alert",
            "date_range",
            "multi_filter",
            "unknown"
    );
    private static final Set<String> FILTER_COUNT_BUCKETS = Set.of("0", "1", "2", "3_plus");
    private static final Set<String> RESULT_SIZE_BUCKETS = Set.of("0", "1_10", "11_50", "51_100", "unknown");
    private static final Set<String> TRI_STATE = Set.of("true", "false", "unknown");
    private static final Set<String> DURATION_BUCKETS = Set.of(
            "lt_50ms",
            "50_100ms",
            "100_250ms",
            "250_500ms",
            "500ms_plus"
    );

    public SuspiciousTransactionQueryTelemetrySnapshot {
        endpoint = allow(endpoint, ENDPOINTS, "search");
        outcome = allow(outcome, OUTCOMES, "error");
        queryShape = allow(queryShape, QUERY_SHAPES, "unknown");
        filterCountBucket = allow(filterCountBucket, FILTER_COUNT_BUCKETS, "0");
        resultSizeBucket = allow(resultSizeBucket, RESULT_SIZE_BUCKETS, "unknown");
        hasNext = allow(hasNext, TRI_STATE, "unknown");
        cursorUsed = allow(cursorUsed, TRI_STATE, "unknown");
        durationBucket = allow(durationBucket, DURATION_BUCKETS, "500ms_plus");
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

    private static String allow(String value, Set<String> allowed, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return allowed.contains(value) ? value : fallback;
    }
}
