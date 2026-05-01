package com.frauddetection.alert.regulated;

public class MissingIdempotencyKeyException extends RuntimeException {
    public MissingIdempotencyKeyException() {
        super("X-Idempotency-Key is required for regulated mutation commands.");
    }
}
