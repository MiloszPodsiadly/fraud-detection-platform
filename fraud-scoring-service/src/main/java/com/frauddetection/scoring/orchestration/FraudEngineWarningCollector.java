package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.orchestration.runtime.FraudEngineExecutionPolicy;

import java.util.ArrayList;
import java.util.List;

final class FraudEngineWarningCollector {

    CollectedWarnings collect(FraudEngineExecutionPolicy policy, FraudEngineResult result) {
        if (result.status() == FraudEngineStatus.AVAILABLE) {
            return new CollectedWarnings(List.of(), null);
        }
        List<FraudScoringExecutionWarning> warnings = new ArrayList<>();
        warnings.add(new FraudScoringExecutionWarning(
                policy.engineId(),
                policy.required()
                        ? FraudScoringExecutionWarningCode.REQUIRED_ENGINE_NOT_AVAILABLE
                        : FraudScoringExecutionWarningCode.OPTIONAL_ENGINE_NOT_AVAILABLE,
                result.status(),
                policy.required()
        ));
        if (result.status() == FraudEngineStatus.TIMEOUT) {
            warnings.add(new FraudScoringExecutionWarning(
                    policy.engineId(),
                    FraudScoringExecutionWarningCode.ENGINE_TIMEOUT_RECORDED,
                    result.status(),
                    policy.required()
            ));
        }
        if (result.status() == FraudEngineStatus.DEGRADED) {
            if (isEvaluationFailure(result)) {
                warnings.add(new FraudScoringExecutionWarning(
                        policy.engineId(),
                        FraudScoringExecutionWarningCode.ENGINE_EVALUATION_FAILURE_RECORDED,
                        result.status(),
                        policy.required()
                ));
            }
            if (isPublicationFailure(result)) {
                warnings.add(new FraudScoringExecutionWarning(
                        policy.engineId(),
                        FraudScoringExecutionWarningCode.ENGINE_PUBLICATION_FAILURE_RECORDED,
                        result.status(),
                        policy.required()
                ));
            }
            warnings.add(new FraudScoringExecutionWarning(
                    policy.engineId(),
                    FraudScoringExecutionWarningCode.ENGINE_DEGRADED_RECORDED,
                    result.status(),
                    policy.required()
            ));
        }
        return new CollectedWarnings(warnings, failureCategoryFor(result));
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
        if (normalized.contains("client")) {
            return "client_error";
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

    record CollectedWarnings(List<FraudScoringExecutionWarning> warnings, String failureCategory) {
    }
}
