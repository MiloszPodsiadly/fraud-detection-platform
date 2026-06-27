package com.frauddetection.alert.audit.outbox;

public enum WriteActionAuditOutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT
}
