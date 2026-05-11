package com.frauddetection.alert.fraudcase;

public class FraudCaseMissingIdempotencyKeyException extends RuntimeException {
    public FraudCaseMissingIdempotencyKeyException() {
        super("Idempotency key is required for fraud case lifecycle mutation.");
    }
}
