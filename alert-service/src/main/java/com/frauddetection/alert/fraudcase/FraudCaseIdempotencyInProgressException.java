package com.frauddetection.alert.fraudcase;

public class FraudCaseIdempotencyInProgressException extends RuntimeException {
    public FraudCaseIdempotencyInProgressException() {
        super("Fraud case lifecycle mutation is already in progress for this idempotency key.");
    }
}
