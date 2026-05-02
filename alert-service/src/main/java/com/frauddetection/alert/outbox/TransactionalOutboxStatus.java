package com.frauddetection.alert.outbox;

public enum TransactionalOutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    PUBLISH_CONFIRMATION_UNKNOWN,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    RECOVERY_REQUIRED
}
