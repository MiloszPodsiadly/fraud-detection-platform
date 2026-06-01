package com.frauddetection.scoring.orchestration.aggregation;

public enum EngineIntelligenceEmissionOmissionReason {
    DISABLED,
    PIPELINE_UNAVAILABLE,
    ORCHESTRATOR_FAILURE,
    AGGREGATION_FAILURE,
    MAPPER_FAILURE,
    UNKNOWN_FAILURE
}
