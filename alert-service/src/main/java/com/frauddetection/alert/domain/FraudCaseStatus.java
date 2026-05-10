package com.frauddetection.alert.domain;

public enum FraudCaseStatus {
    OPEN,
    IN_REVIEW,
    ESCALATED,
    RESOLVED,
    REOPENED,
    CONFIRMED_FRAUD,
    FALSE_POSITIVE,
    CLOSED
}
