package com.frauddetection.common.events.engine;

public enum FraudEngineEvidenceType {
    REASON_CODE,
    FEATURE_BUCKET,
    MODEL_EXPLANATION,
    OPERATIONAL_STATUS,
    RULE_MATCH,
    VELOCITY_SIGNAL,
    DEVICE_SIGNAL,
    MERCHANT_SIGNAL,
    GRAPH_SIGNAL,
    OPERATIONAL_FALLBACK
}
