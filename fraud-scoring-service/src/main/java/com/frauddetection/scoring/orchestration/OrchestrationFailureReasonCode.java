package com.frauddetection.scoring.orchestration;

enum OrchestrationFailureReasonCode {
    ORCHESTRATOR_ENGINE_EXCEPTION,
    ORCHESTRATOR_ENGINE_NULL_RESULT;

    String wireValue() {
        return name();
    }
}
