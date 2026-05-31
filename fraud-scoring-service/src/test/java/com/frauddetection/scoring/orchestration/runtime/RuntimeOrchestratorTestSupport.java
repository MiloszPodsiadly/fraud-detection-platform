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
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrator;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

final class RuntimeOrchestratorTestSupport {
    static final Instant RECEIVED_AT = Instant.parse("2026-05-30T10:00:00Z");
    static final Duration RULES_DEADLINE = Duration.ofMillis(30);
    static final Duration ML_DEADLINE = Duration.ofMillis(40);

    private RuntimeOrchestratorTestSupport() {
    }

    static FraudScoringOrchestrator orchestrator(
            List<ExecutionMode> modes,
            FraudScoringOrchestratorMetrics metrics,
            MutableClock clock
    ) {
        return orchestrator(
                modes,
                metrics,
                clock,
                engine(ruleDescriptor(), ignored -> availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                engine(mlDescriptor(), ignored -> availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        );
    }

    static FraudScoringOrchestrator orchestrator(
            List<ExecutionMode> modes,
            FraudScoringOrchestratorMetrics metrics,
            MutableClock clock,
            FraudSignalEngine rules,
            FraudSignalEngine ml
    ) {
        return new FraudScoringOrchestrator(
                registry(rules, ml),
                executionPolicy(),
                new BoundedFraudEngineExecutor(new ScriptedExecutorService(modes)),
                metrics,
                clock
        );
    }

    static FraudSignalEngineRegistry registry() {
        return registry(
                engine(ruleDescriptor(), ignored -> availableResult(ruleDescriptor(), 0.82d, RiskLevel.HIGH)),
                engine(mlDescriptor(), ignored -> availableResult(mlDescriptor(), 0.72d, RiskLevel.MEDIUM))
        );
    }

    static FraudSignalEngineRegistry registry(FraudSignalEngine rules, FraudSignalEngine ml) {
        return new FraudSignalEngineRegistry(List.of(rules, ml));
    }

    static FraudScoringOrchestratorExecutionPolicy executionPolicy() {
        return new FraudScoringOrchestratorExecutionPolicy(List.of(
                new FraudEngineExecutionPolicy("rules.primary", RULES_DEADLINE, true),
                new FraudEngineExecutionPolicy("ml.python.primary", ML_DEADLINE, false)
        ));
    }

    static ScoringContext context() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(event, event.featureSnapshot(), ScoringMode.ML, event.correlationId(), RECEIVED_AT);
    }

    static FraudSignalEngine engine(
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

    static FraudEngineDescriptor ruleDescriptor() {
        return new FraudEngineDescriptor("rules.primary", FraudEngineType.RULES, "java", "1.0.0", true);
    }

    static FraudEngineDescriptor mlDescriptor() {
        return new FraudEngineDescriptor("ml.python.primary", FraudEngineType.ML_MODEL, "python", "1.0.0", false);
    }

    static FraudEngineResult availableResult(FraudEngineDescriptor descriptor, double score, RiskLevel riskLevel) {
        return availableResult(descriptor, score, riskLevel, 0L);
    }

    static FraudEngineResult availableResult(
            FraudEngineDescriptor descriptor,
            double score,
            RiskLevel riskLevel,
            long latencyMs
    ) {
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
                latencyMs,
                null,
                null,
                null,
                RECEIVED_AT
        );
    }

    enum ExecutionMode {
        COMPLETED,
        TIMED_OUT,
        REJECTED
    }

    static final class ScriptedExecutorService extends AbstractExecutorService {
        private final Deque<ExecutionMode> modes;
        private final Runnable beforeSubmit;
        private boolean shutdown;

        ScriptedExecutorService(List<ExecutionMode> modes) {
            this(modes, () -> {
            });
        }

        ScriptedExecutorService(List<ExecutionMode> modes, Runnable beforeSubmit) {
            this.modes = new ArrayDeque<>(modes);
            this.beforeSubmit = beforeSubmit;
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
            beforeSubmit.run();
            ExecutionMode mode = modes.removeFirst();
            if (mode == ExecutionMode.REJECTED) {
                throw new RejectedExecutionException("queue-full token endpoint accountId=123");
            }
            return new ScriptedFuture<>(task, mode);
        }
    }

    static final class ScriptedFuture<T> implements Future<T> {
        private final Callable<T> task;
        private final ExecutionMode mode;

        ScriptedFuture(Callable<T> task, ExecutionMode mode) {
            this.task = task;
            this.mode = mode;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return mode == ExecutionMode.COMPLETED;
        }

        @Override
        public T get() throws ExecutionException {
            return complete();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException {
            if (mode == ExecutionMode.TIMED_OUT) {
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

    static final class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        MutableClock() {
            this(RECEIVED_AT, ZoneOffset.UTC);
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

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
