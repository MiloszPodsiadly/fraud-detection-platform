package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import com.frauddetection.scoring.orchestration.runtime.FraudEngineExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorExecutionPolicy;
import com.frauddetection.scoring.orchestration.runtime.FraudScoringOrchestratorMetrics;
import com.frauddetection.scoring.orchestration.runtime.NoOpFraudScoringOrchestratorMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FraudScoringOrchestrator implements AutoCloseable {
    private static final String EVIDENCE_SOURCE = "ORCHESTRATOR";

    private final FraudSignalEngineRegistry registry;
    private final FraudScoringOrchestratorExecutionPolicy executionPolicy;
    private final BoundedFraudEngineExecutor executor;
    private final FraudScoringOrchestratorMetrics metrics;
    private final Clock clock;

    public FraudScoringOrchestrator(FraudSignalEngineRegistry registry) {
        this(
                registry,
                FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy(),
                BoundedFraudEngineExecutor.defaultInternalExecutor(),
                new NoOpFraudScoringOrchestratorMetrics(),
                Clock.systemUTC()
        );
    }

    public FraudScoringOrchestrator(
            FraudSignalEngineRegistry registry,
            FraudScoringOrchestratorExecutionPolicy executionPolicy,
            BoundedFraudEngineExecutor executor,
            FraudScoringOrchestratorMetrics metrics,
            Clock clock
    ) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        validatePolicyAlignment();
    }

    public FraudScoringOrchestrationResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        Instant generatedAt = context.receivedAt();
        List<FraudEngineResult> engineResults = new ArrayList<>();
        List<FraudScoringExecutionWarning> executionWarnings = new ArrayList<>();
        for (FraudSignalEngineRegistry.RegisteredEngine registeredEngine : registry.registeredEngines()) {
            FraudEngineExecutionPolicy policy = executionPolicy.policyFor(registeredEngine.descriptor().engineId());
            EvaluatedEngineResult evaluated = evaluateEngine(registeredEngine, policy, context, generatedAt);
            FraudEngineResult engineResult = evaluated.result();
            engineResults.add(engineResult);
            addWarnings(policy, engineResult, executionWarnings);
            recordMetrics(registeredEngine.descriptor(), policy, engineResult, evaluated.latency());
        }
        FraudScoringOrchestrationStatus status = statusFor(registry.registeredEngines(), engineResults);
        metrics.recordOrchestration(status);
        return new FraudScoringOrchestrationResult(status, engineResults, executionWarnings, generatedAt);
    }

    @Override
    public void close() {
        executor.close();
    }

    private EvaluatedEngineResult evaluateEngine(
            FraudSignalEngineRegistry.RegisteredEngine registeredEngine,
            FraudEngineExecutionPolicy policy,
            ScoringContext context,
            Instant generatedAt
    ) {
        Instant startedAt = clock.instant();
        BoundedFraudEngineExecutor.ExecutionResult<FraudEngineResult> execution = executor.execute(
                () -> registeredEngine.engine().evaluate(context),
                policy.deadline()
        );
        Duration latency = measuredLatency(startedAt, policy.deadline(), execution.status());
        FraudEngineResult result = switch (execution.status()) {
            case COMPLETED -> execution.value() == null
                    ? failureResult(
                    registeredEngine.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_NULL_RESULT,
                    generatedAt,
                    latency
            )
                    : execution.value();
            case FAILED -> failureResult(
                    registeredEngine.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION,
                    generatedAt,
                    latency
            );
            case TIMED_OUT -> timeoutResult(registeredEngine.descriptor(), generatedAt, latency);
        };
        return new EvaluatedEngineResult(result, latency);
    }

    private FraudEngineResult failureResult(
            FraudEngineDescriptor descriptor,
            OrchestrationFailureReasonCode reasonCode,
            Instant generatedAt,
            Duration latency
    ) {
        return new FraudEngineResult(
                descriptor.engineId(),
                descriptor.engineType(),
                descriptor.engineLanguage(),
                FraudEngineStatus.DEGRADED,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode.wireValue()),
                List.of(),
                List.of(new FraudEngineEvidence(
                        FraudEngineEvidenceType.OPERATIONAL_FALLBACK,
                        reasonCode.wireValue(),
                        "Engine status",
                        "Engine execution did not produce a usable signal.",
                        EVIDENCE_SOURCE,
                        FraudEngineEvidenceStatus.PARTIAL
                )),
                latency.toMillis(),
                null,
                null,
                reasonCode.wireValue(),
                generatedAt
        );
    }

    private FraudEngineResult timeoutResult(
            FraudEngineDescriptor descriptor,
            Instant generatedAt,
            Duration latency
    ) {
        OrchestrationFailureReasonCode reasonCode = OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_TIMEOUT;
        return new FraudEngineResult(
                descriptor.engineId(),
                descriptor.engineType(),
                descriptor.engineLanguage(),
                FraudEngineStatus.TIMEOUT,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode.wireValue()),
                List.of(),
                List.of(new FraudEngineEvidence(
                        FraudEngineEvidenceType.OPERATIONAL_FALLBACK,
                        reasonCode.wireValue(),
                        "Engine status",
                        "Engine execution exceeded its bounded deadline.",
                        EVIDENCE_SOURCE,
                        FraudEngineEvidenceStatus.UNAVAILABLE
                )),
                latency.toMillis(),
                null,
                null,
                reasonCode.wireValue(),
                generatedAt
        );
    }

    private void addWarnings(
            FraudEngineExecutionPolicy policy,
            FraudEngineResult result,
            List<FraudScoringExecutionWarning> executionWarnings
    ) {
        if (result.status() == FraudEngineStatus.AVAILABLE) {
            return;
        }
        executionWarnings.add(new FraudScoringExecutionWarning(
                policy.engineId(),
                policy.required()
                        ? FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE
                        : FraudScoringExecutionWarningCode.OPTIONAL_ENGINE_NOT_AVAILABLE,
                result.status(),
                policy.required()
        ));
        if (result.status() == FraudEngineStatus.TIMEOUT) {
            executionWarnings.add(new FraudScoringExecutionWarning(
                    policy.engineId(),
                    FraudScoringExecutionWarningCode.ENGINE_TIMEOUT_RECORDED,
                    result.status(),
                    policy.required()
            ));
        }
        if (result.status() == FraudEngineStatus.DEGRADED) {
            executionWarnings.add(new FraudScoringExecutionWarning(
                    policy.engineId(),
                    FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED,
                    result.status(),
                    policy.required()
            ));
        }
    }

    private void recordMetrics(
            FraudEngineDescriptor descriptor,
            FraudEngineExecutionPolicy policy,
            FraudEngineResult result,
            Duration latency
    ) {
        metrics.recordEngineResult(descriptor.engineId(), descriptor.engineType(), result.status(), policy.required());
        metrics.recordEngineLatency(descriptor.engineId(), descriptor.engineType(), result.status(), policy.required(), latency);
        if (result.status() == FraudEngineStatus.TIMEOUT) {
            metrics.recordTimeout(descriptor.engineId(), descriptor.engineType(), policy.required());
        }
        if (policy.required() && result.status() != FraudEngineStatus.AVAILABLE) {
            metrics.recordRequiredEngineFailed(descriptor.engineId());
        }
    }

    private Duration measuredLatency(
            Instant startedAt,
            Duration deadline,
            BoundedFraudEngineExecutor.ExecutionStatus executionStatus
    ) {
        if (executionStatus == BoundedFraudEngineExecutor.ExecutionStatus.TIMED_OUT) {
            return deadline;
        }
        Duration elapsed = Duration.between(startedAt, clock.instant());
        if (elapsed.isNegative()) {
            return Duration.ZERO;
        }
        return elapsed.compareTo(deadline) > 0 ? deadline : elapsed;
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

    private record EvaluatedEngineResult(FraudEngineResult result, Duration latency) {
    }
}
