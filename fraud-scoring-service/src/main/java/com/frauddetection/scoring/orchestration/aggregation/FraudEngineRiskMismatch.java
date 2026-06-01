package com.frauddetection.scoring.orchestration.aggregation;

import java.util.Objects;

public record FraudEngineRiskMismatch(
        FraudEngineRiskMismatchStatus status
) {
    public FraudEngineRiskMismatch {
        Objects.requireNonNull(status, "status is required");
    }
}
