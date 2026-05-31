package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineContributionDirection;

import java.util.Objects;

public record BoundedFraudEngineContributionSummary(
        String feature,
        Double weight,
        FraudEngineContributionDirection direction
) {
    public BoundedFraudEngineContributionSummary {
        if (feature == null || feature.isBlank() || !FraudEngineAggregationSafety.isSafe(feature)) {
            throw new IllegalArgumentException("AGGREGATION_CONTRIBUTION_UNSAFE_FEATURE");
        }
        if (feature.length() > 128) {
            throw new IllegalArgumentException("AGGREGATION_CONTRIBUTION_FEATURE_TOO_LONG");
        }
        if (weight != null && !Double.isFinite(weight)) {
            throw new IllegalArgumentException("AGGREGATION_CONTRIBUTION_INVALID_WEIGHT");
        }
        Objects.requireNonNull(direction, "direction is required");
    }
}
