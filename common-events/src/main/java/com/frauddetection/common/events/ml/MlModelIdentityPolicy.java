package com.frauddetection.common.events.ml;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MlModelIdentityPolicy {
    public static final int MODEL_NAME_MAX_LENGTH = 64;
    public static final int MODEL_VERSION_MAX_LENGTH = 64;
    public static final int FEATURE_CONTRACT_VERSION_MAX_LENGTH = 96;
    public static final int ARTIFACT_SHA256_LENGTH = 64;

    private static final Pattern IDENTITY_PART_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ARTIFACT_SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> FORBIDDEN_COMPACT_TERMS = Set.of(
            "accountid",
            "apikey",
            "authorization",
            "bearer",
            "cardid",
            "correlationid",
            "customerid",
            "deviceid",
            "email",
            "endpoint",
            "exceptionmessage",
            "feedbackid",
            "finaldecision",
            "groundtruth",
            "idempotencykey",
            "merchantid",
            "metadata",
            "modeltraininglabel",
            "password",
            "paymentauthorization",
            "rawfeaturevector",
            "rawmlrequest",
            "rawmlresponse",
            "rawpayload",
            "rawrequest",
            "rawresponse",
            "requestpayloadhash",
            "secret",
            "stacktrace",
            "submittedby",
            "token",
            "traininglabel",
            "transactionid"
    );

    private MlModelIdentityPolicy() {
    }

    public static String requireModelName(String value, String fieldName) {
        return requireIdentityPart(value, fieldName, MODEL_NAME_MAX_LENGTH);
    }

    public static String requireModelVersion(String value, String fieldName) {
        return requireIdentityPart(value, fieldName, MODEL_VERSION_MAX_LENGTH);
    }

    public static String requireFeatureContractVersion(String value, String fieldName) {
        return requireIdentityPart(value, fieldName, FEATURE_CONTRACT_VERSION_MAX_LENGTH);
    }

    public static String optionalModelName(String value, String fieldName) {
        return optionalIdentityPart(value, fieldName, MODEL_NAME_MAX_LENGTH);
    }

    public static String optionalModelVersion(String value, String fieldName) {
        return optionalIdentityPart(value, fieldName, MODEL_VERSION_MAX_LENGTH);
    }

    public static String optionalFeatureContractVersion(String value, String fieldName) {
        return optionalIdentityPart(value, fieldName, FEATURE_CONTRACT_VERSION_MAX_LENGTH);
    }

    public static String requireArtifactSha256(String value, String fieldName) {
        if (value == null || !ARTIFACT_SHA256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    public static String optionalArtifactSha256(String value, String fieldName) {
        return value == null ? null : requireArtifactSha256(value, fieldName);
    }

    private static String requireIdentityPart(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        validateIdentityPart(value, fieldName, maxLength);
        return value;
    }

    private static String optionalIdentityPart(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be null or non-blank");
        }
        validateIdentityPart(value, fieldName, maxLength);
        return value;
    }

    private static void validateIdentityPart(String value, String fieldName, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length of " + maxLength);
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
        if (!IDENTITY_PART_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must match ^[A-Za-z0-9._-]+$");
        }
        rejectForbiddenTerms(value, fieldName);
    }

    private static void rejectForbiddenTerms(String value, String fieldName) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (FORBIDDEN_COMPACT_TERMS.stream().anyMatch(compact::contains)) {
            throw new IllegalArgumentException(fieldName + " contains forbidden contract text");
        }
    }
}
