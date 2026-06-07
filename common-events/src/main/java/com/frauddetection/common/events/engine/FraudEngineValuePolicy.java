package com.frauddetection.common.events.engine;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class FraudEngineValuePolicy {
    static final int ENGINE_ID_MAX_LENGTH = 64;
    static final int REASON_CODE_MAX_LENGTH = 64;
    static final int FEATURE_CODE_MAX_LENGTH = 64;
    static final int VALUE_BUCKET_MAX_LENGTH = 64;
    static final int EVIDENCE_CODE_MAX_LENGTH = 64;
    static final int DESCRIPTION_CODE_MAX_LENGTH = 128;
    static final int ENGINE_LANGUAGE_MAX_LENGTH = 16;
    static final int MODEL_NAME_MAX_LENGTH = 64;
    static final int MODEL_VERSION_MAX_LENGTH = 64;
    static final int FALLBACK_REASON_MAX_LENGTH = 128;

    private static final Pattern MACHINE_CODE_PATTERN = Pattern.compile("[A-Z0-9_]+");
    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Set<String> FORBIDDEN_COMPACT_TERMS = Set.of(
            "rawpayload",
            "rawrequest",
            "rawresponse",
            "rawevidence",
            "rawcontribution",
            "featurevector",
            "stacktrace",
            "exceptionmessage",
            "token",
            "secret",
            "endpoint",
            "metadata",
            "customerid",
            "accountid",
            "cardid",
            "deviceid",
            "merchantid",
            "pan",
            "iban",
            "email",
            "phone",
            "submittedby",
            "correlationid",
            "idempotencykey",
            "requestpayloadhash",
            "groundtruth",
            "modeltraininglabel",
            "traininglabel",
            "finaldecision",
            "recommendedaction",
            "approve",
            "decline",
            "block",
            "paymentauthorization",
            "ruleupdate"
    );
    private static final Set<String> LEGACY_METADATA_REASON_CODES = Set.of(
            "ML_AVAILABILITY_METADATA_MISSING",
            "ML_AVAILABILITY_METADATA_INVALID",
            "ML_MODEL_METADATA_MISSING"
    );

    private FraudEngineValuePolicy() {
    }

    static String requireSafeIdentifier(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        validateLengthAndControls(value, fieldName, maxLength);
        if (!SAFE_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a bounded machine-readable identifier");
        }
        rejectForbiddenTerms(value, fieldName);
        return value;
    }

    static String optionalSafeIdentifier(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be null or non-blank");
        }
        validateLengthAndControls(value, fieldName, maxLength);
        if (!SAFE_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a bounded machine-readable identifier");
        }
        rejectForbiddenTerms(value, fieldName);
        return value;
    }

    static String requireBoundedIdentifier(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        validateLengthAndControls(value, fieldName, maxLength);
        if (!SAFE_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a bounded machine-readable identifier");
        }
        return value;
    }

    static String optionalBoundedIdentifier(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be null or non-blank");
        }
        validateLengthAndControls(value, fieldName, maxLength);
        if (!SAFE_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a bounded machine-readable identifier");
        }
        return value;
    }

    static String requireMachineCode(String value, String fieldName, int maxLength) {
        String safe = requireSafeIdentifier(value, fieldName, maxLength);
        if (!MACHINE_CODE_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException(fieldName + " must use UPPER_SNAKE_CASE");
        }
        return safe;
    }

    static String optionalMachineCode(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        String safe = optionalSafeIdentifier(value, fieldName, maxLength);
        if (!MACHINE_CODE_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException(fieldName + " must use UPPER_SNAKE_CASE");
        }
        return safe;
    }

    static String requireSafeSummary(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        validateLengthAndControls(value, fieldName, maxLength);
        rejectForbiddenTerms(value, fieldName);
        return value;
    }

    static String validateOptionalSafeSummary(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be null or non-blank");
        }
        validateLengthAndControls(value, fieldName, maxLength);
        rejectForbiddenTerms(value, fieldName);
        return value;
    }

    private static void validateLengthAndControls(String value, String fieldName, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length of " + maxLength);
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
    }

    private static void rejectForbiddenTerms(String value, String fieldName) {
        if (("reasonCode".equals(fieldName) || "statusReason".equals(fieldName))
                && LEGACY_METADATA_REASON_CODES.contains(value)) {
            return;
        }
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (FORBIDDEN_COMPACT_TERMS.stream().anyMatch(compact::contains)) {
            throw new IllegalArgumentException(fieldName + " contains forbidden contract text");
        }
    }
}
