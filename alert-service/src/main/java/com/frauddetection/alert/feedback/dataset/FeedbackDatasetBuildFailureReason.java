package com.frauddetection.alert.feedback.dataset;

public enum FeedbackDatasetBuildFailureReason {
    NONE,
    INVALID_REQUEST,
    FEEDBACK_STORE_UNAVAILABLE,
    ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE,
    ML_PREDICTION_EVIDENCE_INTEGRITY_FAILURE,
    DATASET_SERIALIZATION_FAILED
}
