package com.frauddetection.common.events.engine;

public record FraudEngineEvidence(
        String evidenceType,
        String reasonCode,
        String title,
        String description,
        String source,
        String status
) {
    private static final int MAX_TEXT_LENGTH = 256;

    public FraudEngineEvidence {
        requireText(evidenceType, "evidenceType");
        requireText(title, "title");
        requireText(source, "source");
        requireText(status, "status");
        validateOptionalText(reasonCode, "reasonCode");
        validateOptionalText(description, "description");
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
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds the bounded contract length");
        }
    }
}
