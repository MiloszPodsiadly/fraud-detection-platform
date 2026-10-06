package com.frauddetection.common.events.intelligence;

public enum MlPredictionEvidenceOmissionReason {
    DIAGNOSTIC_EMISSION_DISABLED,
    ML_ENGINE_UNAVAILABLE,
    SOURCE_TIMESTAMP_MISSING,
    INVALID_SCORE,
    IDENTITY_VALIDATION_FAILURE,
    LEGITIMATE_ABSENCE,
    PREDICTION_NOT_ACCEPTED
}
