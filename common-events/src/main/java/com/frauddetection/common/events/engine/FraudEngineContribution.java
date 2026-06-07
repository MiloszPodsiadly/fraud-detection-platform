package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudEngineContribution(
        String feature,
        String value,
        Double weight,
        FraudEngineContributionDirection direction
) {
    private static final BigDecimal MIN_WEIGHT = new BigDecimal("-1.0000");
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("1.0000");
    private static final int WEIGHT_SCALE_MAX = 4;

    public FraudEngineContribution {
        feature = FraudEngineValuePolicy.requireBoundedIdentifier(
                feature,
                "feature",
                FraudEngineValuePolicy.FEATURE_CODE_MAX_LENGTH
        );
        value = FraudEngineValuePolicy.validateOptionalSafeSummary(
                value,
                "value",
                FraudEngineValuePolicy.VALUE_BUCKET_MAX_LENGTH
        );
        if (weight != null) {
            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException("weight must be finite when present");
            }
            BigDecimal decimalWeight = BigDecimal.valueOf(weight);
            if (decimalWeight.compareTo(MIN_WEIGHT) < 0 || decimalWeight.compareTo(MAX_WEIGHT) > 0) {
                throw new IllegalArgumentException("weight must be between -1.0000 and 1.0000");
            }
            if (decimalWeight.scale() > WEIGHT_SCALE_MAX) {
                throw new IllegalArgumentException("weight scale must be less than or equal to 4");
            }
        }
        Objects.requireNonNull(direction, "direction is required");
    }

    public String featureCode() {
        return feature;
    }

    public String valueBucket() {
        return value;
    }
}
