package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

public final class MicrometerFraudScoringOrchestratorMetrics implements FraudScoringOrchestratorMetrics {
    static final String ORCHESTRATION_RESULT_COUNTER = "fraud_scoring_orchestrator_result_total";
    static final String ENGINE_RESULT_COUNTER = "fraud_scoring_engine_result_total";
    static final String ENGINE_FAILURE_COUNTER = "fraud_scoring_engine_failure_total";
    static final String ENGINE_TIMEOUT_COUNTER = "fraud_scoring_engine_timeout_total";
    static final String REQUIRED_ENGINE_FAILED_COUNTER = "fraud_scoring_required_engine_failed_total";
    static final String ENGINE_LATENCY_TIMER = "fraud_scoring_engine_latency_seconds";

    private final MeterRegistry meterRegistry;

    public MicrometerFraudScoringOrchestratorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
    }

    @Override
    public void recordOrchestration(FraudScoringOrchestrationStatus status) {
        Objects.requireNonNull(status, "status is required");
        Counter.builder(ORCHESTRATION_RESULT_COUNTER)
                .tag("status", status.name())
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordEngineResult(String engineId, FraudEngineType engineType, FraudEngineStatus status, boolean required) {
        FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
        FraudScoringOrchestratorMetricLabels.validateStatus(status);
        Counter.builder(ENGINE_RESULT_COUNTER)
                .tags(engineTags(engineId, engineType))
                .tag("status", status.name())
                .register(meterRegistry)
                .increment();
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
        Timer.builder(ENGINE_LATENCY_TIMER)
                .tags(engineTags(engineId, engineType))
                .tag("status", status.name())
                .register(meterRegistry)
                .record(latency);
    }

    @Override
    public void recordEngineFailure(String engineId, FraudEngineType engineType, String failureCategory) {
        FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
        FraudScoringOrchestratorMetricLabels.validateFailureCategory(failureCategory);
        Counter.builder(ENGINE_FAILURE_COUNTER)
                .tags(engineTags(engineId, engineType))
                .tag("failure_category", failureCategory)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordTimeout(String engineId, FraudEngineType engineType, boolean required) {
        FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
        Counter.builder(ENGINE_TIMEOUT_COUNTER)
                .tags(engineTags(engineId, engineType))
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordRequiredEngineFailed(String engineId) {
        if (!com.frauddetection.common.events.engine.FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID.equals(engineId)) {
            throw new IllegalArgumentException("METRICS_UNKNOWN_REQUIRED_ENGINE_ID");
        }
        Counter.builder(REQUIRED_ENGINE_FAILED_COUNTER)
                .tag("engine_id", engineId)
                .register(meterRegistry)
                .increment();
    }

    private String[] engineTags(String engineId, FraudEngineType engineType) {
        return new String[]{"engine_id", engineId, "engine_type", engineType.name()};
    }
}
