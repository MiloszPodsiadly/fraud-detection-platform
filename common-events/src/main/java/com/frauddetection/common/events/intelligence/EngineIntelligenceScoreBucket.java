package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.Objects;

public enum EngineIntelligenceScoreBucket {
    // Reserved for explicitly omitted public score information. Missing and operational scores use UNAVAILABLE.
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH,
    UNAVAILABLE;

    public static EngineIntelligenceScoreBucket from(FraudEngineStatus status, Double score) {
        Objects.requireNonNull(status, "status is required");
        if (status != FraudEngineStatus.AVAILABLE || score == null) {
            return UNAVAILABLE;
        }
        if (!Double.isFinite(score) || score < 0.0d || score > 1.0d) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_SCORE_OUT_OF_RANGE");
        }
        if (score <= 0.25d) {
            return LOW;
        }
        if (score <= 0.50d) {
            return MEDIUM;
        }
        if (score <= 0.75d) {
            return HIGH;
        }
        return VERY_HIGH;
    }
}
