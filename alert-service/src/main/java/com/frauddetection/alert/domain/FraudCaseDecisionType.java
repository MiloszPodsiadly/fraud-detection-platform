package com.frauddetection.alert.domain;

public enum FraudCaseDecisionType {
    FRAUD_CONFIRMED,
    FALSE_POSITIVE,
    NEEDS_MORE_INFO,
    ESCALATE,
    NO_ACTION
}
