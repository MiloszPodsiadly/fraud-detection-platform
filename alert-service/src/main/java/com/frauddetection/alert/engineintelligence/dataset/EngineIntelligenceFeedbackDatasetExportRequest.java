package com.frauddetection.alert.engineintelligence.dataset;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record EngineIntelligenceFeedbackDatasetExportRequest(
        Instant fromInclusive,
        Instant toInclusive,
        int maxRecords
) {
    static final int MIN_RECORDS = 1;
    static final int MAX_RECORDS = 500;
    static final Duration MAX_RANGE = Duration.ofDays(31);

    public EngineIntelligenceFeedbackDatasetExportRequest {
        fromInclusive = Objects.requireNonNull(fromInclusive, "fromInclusive is required");
        toInclusive = Objects.requireNonNull(toInclusive, "toInclusive is required");
        if (fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("fromInclusive must not be after toInclusive");
        }
        if (Duration.between(fromInclusive, toInclusive).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("date range must not exceed 31 days");
        }
        if (maxRecords < MIN_RECORDS || maxRecords > MAX_RECORDS) {
            throw new IllegalArgumentException("maxRecords must be between 1 and 500");
        }
    }
}
