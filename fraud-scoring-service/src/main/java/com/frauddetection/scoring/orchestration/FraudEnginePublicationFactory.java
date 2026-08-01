package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class FraudEnginePublicationFactory {
    private static final String EVIDENCE_SOURCE = "ORCHESTRATOR";

    FraudEngineResult publish(FraudEngineExecutionRunner.FraudEngineExecution execution) {
        return switch (execution.status()) {
            case COMPLETED -> execution.evaluation() == null
                    ? failureResult(
                    execution.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_NULL_RESULT,
                    execution.generatedAt(),
                    execution.latency()
            )
                    : publishedResult(
                    execution.descriptor(),
                    execution.evaluation(),
                    execution.latency(),
                    execution.generatedAt()
            );
            case FAILED -> failureResult(
                    execution.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_EXCEPTION,
                    execution.generatedAt(),
                    execution.latency()
            );
            case REJECTED -> failureResult(
                    execution.descriptor(),
                    OrchestrationFailureReasonCode.ORCHESTRATOR_ENGINE_REJECTED,
                    execution.generatedAt(),
                    execution.latency()
            );
            case TIMED_OUT -> timeoutResult(execution.descriptor(), execution.generatedAt(), execution.latency());
        };
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
}
