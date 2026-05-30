package com.frauddetection.scoring.engine.ml;

public enum PythonMlSignalReasonCode {
    ML_MODEL_SIGNAL,
    ML_MODEL_UNAVAILABLE,
    ML_MODEL_TIMEOUT,
    ML_MODEL_INVALID_RESPONSE,
    ML_SCORE_MISSING,
    ML_SCORE_OUT_OF_RANGE,
    ML_MODEL_METADATA_MISSING,
    ML_AVAILABILITY_METADATA_MISSING,
    ML_AVAILABILITY_METADATA_INVALID,
    ML_CLIENT_ERROR;

    public String wireValue() {
        return name();
    }
}
