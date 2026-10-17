package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;
import java.util.regex.Pattern;

public record ModelPromotionApproval(
        String approvalId,
        String proposalId,
        Outcome outcome,
        String approvedBy,
        Instant approvedAt,
        String rationaleSha256
) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:@-]{1,128}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public ModelPromotionApproval {
        requireIdentifier(approvalId, "approvalId");
        requireIdentifier(proposalId, "proposalId");
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        requireIdentifier(approvedBy, "approvedBy");
        if (approvedAt == null) {
            throw new IllegalArgumentException("approvedAt is required");
        }
        if (rationaleSha256 == null || !SHA256.matcher(rationaleSha256).matches()) {
            throw new IllegalArgumentException("rationaleSha256 must be lowercase SHA-256");
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    public enum Outcome {
        APPROVED,
        REJECTED
    }
}
