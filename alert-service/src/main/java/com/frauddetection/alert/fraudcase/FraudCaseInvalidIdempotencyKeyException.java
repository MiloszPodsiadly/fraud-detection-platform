package com.frauddetection.alert.fraudcase;

public class FraudCaseInvalidIdempotencyKeyException extends RuntimeException {
    public FraudCaseInvalidIdempotencyKeyException() {
        super("Idempotency key is invalid for fraud case lifecycle mutation.");
    }
}
