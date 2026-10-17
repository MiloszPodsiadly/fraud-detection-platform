package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;
import java.util.regex.Pattern;

public record ModelPromotionProposal(
        String proposalId,
        ModelPromotionLifecycleState requestedState,
        String proposedBy,
        Instant proposedAt,
        ModelPromotionDecisionRationale rationale
) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:@-]{1,128}$");
    public ModelPromotionProposal {
        requireIdentifier(proposalId, "proposalId");
        if (requestedState == null) {
            throw new IllegalArgumentException("requestedState is required");
        }
        requireIdentifier(proposedBy, "proposedBy");
        if (proposedAt == null) {
            throw new IllegalArgumentException("proposedAt is required");
        }
        if (rationale == null || !ModelPromotionDecisionRationale.proposalReference(proposalId)
                .equals(rationale.referenceId())) {
            throw new IllegalArgumentException("rationale must be bound to the proposal identifier");
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
