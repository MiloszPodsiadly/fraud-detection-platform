package com.frauddetection.alert.messaging;

import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionReason;

public final class MlPredictionEvidenceTransientProcessingException extends RuntimeException {

    public MlPredictionEvidenceTransientProcessingException(MlPredictionEvidenceProjectionReason reason) {
        super("ML_PREDICTION_EVIDENCE_PROJECTION_" + reason.name());
    }
}
