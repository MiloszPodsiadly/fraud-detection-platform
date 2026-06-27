package com.frauddetection.alert.audit.outbox;

public enum WriteActionAuditOutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT
}
