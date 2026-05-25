package com.frauddetection.scoring.context;

import java.util.regex.Pattern;

public final class ScoringContextValuePolicy {

    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private ScoringContextValuePolicy() {
    }

    public static String requireCorrelationId(String correlationId) {
        if (correlationId == null) {
            throw new NullPointerException("correlationId is required");
        }
        if (!CORRELATION_ID_PATTERN.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("correlationId must be a bounded machine-readable identifier");
        }
        return correlationId;
    }
}
