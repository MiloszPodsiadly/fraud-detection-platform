package com.frauddetection.alert.fraudcase;

public class FraudCaseIdempotencyConflictException extends RuntimeException {
    public FraudCaseIdempotencyConflictException() {
        super("Idempotency key conflicts with a different fraud case lifecycle mutation.");
    }
}
