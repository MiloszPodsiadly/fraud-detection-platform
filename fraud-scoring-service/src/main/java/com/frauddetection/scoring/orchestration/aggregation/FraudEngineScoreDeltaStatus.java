package com.frauddetection.scoring.orchestration.aggregation;

public enum FraudEngineScoreDeltaStatus {
    AVAILABLE,
    UNAVAILABLE_ENGINE_STATUS,
    UNAVAILABLE_MISSING_SCORE,
    UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS
}
