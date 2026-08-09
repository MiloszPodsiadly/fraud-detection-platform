package com.frauddetection.scoring.engine.velocity;

public enum VelocitySignalReasonCode {
    VELOCITY_FEATURES_UNAVAILABLE,
    VELOCITY_FEATURE_TYPE_INVALID,
    VELOCITY_FEATURE_VALUE_INVALID,
    VELOCITY_FEATURES_INCONSISTENT;

    public String wireValue() {
        return name();
    }
}
