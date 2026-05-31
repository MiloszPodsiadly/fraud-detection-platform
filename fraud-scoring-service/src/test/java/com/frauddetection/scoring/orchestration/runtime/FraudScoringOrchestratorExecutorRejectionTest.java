package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.COMPLETED;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.REJECTED;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.executionPolicy;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.orchestrator;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.registry;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorExecutorRejectionTest {

    @Test
    void optionalMlExecutorRejectionProducesBoundedDegradedResult() {
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(COMPLETED, REJECTED),
                new NoOpFraudScoringOrchestratorMetrics(),
                new RuntimeOrchestratorTestSupport.MutableClock()
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.engineResults().getFirst().status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().get(1).score()).isNull();
        assertThat(result.engineResults().get(1).riskLevel()).isNull();
        assertThat(result.engineResults().get(1).confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.engineResults().get(1).statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_REJECTED");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void requiredRulesExecutorRejectionProducesRequiredEngineFailed() {
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(REJECTED, COMPLETED),
                new NoOpFraudScoringOrchestratorMetrics(),
                new RuntimeOrchestratorTestSupport.MutableClock()
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.engineResults().getFirst().status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().getFirst().statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_REJECTED");
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
    }

    @Test
    void executorRejectionDoesNotExposeRawDetails() {
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(COMPLETED, REJECTED),
                new NoOpFraudScoringOrchestratorMetrics(),
                new RuntimeOrchestratorTestSupport.MutableClock()
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.toString())
                .contains("ORCHESTRATOR_ENGINE_REJECTED")
                .doesNotContain("queue-full", "token", "endpoint", "accountId");
        assertThat(result.engineResults().get(1).status()).isNotEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.engineResults().get(1).status()).isNotEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
    }

    @Test
    void executorRejectionLatencyIsMeasuredDeterministically() {
        RuntimeOrchestratorTestSupport.MutableClock clock = new RuntimeOrchestratorTestSupport.MutableClock();
        RuntimeOrchestratorTestSupport.ScriptedExecutorService executorService =
                new RuntimeOrchestratorTestSupport.ScriptedExecutorService(
                        List.of(COMPLETED, REJECTED),
                        () -> clock.advance(Duration.ofMillis(9))
                );
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(
                registry(),
                executionPolicy(),
                new BoundedFraudEngineExecutor(executorService),
                new NoOpFraudScoringOrchestratorMetrics(),
                clock
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults().get(1).statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_REJECTED");
        assertThat(result.engineResults().get(1).latencyMs()).isEqualTo(9L);
    }
}
