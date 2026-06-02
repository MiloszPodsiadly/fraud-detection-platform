package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;

import java.util.Objects;

public record EngineIntelligenceWarningProjection(
        EngineIntelligenceWarningCode warningCode,
        int count
) {
    public EngineIntelligenceWarningProjection {
        Objects.requireNonNull(warningCode, "warningCode is required");
        if (count < 0) {
            throw new IllegalArgumentException("warning count must not be negative");
        }
    }
}
