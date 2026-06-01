package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineIntelligenceWarningSummary(
        EngineIntelligenceWarningCode code,
        int count
) {
    public EngineIntelligenceWarningSummary {
        Objects.requireNonNull(code, "code is required");
        if (count < 0) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_WARNING_COUNT_NEGATIVE");
        }
    }
}
