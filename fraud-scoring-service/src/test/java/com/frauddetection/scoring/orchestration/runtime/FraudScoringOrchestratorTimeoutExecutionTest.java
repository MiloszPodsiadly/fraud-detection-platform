package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.orchestration.FraudScoringExecutionWarning;
import com.frauddetection.scoring.orchestration.FraudScoringExecutionWarningCode;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorTimeoutExecutionTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");
    private static final Duration RULES_DEADLINE = Duration.ofMillis(30);
    private static final Duration ML_DEADLINE = Duration.ofMillis(40);

    @Test
    void optionalMlTimeoutDoesNotEraseRuleResult() {
        MutableClock clock = new MutableClock(RECEIVED_AT);
        RecordingMetrics metrics = new RecordingMetrics();

        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(ScriptedFutureMode.COMPLETED, ScriptedFutureMode.TIMED_OUT),
                clock,
                metrics,
                engine(ruleDescriptor(), ignored -> {
                    clock.advance(Duration.ofMillis(11));
                    return availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH);
                }),
                engine(mlDescriptor(), ignored -> availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.engineResults().getFirst().status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.engineResults().get(1).score()).isNull();
        assertThat(result.engineResults().get(1).riskLevel()).isNull();
        assertThat(result.engineResults().get(1).confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.engineResults().get(1).statusReason()).isEqualTo("ORCHESTRATOR_ENGINE_TIMEOUT");
        assertThat(result.engineResults().get(1).latencyMs()).isEqualTo(ML_DEADLINE.toMillis());
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertThat(result.executionWarnings().stream().map(FraudScoringExecutionWarning::code))
                .contains(FraudScoringExecutionWarningCode.ENGINE_TIMEOUT_RECORDED);
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
        assertNoDecisionSurface();
    }

    @Test
    void requiredRulesTimeoutPreservesMlResultAndMarksRequiredFailure() {
        MutableClock clock = new MutableClock(RECEIVED_AT);

        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(ScriptedFutureMode.TIMED_OUT, ScriptedFutureMode.COMPLETED),
                clock,
                new RecordingMetrics(),
                engine(ruleDescriptor(), ignored -> availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                engine(mlDescriptor(), ignored -> {
                    clock.advance(Duration.ofMillis(13));
                    return availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM);
                })
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults()).hasSize(2);
        assertThat(result.engineResults().getFirst().status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(result.engineResults().getFirst().latencyMs()).isEqualTo(RULES_DEADLINE.toMillis());
        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().get(1).score()).isEqualTo(0.72d);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED);
        assertNoDecisionSurface();
    }

    @Test
    void timeoutDoesNotExposeRawEngineData() {
        MutableClock clock = new MutableClock(RECEIVED_AT);

        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(ScriptedFutureMode.COMPLETED, ScriptedFutureMode.TIMED_OUT),
                clock,
                new RecordingMetrics(),
                engine(ruleDescriptor(), ignored -> availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                engine(mlDescriptor(), ignored -> {
                    throw new IllegalStateException("raw-token payload endpoint accountId=123");
                })
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.toString())
                .contains("ORCHESTRATOR_ENGINE_TIMEOUT")
                .doesNotContain("raw-token", "payload", "endpoint", "accountId=123");
    }

    @Test
    void timeoutRecordsMetrics() {
        MutableClock clock = new MutableClock(RECEIVED_AT);
        RecordingMetrics metrics = new RecordingMetrics();

        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(ScriptedFutureMode.COMPLETED, ScriptedFutureMode.TIMED_OUT),
                clock,
                metrics,
                engine(ruleDescriptor(), ignored -> availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                engine(mlDescriptor(), ignored -> availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        )) {
            orchestrator.evaluate(context());
        }

        assertThat(metrics.timeoutCalls)
                .containsExactly(new TimeoutMetricCall("ml.python.primary", FraudEngineType.ML_MODEL, false));
        assertThat(metrics.engineResultCalls)
                .contains(new EngineResultMetricCall("ml.python.primary", FraudEngineType.ML_MODEL, FraudEngineStatus.TIMEOUT, false));
        assertThat(metrics.latencyCalls)
                .contains(new LatencyMetricCall(
                        "ml.python.primary",
                        FraudEngineType.ML_MODEL,
                        FraudEngineStatus.TIMEOUT,
                        false,
                        ML_DEADLINE
                ));
    }

    @Test
    void successfulEngineLatencyIsMeasuredDeterministically() {
        MutableClock clock = new MutableClock(RECEIVED_AT);
        RecordingMetrics metrics = new RecordingMetrics();

        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(ScriptedFutureMode.COMPLETED, ScriptedFutureMode.COMPLETED),
                clock,
                metrics,
                engine(ruleDescriptor(), ignored -> {
                    clock.advance(Duration.ofMillis(7));
                    return availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH);
                }),
                engine(mlDescriptor(), ignored -> {
                    clock.advance(Duration.ofMillis(19));
                    return availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM);
                })
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults()).extracting(FraudEngineResult::latencyMs)
                .containsExactly(0L, 0L);
        assertThat(metrics.latencyCalls).extracting(LatencyMetricCall::latency)
                .containsExactly(Duration.ofMillis(7), Duration.ofMillis(19));
    }

    @Test
    void throwingEngineLatencyIsMeasuredDeterministically() {
        MutableClock clock = new MutableClock(RECEIVED_AT);
        RecordingMetrics metrics = new RecordingMetrics();

        FraudScoringOrchestrationResult result;
        try (FraudScoringOrchestrator orchestrator = orchestrator(
                List.of(ScriptedFutureMode.COMPLETED, ScriptedFutureMode.COMPLETED),
                clock,
                metrics,
                engine(ruleDescriptor(), ignored -> availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                engine(mlDescriptor(), ignored -> {
                    clock.advance(Duration.ofMillis(17));
                    throw new IllegalStateException("secret raw exception");
                })
        )) {
            result = orchestrator.evaluate(context());
        }

        assertThat(result.engineResults().get(1).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(result.engineResults().get(1).latencyMs()).isEqualTo(17L);
        assertThat(metrics.latencyCalls.get(1).latency()).isEqualTo(Duration.ofMillis(17));
        assertThat(result.toString()).doesNotContain("secret raw exception");
    }

    private FraudScoringOrchestrator orchestrator(
            List<ScriptedFutureMode> modes,
            Clock clock,
            FraudScoringOrchestratorMetrics metrics,
            FraudSignalEngine rules,
            FraudSignalEngine ml
    ) {
        FraudScoringOrchestratorExecutionPolicy policy = new FraudScoringOrchestratorExecutionPolicy(List.of(
                new FraudEngineExecutionPolicy("rules.primary", RULES_DEADLINE, true),
                new FraudEngineExecutionPolicy("ml.python.primary", ML_DEADLINE, false)
        ));
        return new FraudScoringOrchestrator(
                new FraudSignalEngineRegistry(List.of(rules, ml)),
                policy,
                new BoundedFraudEngineExecutor(new ScriptedExecutorService(modes)),
                metrics,
                clock
        );
    }

    private ScoringContext context() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(event, event.featureSnapshot(), ScoringMode.ML, event.correlationId(), RECEIVED_AT);
    }

    private FraudSignalEngine engine(
            FraudEngineDescriptor descriptor,
            Function<ScoringContext, FraudEngineResult> handler
    ) {
        return new FraudSignalEngine() {
            @Override
            public FraudEngineResult evaluate(ScoringContext context) {
                return handler.apply(context);
            }

            @Override
            public FraudEngineDescriptor descriptor() {
                return descriptor;
            }
        };
    }

    private FraudEngineDescriptor ruleDescriptor() {
        return new FraudEngineDescriptor("rules.primary", FraudEngineType.RULES, "java", "1.0.0", true);
    }

    private FraudEngineDescriptor mlDescriptor() {
        return new FraudEngineDescriptor("ml.python.primary", FraudEngineType.ML_MODEL, "python", "1.0.0", false);
    }

    private FraudEngineResult availableResult(FraudEngineDescriptor descriptor, double score, RiskLevel riskLevel) {
        return new FraudEngineResult(
                descriptor.engineId(),
                descriptor.engineType(),
                descriptor.engineLanguage(),
                FraudEngineStatus.AVAILABLE,
                score,
                riskLevel,
                FraudEngineConfidence.MEDIUM,
                List.of("ENGINE_SIGNAL"),
                List.of(),
                List.of(),
                0L,
                null,
                null,
                null,
                RECEIVED_AT
        );
    }

    private void assertNoDecisionSurface() {
        assertThat(Arrays.stream(FraudScoringOrchestrationResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("finalDecision", "approve", "decline", "block", "recommendedAction", "finalRisk");
    }

    private enum ScriptedFutureMode {
        COMPLETED,
        TIMED_OUT
    }

    private static final class ScriptedExecutorService extends AbstractExecutorService {
        private final Deque<ScriptedFutureMode> modes;
        private boolean shutdown;

        private ScriptedExecutorService(List<ScriptedFutureMode> modes) {
            this.modes = new ArrayDeque<>(modes);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return new ScriptedFuture<>(task, modes.removeFirst());
        }
    }

    private static final class ScriptedFuture<T> implements Future<T> {
        private final Callable<T> task;
        private final ScriptedFutureMode mode;
        private boolean cancelled;

        private ScriptedFuture(Callable<T> task, ScriptedFutureMode mode) {
            this.task = task;
            this.mode = mode;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return mode == ScriptedFutureMode.COMPLETED;
        }

        @Override
        public T get() throws ExecutionException {
            return complete();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException {
            if (mode == ScriptedFutureMode.TIMED_OUT) {
                throw new TimeoutException("raw-token payload endpoint accountId=123");
            }
            return complete();
        }

        private T complete() throws ExecutionException {
            try {
                return task.call();
            } catch (Exception exception) {
                throw new ExecutionException(exception);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        private MutableClock(Instant current) {
            this(current, ZoneOffset.UTC);
        }

        private MutableClock(Instant current, ZoneId zone) {
            this.current = Objects.requireNonNull(current);
            this.zone = Objects.requireNonNull(zone);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    private static final class RecordingMetrics implements FraudScoringOrchestratorMetrics {
        private final List<FraudScoringOrchestrationStatus> orchestrationCalls = new ArrayList<>();
        private final List<EngineResultMetricCall> engineResultCalls = new ArrayList<>();
        private final List<LatencyMetricCall> latencyCalls = new ArrayList<>();
        private final List<TimeoutMetricCall> timeoutCalls = new ArrayList<>();
        private final List<String> requiredEngineFailedCalls = new ArrayList<>();

        @Override
        public void recordOrchestration(FraudScoringOrchestrationStatus status) {
            orchestrationCalls.add(Objects.requireNonNull(status));
        }

        @Override
        public void recordEngineResult(String engineId, FraudEngineType engineType, FraudEngineStatus status, boolean required) {
            FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
            FraudScoringOrchestratorMetricLabels.validateStatus(status);
            engineResultCalls.add(new EngineResultMetricCall(engineId, engineType, status, required));
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
            latencyCalls.add(new LatencyMetricCall(engineId, engineType, status, required, latency));
        }

        @Override
        public void recordTimeout(String engineId, FraudEngineType engineType, boolean required) {
            FraudScoringOrchestratorMetricLabels.validateEngine(engineId, engineType);
            timeoutCalls.add(new TimeoutMetricCall(engineId, engineType, required));
        }

        @Override
        public void recordRequiredEngineFailed(String engineId) {
            requiredEngineFailedCalls.add(engineId);
        }
    }

    private record EngineResultMetricCall(
            String engineId,
            FraudEngineType engineType,
            FraudEngineStatus status,
            boolean required
    ) {
    }

    private record LatencyMetricCall(
            String engineId,
            FraudEngineType engineType,
            FraudEngineStatus status,
            boolean required,
            Duration latency
    ) {
    }

    private record TimeoutMetricCall(String engineId, FraudEngineType engineType, boolean required) {
    }
}
