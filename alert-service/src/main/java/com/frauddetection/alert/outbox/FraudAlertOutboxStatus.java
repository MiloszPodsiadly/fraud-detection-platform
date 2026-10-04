package com.frauddetection.alert.outbox;

public enum FraudAlertOutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISH_ATTEMPTED,
    PUBLISHED,
    PUBLISH_CONFIRMATION_UNKNOWN
}
