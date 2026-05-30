package com.frauddetection.scoring.orchestration;

enum OrchestrationFailureReasonCode {
    ORCHESTRATOR_ENGINE_EXCEPTION,
    ORCHESTRATOR_ENGINE_NULL_RESULT,
    ORCHESTRATOR_ENGINE_DESCRIPTOR_INVALID;

    String wireValue() {
        return name();
    }
}
