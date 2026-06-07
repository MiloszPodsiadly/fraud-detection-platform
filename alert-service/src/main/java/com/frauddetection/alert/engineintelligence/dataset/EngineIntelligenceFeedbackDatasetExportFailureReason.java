package com.frauddetection.alert.engineintelligence.dataset;

public enum EngineIntelligenceFeedbackDatasetExportFailureReason {
    INVALID_EXPORT_QUERY,
    FEEDBACK_STORE_UNAVAILABLE,
    ALERT_STORE_UNAVAILABLE,
    PROJECTION_STORE_UNAVAILABLE,
    CORRUPTED_FEEDBACK,
    CORRUPTED_ALERT_DATA,
    CORRUPTED_PROJECTION,
    SERIALIZATION_FAILED
}
