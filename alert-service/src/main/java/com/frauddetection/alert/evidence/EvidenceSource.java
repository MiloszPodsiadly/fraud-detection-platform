package com.frauddetection.alert.evidence;

public enum EvidenceSource {
    FEATURE_ENRICHER,
    FRAUD_SCORING_SERVICE,
    ML_INFERENCE_SERVICE,
    ALERT_SERVICE,
    LEGACY_SCORING_PAYLOAD
}
