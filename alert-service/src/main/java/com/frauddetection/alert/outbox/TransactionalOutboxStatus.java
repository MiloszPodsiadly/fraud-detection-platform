package com.frauddetection.alert.outbox;

public enum TransactionalOutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISH_ATTEMPTED,
    PUBLISHED,
    PUBLISH_CONFIRMATION_UNKNOWN,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    RECOVERY_REQUIRED
}
