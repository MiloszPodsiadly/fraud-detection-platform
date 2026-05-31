package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.COMPLETED;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.REJECTED;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.TIMED_OUT;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.orchestrator;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorNonCooperativeTimeoutTest {

    @Test
    void nonCooperativeTimedOutEngineDoesNotProduceSuccess() {
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(COMPLETED, TIMED_OUT),
                new NoOpFraudScoringOrchestratorMetrics(),
                new RuntimeOrchestratorTestSupport.MutableClock()
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.engineResults().get(1).statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_TIMEOUT");
        assertThat(result.engineResults().get(1).score()).isNull();
        assertThat(result.engineResults().get(1).riskLevel()).isNull();
        assertThat(result.engineResults().get(1).confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.engineResults().get(1).status()).isNotEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
    }

    @Test
    void repeatedNonCooperativeTimeoutsRemainBounded() {
        FraudScoringOrchestrationResult requiredTimeout;
        FraudScoringOrchestrationResult optionalTimeout;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(TIMED_OUT, COMPLETED, COMPLETED, TIMED_OUT),
                new NoOpFraudScoringOrchestratorMetrics(),
                new RuntimeOrchestratorTestSupport.MutableClock()
        )) {
            requiredTimeout = orchestrator.evaluate(context());
            optionalTimeout = orchestrator.evaluate(context());
        }

        assertThat(requiredTimeout.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
        assertThat(optionalTimeout.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertThat(requiredTimeout.toString() + optionalTimeout)
                .contains("ORCHESTRATOR_ENGINE_TIMEOUT")
                .doesNotContain("raw-token", "payload", "endpoint", "accountId");
    }

    @Test
    void saturatedExecutorProducesBoundedRejectedResult() {
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(COMPLETED, REJECTED),
                new NoOpFraudScoringOrchestratorMetrics(),
                new RuntimeOrchestratorTestSupport.MutableClock()
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults().getFirst().status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().get(1).statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_REJECTED");
        assertThat(result.toString()).doesNotContain("queue-full", "token", "endpoint", "accountId");
    }
}
