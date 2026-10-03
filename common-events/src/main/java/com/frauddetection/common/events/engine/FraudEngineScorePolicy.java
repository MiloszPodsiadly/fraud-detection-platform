package com.frauddetection.common.events.engine;

import java.math.BigDecimal;

public final class FraudEngineScorePolicy {

    public static final int SCORE_SCALE_MAX = 4;

    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_SCORE = BigDecimal.ONE;

    private FraudEngineScorePolicy() {
    }

    public static Double validateOptional(Double score, String fieldName) {
        if (score == null) {
            return null;
        }
        return validatePresent(score, fieldName);
    }

    public static Double requireValid(Double score, String fieldName) {
        if (score == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return validatePresent(score, fieldName);
    }

    private static Double validatePresent(Double score, String fieldName) {
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException(fieldName + " must be finite when present");
        }
        BigDecimal decimalScore = BigDecimal.valueOf(score);
        if (decimalScore.compareTo(MIN_SCORE) < 0 || decimalScore.compareTo(MAX_SCORE) > 0) {
            throw new IllegalArgumentException(fieldName + " must be null or between 0.0000 and 1.0000");
        }
        double rounded = Math.rint(score * 10_000.0d) / 10_000.0d;
        if (Math.abs(score - rounded) > 0.000000001d) {
            throw new IllegalArgumentException(fieldName + " scale must be less than or equal to " + SCORE_SCALE_MAX);
        }
        return score;
    }
}
