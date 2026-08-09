package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudEngineExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.orchestration.runtime.MonotonicTicker;
import com.frauddetection.scoring.orchestration.runtime.NoOpFraudScoringOrchestratorMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FraudScoringOrchestrator implements AutoCloseable {

    private final FraudSignalEngineRegistry registry;
    private final FraudScoringOrchestratorExecutionPolicy executionPolicy;
    private final FraudScoringOrchestratorMetrics metrics;
    private final Clock clock;
    private final FraudEngineExecutionRunner executionRunner;
    private final FraudEnginePublicationFactory publicationFactory = new FraudEnginePublicationFactory();
    private final FraudEngineWarningCollector warningCollector = new FraudEngineWarningCollector();

    public FraudScoringOrchestrator(FraudSignalEngineRegistry registry) {
        this(
                registry,
                FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy(),
                BoundedFraudEngineExecutor.defaultInternalExecutor(),
                new NoOpFraudScoringOrchestratorMetrics(),
                Clock.systemUTC(),
                MonotonicTicker.system()
        );
    }

    public FraudScoringOrchestrator(
            FraudSignalEngineRegistry registry,
            FraudScoringOrchestratorExecutionPolicy executionPolicy,
            BoundedFraudEngineExecutor executor,
            FraudScoringOrchestratorMetrics metrics,
            Clock clock
    ) {
        this(registry, executionPolicy, executor, metrics, clock, MonotonicTicker.system());
    }

    public FraudScoringOrchestrator(
            FraudSignalEngineRegistry registry,
            FraudScoringOrchestratorExecutionPolicy executionPolicy,
            BoundedFraudEngineExecutor executor,
            FraudScoringOrchestratorMetrics metrics,
            Clock clock,
            MonotonicTicker ticker
    ) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.executionRunner = new FraudEngineExecutionRunner(
                Objects.requireNonNull(executor, "executor is required"),
                this.clock,
                Objects.requireNonNull(ticker, "ticker is required")
        );
        validatePolicyAlignment();
    }

    public FraudScoringOrchestrationResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        List<FraudEngineResult> engineResults = new ArrayList<>();
        List<FraudScoringExecutionWarning> executionWarnings = new ArrayList<>();
        for (FraudSignalEngineRegistry.RegisteredEngine registeredEngine : registry.registeredEngines()) {
            FraudEngineExecutionPolicy policy = executionPolicy.policyFor(registeredEngine.descriptor().engineId());
            FraudEngineExecutionRunner.FraudEngineExecution execution =
                    executionRunner.run(registeredEngine, policy, context);
            FraudEngineResult engineResult = publicationFactory.publish(execution);

            engineResults.add(engineResult);
            collectWarningsAndFailureMetric(policy, engineResult, executionWarnings);
            recordMetricsSafely(registeredEngine.descriptor(), policy, engineResult, execution.latency());
        }
        FraudScoringOrchestrationStatus status = statusFor(registry.registeredEngines(), engineResults);
        recordOrchestrationSafely(status);
        Instant generatedAt = engineResults.isEmpty()
                ? clock.instant()
                : engineResults.getLast().generatedAt();
        return new FraudScoringOrchestrationResult(status, engineResults, executionWarnings, generatedAt);
    }

    @Override
    public void close() {
        executionRunner.close();
    }

    private void collectWarningsAndFailureMetric(
            FraudEngineExecutionPolicy policy,
            FraudEngineResult result,
            List<FraudScoringExecutionWarning> executionWarnings
    ) {
        FraudEngineWarningCollector.CollectedWarnings collected = warningCollector.collect(policy, result);
        executionWarnings.addAll(collected.warnings());
        if (collected.failureCategory() != null) {
            recordSafely(() -> metrics.recordEngineFailure(
                    result.engineId(),
                    result.engineType(),
                    collected.failureCategory()
            ));
        }
    }

    private void recordMetrics(
            FraudEngineDescriptor descriptor,
            FraudEngineExecutionPolicy policy,
            FraudEngineResult result,
            Duration latency
    ) {
        recordSafely(() -> metrics.recordEngineResult(
                descriptor.engineId(), descriptor.engineType(), result.status(), policy.required()
        ));
        recordSafely(() -> metrics.recordEngineLatency(
                descriptor.engineId(), descriptor.engineType(), result.status(), policy.required(), latency
        ));
        if (result.status() == FraudEngineStatus.TIMEOUT) {
            recordSafely(() -> metrics.recordTimeout(descriptor.engineId(), descriptor.engineType(), policy.required()));
        }
        if (policy.required() && result.status() != FraudEngineStatus.AVAILABLE) {
            recordSafely(() -> metrics.recordRequiredEngineFailed(descriptor.engineId()));
        }
    }

    private void recordMetricsSafely(
            FraudEngineDescriptor descriptor,
            FraudEngineExecutionPolicy policy,
            FraudEngineResult result,
            Duration latency
    ) {
        try {
            recordMetrics(descriptor, policy, result, latency);
        } catch (RuntimeException ignored) {
            // Metrics are best-effort and must not affect orchestration results.
        }
    }

    private void recordOrchestrationSafely(FraudScoringOrchestrationStatus status) {
        recordSafely(() -> metrics.recordOrchestration(status));
    }

    private void recordSafely(Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException ignored) {
            // Metrics are best-effort and must not affect orchestration results.
        }
    }

    private FraudScoringOrchestrationStatus statusFor(
            List<FraudSignalEngineRegistry.RegisteredEngine> registeredEngines,
            List<FraudEngineResult> engineResults
    ) {
        boolean optionalEngineUnavailable = false;
        for (int index = 0; index < registeredEngines.size(); index++) {
            FraudEngineStatus status = engineResults.get(index).status();
            if (status == FraudEngineStatus.AVAILABLE) {
                continue;
            }
            if (executionPolicy.policyFor(registeredEngines.get(index).descriptor().engineId()).required()) {
                return FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED;
            }
            optionalEngineUnavailable = true;
        }
        return optionalEngineUnavailable
                ? FraudScoringOrchestrationStatus.PARTIAL
                : FraudScoringOrchestrationStatus.COMPLETE;
    }

    private void validatePolicyAlignment() {
        for (FraudSignalEngineRegistry.RegisteredEngine registeredEngine : registry.registeredEngines()) {
            FraudEngineDescriptor descriptor = registeredEngine.descriptor();
            if (descriptor.required() != executionPolicy.policyFor(descriptor.engineId()).required()) {
                throw new IllegalArgumentException("ENGINE_EXECUTION_POLICY_REQUIRED_MISMATCH");
            }
        }
    }
}
