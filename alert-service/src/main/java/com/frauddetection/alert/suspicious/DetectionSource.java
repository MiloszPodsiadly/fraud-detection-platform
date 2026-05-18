package com.frauddetection.alert.suspicious;

public enum DetectionSource {
    RULE_ENGINE,
    ML_MODEL,
    HYBRID_SCORING,
    SCORING_FALLBACK,
    LEGACY_SCORING
}
