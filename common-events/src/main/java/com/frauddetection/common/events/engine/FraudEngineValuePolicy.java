package com.frauddetection.common.events.engine;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class FraudEngineValuePolicy {
    static final int MAX_IDENTIFIER_LENGTH = 128;

    private static final Pattern REASON_CODE_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Set<String> FORBIDDEN_SUMMARY_TERMS = Set.of(
            "stacktrace",
            "stack trace",
            "exception",
            "secret",
            "token",
            "password",
            "rawpayload",
            "raw payload",
            "rawfeatures",
            "raw features"
    );

    private FraudEngineValuePolicy() {
    }

    static void requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        validateLengthAndControls(value, fieldName, maxLength);
    }

    static void validateOptionalText(String value, String fieldName, int maxLength) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be null or non-blank");
        }
        validateLengthAndControls(value, fieldName, maxLength);
    }

    static void requireReasonCode(String value, String fieldName) {
        if (value == null || !REASON_CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a bounded machine-readable reason code");
        }
    }

    static void validateOptionalReasonCode(String value, String fieldName) {
        if (value != null) {
            requireReasonCode(value, fieldName);
        }
    }

    static void requireSafeSummary(String value, String fieldName, int maxLength) {
        requireText(value, fieldName, maxLength);
        rejectForbiddenSummaryTerms(value, fieldName);
    }

    static void validateOptionalSafeSummary(String value, String fieldName, int maxLength) {
        validateOptionalText(value, fieldName, maxLength);
        if (value != null) {
            rejectForbiddenSummaryTerms(value, fieldName);
        }
    }

    private static void validateLengthAndControls(String value, String fieldName, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds the bounded contract length");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
    }

    private static void rejectForbiddenSummaryTerms(String value, String fieldName) {
        String lowerCaseValue = value.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_SUMMARY_TERMS.stream().anyMatch(lowerCaseValue::contains)) {
            throw new IllegalArgumentException(fieldName + " must contain a safe bounded summary only");
        }
    }
}
