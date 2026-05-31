package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;

import java.time.Duration;

public interface FraudScoringOrchestratorMetrics {

    void recordOrchestration(FraudScoringOrchestrationStatus status);

    void recordEngineResult(String engineId, FraudEngineType engineType, FraudEngineStatus status, boolean required);

    void recordEngineLatency(
            String engineId,
            FraudEngineType engineType,
            FraudEngineStatus status,
            boolean required,
            Duration latency
    );

    void recordTimeout(String engineId, FraudEngineType engineType, boolean required);

    void recordRequiredEngineFailed(String engineId);
}
