package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

record ModelPromotionProposalRequestIntent(
        ModelPromotionTransitionProposal transition,
        ModelPromotionEvaluationWindow evaluationWindow,
        String proposalId,
        String actorId,
        ModelPromotionDecisionRationale rationale
) {
    ModelPromotionProposalRequestIntent {
        Objects.requireNonNull(transition, "transition is required");
        Objects.requireNonNull(evaluationWindow, "evaluationWindow is required");
        Objects.requireNonNull(rationale, "rationale is required");
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId is required");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId is required");
        }
        if (!ModelPromotionDecisionRationale.proposalReference(proposalId).equals(rationale.referenceId())) {
            throw new IllegalArgumentException("rationale does not belong to this proposal");
        }
    }
}
