package com.frauddetection.alert.governance.exactmodelshadowperformance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

public record ExactModelShadowPerformanceSelector(
        String modelName,
        String modelVersion,
        String featureContractVersion,
        String modelArtifactSha256,
        String fromInclusive,
        String toInclusive
) {

    private static final Pattern IDENTITY_PART = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> FORBIDDEN_IDENTITY_TERMS = Set.of(
            "accountid", "apikey", "authorization", "bearer", "cardid", "correlationid", "customerid",
            "deviceid", "email", "endpoint", "exceptionmessage", "feedbackid", "finaldecision", "groundtruth",
            "idempotencykey", "merchantid", "metadata", "modeltraininglabel", "password", "paymentauthorization",
            "rawfeaturevector", "rawmlrequest", "rawmlresponse", "rawpayload", "rawrequest", "rawresponse",
            "requestpayloadhash", "secret", "stacktrace", "submittedby", "token", "traininglabel", "transactionid"
    );

    public ExactModelShadowPerformanceSelector {
        modelName = identity(modelName, 64, "modelName");
        modelVersion = identity(modelVersion, 64, "modelVersion");
        featureContractVersion = identity(featureContractVersion, 96, "featureContractVersion");
        if (modelArtifactSha256 == null || !SHA256.matcher(modelArtifactSha256).matches()) {
            throw new IllegalArgumentException("modelArtifactSha256 must be lowercase SHA-256");
        }
        fromInclusive = timestamp(fromInclusive, "fromInclusive");
        toInclusive = timestamp(toInclusive, "toInclusive");
        if (fromInclusive != null && toInclusive != null
                && Instant.parse(fromInclusive).isAfter(Instant.parse(toInclusive))) {
            throw new IllegalArgumentException("fromInclusive must not be after toInclusive");
        }
    }

    public String selectionKey() {
        String payload = "exact-model-shadow-selection-v1\n"
                + String.join("\n", modelName, modelVersion, featureContractVersion,
                modelArtifactSha256, nullToEmpty(fromInclusive), nullToEmpty(toInclusive));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String identity(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !IDENTITY_PART.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        String compact = value.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (FORBIDDEN_IDENTITY_TERMS.stream().anyMatch(compact::contains)) {
            throw new IllegalArgumentException(field + " contains a forbidden value");
        }
        return value;
    }

    private static String timestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Instant instant = Instant.parse(value);
            if (!instant.toString().equals(value)) {
                throw new IllegalArgumentException(field + " must be canonical RFC3339 UTC");
            }
            return value;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be canonical RFC3339 UTC", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
