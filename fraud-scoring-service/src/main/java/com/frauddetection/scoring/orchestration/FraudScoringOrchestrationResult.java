package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record FraudScoringOrchestrationResult(
        List<FraudEngineResult> engineResults,
        List<String> executionWarnings,
        Instant generatedAt
) {
    private static final Pattern BOUNDED_WARNING = Pattern.compile("[A-Z0-9_]{1,64}");

    public FraudScoringOrchestrationResult {
        Objects.requireNonNull(engineResults, "engineResults is required");
        Objects.requireNonNull(generatedAt, "generatedAt is required");
        for (FraudEngineResult engineResult : engineResults) {
            Objects.requireNonNull(engineResult, "engineResults must not contain null entries");
        }
        engineResults = List.copyOf(engineResults);
        executionWarnings = copyWarnings(executionWarnings);
    }

    private static List<String> copyWarnings(List<String> source) {
        if (source == null) {
            return List.of();
        }
        for (String warning : source) {
            if (warning == null || !BOUNDED_WARNING.matcher(warning).matches()) {
                throw new IllegalArgumentException("executionWarnings must contain bounded warning codes only");
            }
        }
        return List.copyOf(source);
    }
}
