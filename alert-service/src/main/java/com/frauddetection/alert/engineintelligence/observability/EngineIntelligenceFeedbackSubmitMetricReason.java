package com.frauddetection.alert.engineintelligence.observability;

public enum EngineIntelligenceFeedbackSubmitMetricReason {
    VALIDATION_FAILED,
    IDEMPOTENCY_REPLAY,
    IDEMPOTENCY_CONFLICT,
    AUDIT_FAILURE,
    STORE_UNAVAILABLE,
    UNKNOWN_FAILURE
}
