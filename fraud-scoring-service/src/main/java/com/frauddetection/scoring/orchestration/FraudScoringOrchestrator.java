package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FraudScoringOrchestrator {
    private static final String EVIDENCE_SOURCE = "ORCHESTRATOR";

    private final FraudSignalEngineRegistry registry;

    public FraudScoringOrchestrator(FraudSignalEngineRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
    }

    public FraudScoringOrchestrationResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        Instant generatedAt = context.receivedAt();
        List<FraudEngineResult> engineResults = new ArrayList<>();
        List<FraudScoringExecutionWarning> executionWarnings = new ArrayList<>();
        for (FraudSignalEngineRegistry.RegisteredEngine registeredEngine : registry.registeredEngines()) {
            FraudEngineResult engineResult = evaluateEngine(registeredEngine, context, generatedAt);
            engineResults.add(engineResult);
            addWarnings(registeredEngine.descriptor(), engineResult, executionWarnings);
        }
        FraudScoringOrchestrationStatus status = statusFor(registry.registeredEngines(), engineResults);
        return new FraudScoringOrchestrationResult(status, engineResults, executionWarnings, generatedAt);
    }

    private FraudEngineResult evaluateEngine(
            FraudSignalEngineRegistry.RegisteredEngine registeredEngine,
            ScoringContext context,
            Instant generatedAt
    ) {
        try {
            FraudEngineResult result = registeredEngine.engine().evaluate(context);
            if (result == null) {
                return failureResult(
                        registeredEngine.descriptor(),
                        OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_NULL_RESULT,
                        generatedAt
                );
            }
            return result;
        } catch (RuntimeException exception) {
            return failureResult(
                    registeredEngine.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION,
                    generatedAt
            );
        }
    }

    private FraudEngineResult failureResult(
            FraudEngineDescriptor descriptor,
            OrchestrationFailureReasonCode reasonCode,
            Instant generatedAt
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
                0L,
                null,
                null,
                reasonCode.wireValue(),
                generatedAt
        );
    }

    private void addWarnings(
            FraudEngineDescriptor descriptor,
            FraudEngineResult result,
            List<FraudScoringExecutionWarning> executionWarnings
    ) {
        if (result.status() == FraudEngineStatus.AVAILABLE) {
            return;
        }
        executionWarnings.add(new FraudScoringExecutionWarning(
                descriptor.engineId(),
                descriptor.required()
                        ? FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE
                        : FraudScoringExecutionWarningCode.OPTIONAL_ENGINE_NOT_AVAILABLE,
                result.status(),
                descriptor.required()
        ));
        if (result.status() == FraudEngineStatus.TIMEOUT) {
            executionWarnings.add(new FraudScoringExecutionWarning(
                    descriptor.engineId(),
                    FraudScoringExecutionWarningCode.ENGINE_TIMEOUT_RECORDED,
                    result.status(),
                    descriptor.required()
            ));
        }
        if (result.status() == FraudEngineStatus.DEGRADED) {
            executionWarnings.add(new FraudScoringExecutionWarning(
                    descriptor.engineId(),
                    FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED,
                    result.status(),
                    descriptor.required()
            ));
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
            if (registeredEngines.get(index).descriptor().required()) {
                return FraudScoringOrchestrationStatus.REQUIRED_ENGINE_FAILED;
            }
            optionalEngineUnavailable = true;
        }
        return optionalEngineUnavailable
                ? FraudScoringOrchestrationStatus.PARTIAL
                : FraudScoringOrchestrationStatus.COMPLETE;
    }
}
