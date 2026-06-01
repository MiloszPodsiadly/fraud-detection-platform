package com.frauddetection.common.events.intelligence;

public enum EngineIntelligenceScoreDeltaBucket {
    NONE,
    SMALL,
    MEDIUM,
    LARGE,
    UNAVAILABLE;

    public static EngineIntelligenceScoreDeltaBucket fromComparableDelta(Double delta) {
        if (delta == null) {
            return UNAVAILABLE;
        }
        if (!Double.isFinite(delta) || delta < 0.0d || delta > 1.0d) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_SCORE_DELTA_OUT_OF_RANGE");
        }
        if (delta == 0.0d) {
            return NONE;
        }
        if (delta <= 0.15d) {
            return SMALL;
        }
        if (delta <= 0.35d) {
            return MEDIUM;
        }
        return LARGE;
    }
}
