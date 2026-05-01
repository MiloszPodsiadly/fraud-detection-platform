package com.frauddetection.alert.service;

public class ConflictingIdempotencyKeyException extends RuntimeException {
    public ConflictingIdempotencyKeyException() {
        super("Idempotency key was already used with a different request payload.");
    }
}
