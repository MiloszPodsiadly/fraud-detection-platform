package com.frauddetection.alert.messaging;

import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionReason;

public final class MlPredictionEvidencePermanentProcessingException extends RuntimeException {

    public MlPredictionEvidencePermanentProcessingException(MlPredictionEvidenceProjectionReason reason) {
        super("ML_PREDICTION_EVIDENCE_PROJECTION_" + reason.name());
    }
}
