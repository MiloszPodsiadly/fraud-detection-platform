package com.frauddetection.alert.governance.modelpromotion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public record ModelPromotionDecisionRationale(
        String referenceId,
        String content,
        String sha256
) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:@-]{1,128}$");
    private static final Pattern REFERENCE = Pattern.compile("^model-promotion:proposal:[A-Za-z0-9._:@-]{1,128}(?::approval:[A-Za-z0-9._:@-]{1,128})?$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAXIMUM_CONTENT_BYTES = 4096;

    public ModelPromotionDecisionRationale {
        if (referenceId == null || !REFERENCE.matcher(referenceId).matches()) {
            throw new IllegalArgumentException("rationale reference is invalid");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("rationale content is required");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_CONTENT_BYTES) {
            throw new IllegalArgumentException("rationale content exceeds the maximum byte length");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches() || !sha256.equals(sha256(content))) {
            throw new IllegalArgumentException("rationale SHA-256 does not match the rationale content");
        }
    }

    public static String proposalReference(String proposalId) {
        return "model-promotion:proposal:" + requireIdentifier(proposalId, "proposalId");
    }

    public static String approvalReference(String proposalId, String approvalId) {
        return proposalReference(proposalId) + ":approval:" + requireIdentifier(approvalId, "approvalId");
    }

    public static String sha256(String content) {
        if (content == null) {
            throw new IllegalArgumentException("rationale content is required");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
