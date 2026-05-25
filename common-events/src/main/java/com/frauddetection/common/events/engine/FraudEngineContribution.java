package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudEngineContribution(
        String feature,
        String value,
        Double weight,
        FraudEngineContributionDirection direction
) {
    private static final int MAX_LABEL_LENGTH = 128;

    public FraudEngineContribution {
        FraudEngineValuePolicy.requireText(feature, "feature", MAX_LABEL_LENGTH);
        FraudEngineValuePolicy.validateOptionalSafeSummary(value, "value", MAX_LABEL_LENGTH);
        if (weight != null && !Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite when present");
        }
    }
}
