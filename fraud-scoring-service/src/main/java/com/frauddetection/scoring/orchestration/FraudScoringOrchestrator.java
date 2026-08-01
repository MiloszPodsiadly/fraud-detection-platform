package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
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
    private static final String EVIDENCE_SOURCE = "ORCHESTRATOR";

    private final FraudSignalEngineRegistry registry;
    private final FraudScoringOrchestratorExecutionPolicy executionPolicy;
    private final BoundedFraudEngineExecutor executor;
    private final FraudScoringOrchestratorMetrics metrics;
    private final Clock clock;
    private final MonotonicTicker ticker;

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
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.ticker = Objects.requireNonNull(ticker, "ticker is required");
        validatePolicyAlignment();
    }

    public FraudScoringOrchestrationResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        List<FraudEngineResult> engineResults = new ArrayList<>();
        List<FraudScoringExecutionWarning> executionWarnings = new ArrayList<>();
        for (FraudSignalEngineRegistry.RegisteredEngine registeredEngine : registry.registeredEngines()) {
            FraudEngineExecutionPolicy policy = executionPolicy.policyFor(registeredEngine.descriptor().engineId());
            EvaluatedEngineResult evaluated = evaluateEngine(registeredEngine, policy, context);
            FraudEngineResult engineResult = evaluated.result();
            engineResults.add(engineResult);
            addWarnings(policy, engineResult, executionWarnings);
            recordMetricsSafely(registeredEngine.descriptor(), policy, engineResult, evaluated.latency());
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
        executor.close();
    }

    private EvaluatedEngineResult evaluateEngine(
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
        Instant generatedAt = clock.instant();
        FraudEngineResult result = switch (execution.status()) {
            case COMPLETED -> execution.value() == null
                    ? failureResult(
                    registeredEngine.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_NULL_RESULT,
                    generatedAt,
                    latency
            )
                    : publishedResult(registeredEngine.descriptor(), execution.value(), latency, generatedAt);
            case FAILED -> failureResult(
                    registeredEngine.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION,
                    generatedAt,
                    latency
            );
            case REJECTED -> failureResult(
                    registeredEngine.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_REJECTED,
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

    private FraudEngineResult publishedResult(
            FraudEngineDescriptor descriptor,
            FraudSignalEvaluation source,
            Duration latency,
            Instant generatedAt
    ) {
        try {
            return new FraudEngineResult(
                    descriptor.engineId(),
                    descriptor.engineType(),
                    descriptor.engineLanguage(),
                    source.status(),
                    source.score(),
                    source.riskLevel(),
                    source.confidence(),
                    source.reasonCodes(),
                    source.contributions(),
                    source.evidence(),
                    latency.toMillis(),
                    source.modelName(),
                    source.modelVersion(),
                    source.statusReason(),
                    generatedAt
            );
        } catch (RuntimeException exception) {
            return failureResult(
                    descriptor,
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_PUBLICATION_FAILURE,
                    generatedAt,
                    latency
            );
        }
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
        recordSafely(() -> metrics.recordEngineFailure(
                result.engineId(),
                result.engineType(),
                failureCategoryFor(result)
        ));
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
            if (isEvaluationFailure(result)) {
                executionWarnings.add(new FraudScoringExecutionWarning(
                        policy.engineId(),
                        FraudScoringExecutionWarningCode.ENGINE_EVALUATION_FAILURE_RECORDED,
                        result.status(),
                        policy.required()
                ));
            }
            if (isPublicationFailure(result)) {
                executionWarnings.add(new FraudScoringExecutionWarning(
                        policy.engineId(),
                        FraudScoringExecutionWarningCode.ENGINE_PUBLICATION_FAILURE_RECORDED,
                        result.status(),
                        policy.required()
                ));
            }
            executionWarnings.add(new FraudScoringExecutionWarning(
                    policy.engineId(),
                    FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED,
                    result.status(),
                    policy.required()
            ));
        }
    }

    private boolean isEvaluationFailure(FraudEngineResult result) {
        return OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION.wireValue().equals(result.statusReason())
                || OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_NULL_RESULT.wireValue().equals(result.statusReason())
                || OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_REJECTED.wireValue().equals(result.statusReason());
    }

    private boolean isPublicationFailure(FraudEngineResult result) {
        return OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_PUBLICATION_FAILURE.wireValue()
                .equals(result.statusReason());
    }

    private String failureCategoryFor(FraudEngineResult result) {
        if (result.status() == FraudEngineStatus.TIMEOUT) {
            return "timeout";
        }
        String reason = result.statusReason();
        if (reason == null || reason.isBlank()) {
            return result.status() == FraudEngineStatus.UNAVAILABLE ? "missing" : "degraded";
        }
        String normalized = reason.toLowerCase();
        if (normalized.contains("timeout")) {
            return "timeout";
        }
        if (normalized.contains("exception")) {
            return "exception";
        }
        if (normalized.contains("null")) {
            return "null_result";
        }
        if (normalized.contains("publication")) {
            return "publication_failure";
        }
        if (normalized.contains("rejected")) {
            return "rejected";
        }
        if (normalized.contains("type")) {
            return "invalid_type";
        }
        if (normalized.contains("value")) {
            return "invalid_value";
        }
        if (normalized.contains("inconsistent")) {
            return "inconsistent";
        }
        if (normalized.contains("unavailable") || normalized.contains("missing")) {
            return "missing";
        }
        return result.status() == FraudEngineStatus.UNAVAILABLE ? "missing" : "degraded";
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
