package com.frauddetection.alert.fraudcase;

public final class FraudCaseEvidenceValidationException extends RuntimeException {

    private final Reason reason;

    public FraudCaseEvidenceValidationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        RAPID_TRANSFER_MEMBERSHIP_INVALID,
        RAPID_TRANSFER_MEMBERSHIP_MISSING,
        RAPID_TRANSFER_SNAPSHOT_INCONSISTENT,
        CURRENT_EVENT_NOT_IN_EVIDENCE_IDS,
        CURRENT_EVENT_CUSTOMER_MISMATCH,
        CROSS_CUSTOMER_TRANSACTION,
        CASE_CUSTOMER_MISMATCH,
        CASE_KEY_MISMATCH
    }
}
