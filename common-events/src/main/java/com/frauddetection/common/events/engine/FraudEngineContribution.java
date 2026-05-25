package com.frauddetection.common.events.engine;

public record FraudEngineContribution(
        String feature,
        String value,
        Double weight,
        String direction
) {
    private static final int MAX_LABEL_LENGTH = 128;

    public FraudEngineContribution {
        requireText(feature, "feature");
        validateOptionalText(value, "value");
        validateOptionalText(direction, "direction");
        if (weight != null && !Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite when present");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        validateLength(value, fieldName);
    }

    private static void validateOptionalText(String value, String fieldName) {
        if (value != null) {
            validateLength(value, fieldName);
        }
    }

    private static void validateLength(String value, String fieldName) {
        if (value.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds the bounded contract length");
        }
    }
}
