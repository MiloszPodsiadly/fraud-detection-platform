package com.frauddetection.scoring.orchestration.aggregation;

import java.util.Objects;

record FraudEngineAggregationWarningSummary(
        FraudEngineAggregationWarningCode code,
        int count
) {
    FraudEngineAggregationWarningSummary {
        Objects.requireNonNull(code, "code is required");
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
    }
}
