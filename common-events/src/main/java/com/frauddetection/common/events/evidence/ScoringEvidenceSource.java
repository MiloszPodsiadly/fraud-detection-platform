package com.frauddetection.common.events.evidence;

public enum ScoringEvidenceSource {
    RULE_BASED_SCORING,
    ML_MODEL,
    ML_RUNTIME,
    FEATURE_SNAPSHOT,
    SCORING_FALLBACK,
    LEGACY_SCORING
}
