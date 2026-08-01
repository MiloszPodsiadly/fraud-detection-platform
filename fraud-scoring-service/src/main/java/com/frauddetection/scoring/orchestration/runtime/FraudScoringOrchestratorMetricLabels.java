package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;

import java.time.Duration;
import java.util.Set;

final class FraudScoringOrchestratorMetricLabels {
    private static final Set<FraudEngineStatus> ALLOWED_STATUSES = Set.of(
            FraudEngineStatus.AVAILABLE,
            FraudEngineStatus.UNAVAILABLE,
            FraudEngineStatus.TIMEOUT,
            FraudEngineStatus.DEGRADED
    );
    private static final Set<String> ALLOWED_FAILURE_CATEGORIES = Set.of(
            "missing",
            "invalid_type",
            "invalid_value",
            "inconsistent",
            "timeout",
            "exception",
            "null_result",
            "rejected",
            "degraded"
    );

    private FraudScoringOrchestratorMetricLabels() {
    }

    static void validateEngine(String engineId, FraudEngineType engineType) {
        if (!FraudEngineIdentityContract.isKnownEngineId(engineId)) {
            throw new IllegalArgumentException("METRICS_UNKNOWN_ENGINE_ID");
        }
        if (!FraudEngineIdentityContract.hasExpectedType(engineId, engineType)) {
            throw new IllegalArgumentException("METRICS_ENGINE_TYPE_MISMATCH");
        }
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

    static void validateFailureCategory(String failureCategory) {
        if (!ALLOWED_FAILURE_CATEGORIES.contains(failureCategory)) {
            throw new IllegalArgumentException("METRICS_UNKNOWN_FAILURE_CATEGORY");
        }
    }
}
