package com.frauddetection.common.events.evidence;

public enum ScoringEvidenceType {
    TRANSACTION_FEATURE,
    CUSTOMER_BEHAVIOR,
    DEVICE_SIGNAL,
    GEO_SIGNAL,
    VELOCITY_SIGNAL,
    MERCHANT_SIGNAL,
    RULE_MATCH,
    MODEL_EXPLANATION,
    SCORING_SNAPSHOT,
    DIAGNOSTIC
}
