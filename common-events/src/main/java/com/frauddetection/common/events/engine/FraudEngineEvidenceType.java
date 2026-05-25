package com.frauddetection.common.events.engine;

public enum FraudEngineEvidenceType {
    RULE_MATCH,
    MODEL_EXPLANATION,
    VELOCITY_SIGNAL,
    DEVICE_SIGNAL,
    MERCHANT_SIGNAL,
    GRAPH_SIGNAL,
    OPERATIONAL_FALLBACK
}
