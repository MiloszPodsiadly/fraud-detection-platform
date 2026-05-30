package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.Objects;
import java.util.regex.Pattern;

public record FraudScoringExecutionWarning(
        String engineId,
        FraudScoringExecutionWarningCode code,
        FraudEngineStatus engineStatus,
        boolean required
) {
    private static final Pattern BOUNDED_ENGINE_ID = Pattern.compile("[a-z0-9._-]{1,64}");

    public FraudScoringExecutionWarning {
        Objects.requireNonNull(engineId, "engineId is required");
        Objects.requireNonNull(code, "code is required");
        Objects.requireNonNull(engineStatus, "engineStatus is required");
        if (!BOUNDED_ENGINE_ID.matcher(engineId).matches()) {
            throw new IllegalArgumentException("engineId must be bounded");
        }
    }
}
