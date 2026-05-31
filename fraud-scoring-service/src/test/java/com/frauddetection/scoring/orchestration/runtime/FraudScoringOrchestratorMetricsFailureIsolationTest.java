package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.COMPLETED;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.TIMED_OUT;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.orchestrator;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorMetricsFailureIsolationTest {

    @Test
    void recordEngineResultFailureDoesNotBreakEvaluate() {
        FraudScoringOrchestrationResult result = evaluate(List.of(COMPLETED, COMPLETED), FailurePoint.ENGINE_RESULT);

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.COMPLETE);
    }

    @Test
    void recordEngineLatencyFailureDoesNotBreakEvaluate() {
        FraudScoringOrchestrationResult result = evaluate(List.of(COMPLETED, COMPLETED), FailurePoint.ENGINE_LATENCY);

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.COMPLETE);
    }

    @Test
    void recordTimeoutFailureDoesNotBreakEvaluate() {
        FraudScoringOrchestrationResult result = evaluate(List.of(COMPLETED, TIMED_OUT), FailurePoint.TIMEOUT);

        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
    }

    @Test
    void recordRequiredEngineFailedFailureDoesNotBreakEvaluate() {
        FraudScoringOrchestrationResult result = evaluate(List.of(TIMED_OUT, COMPLETED), FailurePoint.REQUIRED_FAILED);

        assertThat(result.engineResults().getFirst().status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
    }

    @Test
    void recordOrchestrationFailureDoesNotBreakEvaluate() {
        FraudScoringOrchestrationResult result = evaluate(List.of(COMPLETED, COMPLETED), FailurePoint.ORCHESTRATION);

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.COMPLETE);
    }

    @Test
    void metricsFailureDoesNotExposeRawExceptionText() {
        FraudScoringOrchestrationResult result = evaluate(List.of(COMPLETED, COMPLETED), FailurePoint.ENGINE_RESULT);

        assertThat(result.toString()).doesNotContain("raw-token", "endpoint", "payload", "accountId");
        assertThat(Arrays.stream(FraudScoringOrchestrationResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("finalDecision", "approve", "decline", "block", "recommendedAction");
    }

    private FraudScoringOrchestrationResult evaluate(List<RuntimeOrchestratorTestSupport.ExecutionMode> modes, FailurePoint point) {
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                modes,
                new ThrowingMetrics(point),
                new RuntimeOrchestratorTestSupport.MutableClock()
        )) {
            return orchestrator.evaluate(context());
        }
    }

    private enum FailurePoint {
        ENGINE_RESULT,
        ENGINE_LATENCY,
        TIMEOUT,
        REQUIRED_FAILED,
        ORCHESTRATION
    }

    private static final class ThrowingMetrics implements FraudScoringOrchestratorMetrics {
        private final FailurePoint point;

        private ThrowingMetrics(FailurePoint point) {
            this.point = point;
        }

        @Override
        public void recordOrchestration(FraudScoringOrchestrationStatus status) {
            failAt(FailurePoint.ORCHESTRATION);
        }

        @Override
        public void recordEngineResult(String engineId, FraudEngineType engineType, FraudEngineStatus status, boolean required) {
            failAt(FailurePoint.ENGINE_RESULT);
        }

        @Override
        public void recordEngineLatency(
                String engineId,
                FraudEngineType engineType,
                FraudEngineStatus status,
                boolean required,
                Duration latency
        ) {
            failAt(FailurePoint.ENGINE_LATENCY);
        }

        @Override
        public void recordTimeout(String engineId, FraudEngineType engineType, boolean required) {
            failAt(FailurePoint.TIMEOUT);
        }

        @Override
        public void recordRequiredEngineFailed(String engineId) {
            failAt(FailurePoint.REQUIRED_FAILED);
        }

        private void failAt(FailurePoint candidate) {
            if (point == candidate) {
                throw new IllegalStateException("raw-token endpoint payload accountId=123");
            }
        }
    }
}
