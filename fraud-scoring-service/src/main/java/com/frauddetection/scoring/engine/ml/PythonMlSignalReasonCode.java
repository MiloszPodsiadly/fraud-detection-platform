package com.frauddetection.scoring.engine.ml;

public enum PythonMlSignalReasonCode {
    ML_MODEL_SIGNAL,
    ML_MODEL_AVAILABLE,
    ML_MODEL_UNAVAILABLE,
    ML_MODEL_TIMEOUT,
    ML_MODEL_INVALID_RESPONSE,
    ML_MODEL_DEGRADED,
    ML_SCORE_AVAILABLE,
    ML_SCORE_MISSING,
    ML_SCORE_OUT_OF_RANGE,
    ML_CONFIDENCE_UNAVAILABLE,
    ML_MODEL_METADATA_MISSING,
    ML_CLIENT_ERROR,
    ML_RESPONSE_REJECTED;

    public String wireValue() {
        return name();
    }
}
