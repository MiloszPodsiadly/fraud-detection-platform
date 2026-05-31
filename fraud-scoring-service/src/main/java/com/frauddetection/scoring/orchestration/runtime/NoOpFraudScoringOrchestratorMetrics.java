package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;

import java.time.Duration;
import java.util.Objects;

public final class NoOpFraudScoringOrchestratorMetrics implements FraudScoringOrchestratorMetrics {

    @Override
    public void recordOrchestration(FraudScoringOrchestrationStatus status) {
        Objects.requireNonNull(status, "status is required");
    }

    @Override
    public void recordEngineResult(String engineId, FraudEngineType engineType, FraudEngineStatus status, boolean required) {
        FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
        FraudScoringOrchestratorMetricLabels.validateStatus(status);
    }

    @Override
    public void recordEngineLatency(
            String engineId,
            FraudEngineType engineType,
            FraudEngineStatus status,
            boolean required,
            Duration latency
    ) {
        FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
        FraudScoringOrchestratorMetricLabels.validateStatus(status);
        FraudScoringOrchestratorMetricLabels.validateLatency(latency);
    }

    @Override
    public void recordTimeout(String engineId, FraudEngineType engineType, boolean required) {
        FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
    }

    @Override
    public void recordRequiredEngineFailed(String engineId) {
        if (!FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID.equals(engineId)) {
            throw new IllegalArgumentException("METRICS_UNKNOWN_REQUIRED_ENGINE_ID");
        }
    }
}
