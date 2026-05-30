package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FraudScoringOrchestrationResult(
        FraudScoringOrchestrationStatus status,
        List<FraudEngineResult> engineResults,
        List<FraudScoringExecutionWarning> executionWarnings,
        Instant generatedAt
) {
    public FraudScoringOrchestrationResult {
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(engineResults, "engineResults is required");
        Objects.requireNonNull(generatedAt, "generatedAt is required");
        for (FraudEngineResult engineResult : engineResults) {
            Objects.requireNonNull(engineResult, "engineResults must not contain null entries");
        }
        engineResults = List.copyOf(engineResults);
        executionWarnings = copyWarnings(executionWarnings);
    }

    private static List<FraudScoringExecutionWarning> copyWarnings(List<FraudScoringExecutionWarning> source) {
        if (source == null) {
            return List.of();
        }
        for (FraudScoringExecutionWarning warning : source) {
            Objects.requireNonNull(warning, "executionWarnings must not contain null entries");
        }
        return List.copyOf(source);
    }
}
