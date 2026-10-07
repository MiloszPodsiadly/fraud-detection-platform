package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;

public enum FeedbackDatasetMlPredictionEvidenceStatus {
    AVAILABLE,
    LEGITIMATELY_ABSENT,
    MISSING_UNEXPECTEDLY,
    MALFORMED,
    IDENTITY_MISMATCH;

    static FeedbackDatasetMlPredictionEvidenceStatus fromAuthoritativeOmission(
            MlPredictionEvidenceOmissionReason reason
    ) {
        return switch (reason) {
            case DIAGNOSTIC_EMISSION_DISABLED, LEGITIMATE_ABSENCE -> LEGITIMATELY_ABSENT;
            case DIAGNOSTIC_ENRICHMENT_UNAVAILABLE, ML_ENGINE_UNAVAILABLE -> MISSING_UNEXPECTEDLY;
            case SOURCE_TIMESTAMP_MISSING, INVALID_SCORE, IDENTITY_VALIDATION_FAILURE,
                    EVIDENCE_SOURCE_INTEGRITY_FAILURE, PREDICTION_NOT_ACCEPTED -> MALFORMED;
        };
    }
}
