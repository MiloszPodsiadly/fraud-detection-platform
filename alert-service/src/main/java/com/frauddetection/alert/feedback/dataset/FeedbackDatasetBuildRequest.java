package com.frauddetection.alert.feedback.dataset;

import java.time.Instant;

public record FeedbackDatasetBuildRequest(
        Instant fromInclusive,
        Instant toInclusive,
        Integer maxRecords
) {
    static final int DEFAULT_MAX_RECORDS = 500;
    static final int HARD_MAX_RECORDS = 1000;
    static final long MAX_RANGE_DAYS = 31;

    int effectiveMaxRecords() {
        if (maxRecords == null) {
            return DEFAULT_MAX_RECORDS;
        }
        return Math.min(maxRecords, HARD_MAX_RECORDS);
    }
}
