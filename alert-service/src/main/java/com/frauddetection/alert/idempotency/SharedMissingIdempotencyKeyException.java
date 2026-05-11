package com.frauddetection.alert.idempotency;

public class SharedMissingIdempotencyKeyException extends RuntimeException {
    public SharedMissingIdempotencyKeyException() {
        super("Idempotency key is required.");
    }
}
