package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;
import java.util.Objects;

public record ModelPromotionProposalResult(
        String proposalId,
        ModelPromotionProposalDocument.ReviewStatus reviewStatus,
        ModelPromotionProposal proposal,
        ModelPromotionApproval approval,
        String policySha256,
        String assessmentSha256,
        Instant submittedAt,
        Instant reviewedAt,
        boolean idempotentReplay
) {

    public ModelPromotionProposalResult {
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId is required");
        }
        Objects.requireNonNull(reviewStatus, "reviewStatus is required");
        Objects.requireNonNull(proposal, "proposal is required");
        Objects.requireNonNull(submittedAt, "submittedAt is required");
    }

    public ModelPromotionProposalResult asReplay() {
        return new ModelPromotionProposalResult(
                proposalId,
                reviewStatus,
                proposal,
                approval,
                policySha256,
                assessmentSha256,
                submittedAt,
                reviewedAt,
                true
        );
    }
}
