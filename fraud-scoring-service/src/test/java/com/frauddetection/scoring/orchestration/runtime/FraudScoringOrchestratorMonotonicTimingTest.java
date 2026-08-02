package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.COMPLETED;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ExecutionMode.TIMED_OUT;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.engine;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.executionPolicy;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.mlDescriptor;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.registry;
import static com.frauddetection.scoring.orchestration.runtime.RuntimeOrchestratorTestSupport.ruleDescriptor;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorMonotonicTimingTest {

    @Test
    void positiveDurationUsesMonotonicTicker() {
        FraudScoringOrchestrationResult result = evaluate(ticker(0, 7_000_000, 7_000_000, 26_000_000));

        assertThat(result.engineResults()).extracting(FraudEngineResult::latencyMs)
                .containsExactly(7L, 19L);
    }

    @Test
    void zeroDurationIsAllowed() {
        FraudScoringOrchestrationResult result = evaluate(ticker(0, 0, 0, 0));

        assertThat(result.engineResults()).extracting(FraudEngineResult::latencyMs)
                .containsExactly(0L, 0L);
    }

    @Test
    void upperBoundaryDurationIsAllowed() {
        FraudScoringOrchestrationResult result = evaluate(ticker(0, 30_000_000, 30_000_000, 70_000_000));

        assertThat(result.engineResults()).extracting(FraudEngineResult::latencyMs)
                .containsExactly(30L, 40L);
    }

    @Test
    void overLimitDurationIsClampedToEngineDeadline() {
        FraudScoringOrchestrationResult result = evaluate(ticker(0, 100_000_000, 100_000_000, 200_000_000));

        assertThat(result.engineResults()).extracting(FraudEngineResult::latencyMs)
                .containsExactly(30L, 40L);
    }

    @Test
    void timeoutPathUsesDeadlineWithoutWallClockSubtraction() {
        FraudScoringOrchestrationResult result = evaluate(
                ticker(0, 10_000_000),
                List.of(TIMED_OUT, COMPLETED)
        );

        assertThat(result.engineResults().getFirst().status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.engineResults().getFirst().latencyMs()).isEqualTo(30L);
    }

    @Test
    void wallClockMovingBackwardsDoesNotAffectMeasuredLatency() {
        RuntimeOrchestratorTestSupport.MutableClock clock = new RuntimeOrchestratorTestSupport.MutableClock();
        SequenceTicker ticker = ticker(0, 12_000_000, 12_000_000, 20_000_000);
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(
                registry(
                        engine(ruleDescriptor(), ignored -> {
                            clock.advance(Duration.ofSeconds(-5));
                            return RuntimeOrchestratorTestSupport.availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH);
                        }),
                        engine(mlDescriptor(), ignored ->
                                RuntimeOrchestratorTestSupport.availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
                ),
                executionPolicy(),
                new BoundedFraudEngineExecutor(new RuntimeOrchestratorTestSupport.ScriptedExecutorService(
                        List.of(COMPLETED, COMPLETED)
                )),
                new NoOpFraudScoringOrchestratorMetrics(),
                clock,
                ticker
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults()).extracting(FraudEngineResult::latencyMs)
                .containsExactly(12L, 8L);
        assertThat(result.engineResults().getFirst().generatedAt())
                .isEqualTo(Instant.parse("2026-05-30T09:59:55Z"));
    }

    @Test
    void generatedTimestampStillFollowsInjectedClock() {
        RuntimeOrchestratorTestSupport.MutableClock clock = new RuntimeOrchestratorTestSupport.MutableClock();
        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(
                registry(
                        engine(ruleDescriptor(), ignored -> {
                            clock.advance(Duration.ofMillis(3));
                            return RuntimeOrchestratorTestSupport.availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH);
                        }),
                        engine(mlDescriptor(), ignored -> {
                            clock.advance(Duration.ofMillis(4));
                            return RuntimeOrchestratorTestSupport.availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM);
                        })
                ),
                executionPolicy(),
                new BoundedFraudEngineExecutor(new RuntimeOrchestratorTestSupport.ScriptedExecutorService(
                        List.of(COMPLETED, COMPLETED)
                )),
                new NoOpFraudScoringOrchestratorMetrics(),
                clock,
                ticker(0, 10_000_000, 10_000_000, 20_000_000)
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults()).extracting(FraudEngineResult::generatedAt)
                .containsExactly(
                        Instant.parse("2026-05-30T10:00:00.003Z"),
                        Instant.parse("2026-05-30T10:00:00.007Z")
                );
        assertThat(result.generatedAt()).isEqualTo(Instant.parse("2026-05-30T10:00:00.007Z"));
    }

    private FraudScoringOrchestrationResult evaluate(SequenceTicker ticker) {
        return evaluate(ticker, List.of(COMPLETED, COMPLETED));
    }

    private FraudScoringOrchestrationResult evaluate(SequenceTicker ticker, List<RuntimeOrchestratorTestSupport.ExecutionMode> modes) {
        try (FraudScoringOrchestrator orchestrator = new FraudScoringOrchestrator(
                registry(),
                executionPolicy(),
                new BoundedFraudEngineExecutor(new RuntimeOrchestratorTestSupport.ScriptedExecutorService(modes)),
                new NoOpFraudScoringOrchestratorMetrics(),
                new RuntimeOrchestratorTestSupport.MutableClock(),
                ticker
        )) {
            return orchestrator.evaluate(context());
        }
    }

    private SequenceTicker ticker(long... nanos) {
        return new SequenceTicker(nanos);
    }

    private static final class SequenceTicker implements MonotonicTicker {
        private final ArrayDeque<Long> reads = new ArrayDeque<>();

        private SequenceTicker(long... nanos) {
            for (long value : nanos) {
                reads.add(value);
            }
        }

        @Override
        public long readNanos() {
            return reads.isEmpty() ? 0L : reads.removeFirst();
        }
    }
}
