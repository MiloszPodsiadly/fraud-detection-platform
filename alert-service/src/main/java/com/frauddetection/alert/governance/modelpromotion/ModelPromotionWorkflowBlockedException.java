package com.frauddetection.alert.governance.modelpromotion;

public final class ModelPromotionWorkflowBlockedException extends IllegalStateException {

    private final Reason reason;

    public ModelPromotionWorkflowBlockedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNAUTHENTICATED,
        UNAUTHORIZED,
        UNSUPPORTED_PRINCIPAL,
        POLICY_UNAVAILABLE,
        EVIDENCE_UNAVAILABLE,
        EVIDENCE_CHANGED,
        POLICY_CHANGED,
        PROPOSAL_NOT_FOUND
    }
}
