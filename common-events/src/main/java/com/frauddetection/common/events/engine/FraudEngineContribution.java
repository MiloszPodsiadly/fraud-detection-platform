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
        feature = FraudEngineValuePolicy.requireMachineCode(
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
        validateWeightDirection(weight, direction);
    }

    public String featureCode() {
        return feature;
    }

    public String valueBucket() {
        return value;
    }

    private static void validateWeightDirection(Double weight, FraudEngineContributionDirection direction) {
        if (weight == null) {
            return;
        }
        switch (direction) {
            case INCREASES_RISK -> {
                if (weight < 0.0d) {
                    throw new IllegalArgumentException("INCREASES_RISK contribution weight must be null or non-negative");
                }
            }
            case DECREASES_RISK -> {
                if (weight > 0.0d) {
                    throw new IllegalArgumentException("DECREASES_RISK contribution weight must be null or non-positive");
                }
            }
            case NEUTRAL -> {
                if (Double.compare(weight, 0.0d) != 0) {
                    throw new IllegalArgumentException("NEUTRAL contribution weight must be null or zero");
                }
            }
            case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN contribution weight must be null");
        }
    }
}
