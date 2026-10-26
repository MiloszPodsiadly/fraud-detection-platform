package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

public record DurableModelPromotionTransitionCommand(
        ModelPromotionTransitionCommand transition,
        ModelPromotionOperationContext operationContext,
        ModelPromotionPolicy policySnapshot,
        ModelPromotionAssessment assessmentSnapshot,
        ModelPromotionProposal proposal,
        ModelPromotionApproval approval
) {
    public DurableModelPromotionTransitionCommand {
        Objects.requireNonNull(transition, "transition is required");
        Objects.requireNonNull(operationContext, "operationContext is required");
        Objects.requireNonNull(proposal, "proposal is required");
        Objects.requireNonNull(approval, "approval is required");
        if (!transition.operationId().equals(operationContext.operationId())) {
            throw new IllegalArgumentException("transition and operation context identifiers differ");
        }
    }

}
