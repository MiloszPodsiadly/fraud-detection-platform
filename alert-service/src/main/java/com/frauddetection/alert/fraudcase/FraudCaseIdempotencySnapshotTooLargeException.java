package com.frauddetection.alert.fraudcase;

public class FraudCaseIdempotencySnapshotTooLargeException extends RuntimeException {

    public FraudCaseIdempotencySnapshotTooLargeException() {
        super("Fraud case lifecycle idempotency response snapshot exceeded the configured size limit.");
    }
}
