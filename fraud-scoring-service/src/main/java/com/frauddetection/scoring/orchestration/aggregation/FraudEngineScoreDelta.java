package com.frauddetection.scoring.orchestration.aggregation;

import java.util.Objects;

public record FraudEngineScoreDelta(
        FraudEngineScoreDeltaStatus status,
        Double absoluteDelta
) {
    public FraudEngineScoreDelta {
        Objects.requireNonNull(status, "status is required");
        if (status == FraudEngineScoreDeltaStatus.AVAILABLE) {
            if (absoluteDelta == null || !Double.isFinite(absoluteDelta) || absoluteDelta < 0.0d || absoluteDelta > 1.0d) {
                throw new IllegalArgumentException("AGGREGATION_SCORE_DELTA_INVALID");
            }
        } else if (absoluteDelta != null) {
            throw new IllegalArgumentException("AGGREGATION_SCORE_DELTA_UNAVAILABLE_WITH_VALUE");
        }
    }
}
