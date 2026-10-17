package com.frauddetection.alert.governance;

import java.util.Set;
import java.util.regex.Pattern;

public record ExactModelArtifactIdentity(
        String modelName,
        String modelVersion,
        String featureContractVersion,
        String modelArtifactSha256
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

    public ExactModelArtifactIdentity {
        modelName = identity(modelName, 64, "modelName");
        modelVersion = identity(modelVersion, 64, "modelVersion");
        featureContractVersion = identity(featureContractVersion, 96, "featureContractVersion");
        if (modelArtifactSha256 == null || !SHA256.matcher(modelArtifactSha256).matches()) {
            throw new IllegalArgumentException("modelArtifactSha256 must be lowercase SHA-256");
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
}
