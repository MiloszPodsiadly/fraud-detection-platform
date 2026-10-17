package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

public record ModelPromotionProposalSubmission(
        ModelPromotionTransitionProposal transition,
        ModelPromotionOperationContext operationContext,
        ModelPromotionPolicy policySnapshot,
        ModelPromotionAssessment assessmentSnapshot,
        ModelPromotionProposal proposal
) {

    public ModelPromotionProposalSubmission {
        Objects.requireNonNull(transition, "transition is required");
        Objects.requireNonNull(operationContext, "operationContext is required");
        Objects.requireNonNull(policySnapshot, "policySnapshot is required");
        Objects.requireNonNull(proposal, "proposal is required");
        if (!transition.operationId().equals(operationContext.operationId())) {
            throw new IllegalArgumentException("transition and operation context identifiers differ");
        }
        if (proposal.requestedState() != transition.requestedTargetState()) {
            throw new IllegalArgumentException("proposal target state differs from transition target state");
        }
        if (!proposal.proposedBy().equals(operationContext.actorId())) {
            throw new IllegalArgumentException("proposal actor must match the server operation actor");
        }
        if (proposal.proposedAt().isAfter(operationContext.requestedAt())) {
            throw new IllegalArgumentException("proposal timestamp cannot be after the operation timestamp");
        }
    }
}
