package com.frauddetection.alert.idempotency;

public class SharedInvalidIdempotencyKeyException extends RuntimeException {
    public SharedInvalidIdempotencyKeyException() {
        super("Idempotency key is invalid.");
    }
}
