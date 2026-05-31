package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;

import java.time.Duration;
import java.util.Set;

final class FraudScoringOrchestratorMetricLabels {
    private static final Set<FraudEngineStatus> ALLOWED_STATUSES = Set.of(
            FraudEngineStatus.AVAILABLE,
            FraudEngineStatus.UNAVAILABLE,
            FraudEngineStatus.TIMEOUT,
            FraudEngineStatus.DEGRADED
    );

    private FraudScoringOrchestratorMetricLabels() {
    }

    static void validateEngine(String engineId, FraudEngineType engineType) {
        if (FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID.equals(engineId)) {
            requireType(engineType, FraudEngineType.RULES);
            return;
        }
        if (FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID.equals(engineId)) {
            requireType(engineType, FraudEngineType.ML_MODEL);
            return;
        }
        throw new IllegalArgumentException("METRICS_UNKNOWN_ENGINE_ID");
    }

    static void validateStatus(FraudEngineStatus status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("METRICS_UNKNOWN_ENGINE_STATUS");
        }
    }

    static void validateLatency(Duration latency) {
        if (latency == null || latency.isNegative() || latency.compareTo(FraudEngineExecutionPolicy.MAX_DEADLINE) > 0) {
            throw new IllegalArgumentException("METRICS_INVALID_LATENCY");
        }
    }

    private static void requireType(FraudEngineType actual, FraudEngineType expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("METRICS_ENGINE_TYPE_MISMATCH");
        }
    }
}
