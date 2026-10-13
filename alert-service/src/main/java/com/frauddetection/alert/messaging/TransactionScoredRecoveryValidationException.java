package com.frauddetection.alert.messaging;

public final class TransactionScoredRecoveryValidationException extends RuntimeException {

    public TransactionScoredRecoveryValidationException() {
        super("TRANSACTION_SCORED_RECOVERY_PROVENANCE_INVALID");
    }
}
