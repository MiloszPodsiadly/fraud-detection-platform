package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;

import java.util.Objects;

public record FraudEngineAggregationWarning(
        String engineId,
        FraudEngineAggregationWarningCode code
) {
    public FraudEngineAggregationWarning {
        if (engineId != null && !FraudEngineIdentityContract.isKnownEngineId(engineId)) {
            throw new IllegalArgumentException("AGGREGATION_WARNING_UNKNOWN_ENGINE_ID");
        }
        Objects.requireNonNull(code, "code is required");
    }
}
