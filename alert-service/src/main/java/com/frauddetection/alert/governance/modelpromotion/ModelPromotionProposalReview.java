package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

public record ModelPromotionProposalReview(
        String proposalId,
        ModelPromotionApproval approval,
        ModelPromotionOperationContext operationContext
) {

    public ModelPromotionProposalReview {
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId is required");
        }
        Objects.requireNonNull(approval, "approval is required");
        Objects.requireNonNull(operationContext, "operationContext is required");
        if (!proposalId.equals(approval.proposalId())) {
            throw new IllegalArgumentException("approval must reference the reviewed proposal");
        }
        if (!approval.approvedBy().equals(operationContext.actorId())) {
            throw new IllegalArgumentException("approval actor must match the server operation actor");
        }
        if (approval.approvedAt().isAfter(operationContext.requestedAt())) {
            throw new IllegalArgumentException("approval timestamp cannot be after the operation timestamp");
        }
    }
}
