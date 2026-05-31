package com.frauddetection.scoring.orchestration.aggregation;

import java.util.Objects;
import java.util.Set;

public record FraudEngineAggregationWarning(
        String engineId,
        FraudEngineAggregationWarningCode code
) {
    private static final Set<String> ALLOWED_ENGINE_IDS = Set.of("rules.primary", "ml.python.primary");

    public FraudEngineAggregationWarning {
        if (engineId != null && !ALLOWED_ENGINE_IDS.contains(engineId)) {
            throw new IllegalArgumentException("AGGREGATION_WARNING_UNKNOWN_ENGINE_ID");
        }
        Objects.requireNonNull(code, "code is required");
    }
}
