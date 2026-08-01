package com.frauddetection.scoring.orchestration;

import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudEngineExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.MonotonicTicker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class FraudEngineExecutionRunner implements AutoCloseable {
    private final BoundedFraudEngineExecutor executor;
    private final Clock clock;
    private final MonotonicTicker ticker;

    FraudEngineExecutionRunner(
            BoundedFraudEngineExecutor executor,
            Clock clock,
            MonotonicTicker ticker
    ) {
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.ticker = Objects.requireNonNull(ticker, "ticker is required");
    }

    FraudEngineExecution run(
            FraudSignalEngineRegistry.RegisteredEngine registeredEngine,
            FraudEngineExecutionPolicy policy,
            ScoringContext context
    ) {
        long startedNanos = ticker.readNanos();
        BoundedFraudEngineExecutor.ExecutionResult<FraudSignalEvaluation> execution = executor.execute(
                () -> registeredEngine.engine().evaluate(context),
                policy.deadline()
        );
        Duration latency = measuredLatency(startedNanos, policy.deadline(), execution.status());
        return new FraudEngineExecution(
                registeredEngine.descriptor(),
                execution.status(),
                execution.value(),
                latency,
                clock.instant()
        );
    }

    @Override
    public void close() {
        executor.close();
    }

    private Duration measuredLatency(
            long startedNanos,
            Duration deadline,
            BoundedFraudEngineExecutor.ExecutionStatus executionStatus
    ) {
        if (executionStatus == BoundedFraudEngineExecutor.ExecutionStatus.TIMED_OUT) {
            return deadline;
        }
        long elapsedNanos;
        try {
            elapsedNanos = Math.subtractExact(ticker.readNanos(), startedNanos);
        } catch (ArithmeticException exception) {
            return deadline;
        }
        if (elapsedNanos <= 0L) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.ofNanos(elapsedNanos);
        return elapsed.compareTo(deadline) > 0 ? deadline : elapsed;
    }

    record FraudEngineExecution(
            FraudEngineDescriptor descriptor,
            BoundedFraudEngineExecutor.ExecutionStatus status,
            FraudSignalEvaluation evaluation,
            Duration latency,
            Instant generatedAt
    ) {
    }
}
