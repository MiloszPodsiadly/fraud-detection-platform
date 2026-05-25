package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record FraudEngineResult(
        String engineId,
        FraudEngineType engineType,
        String engineLanguage,
        FraudEngineStatus status,
        Double score,
        RiskLevel riskLevel,
        FraudEngineConfidence confidence,
        List<String> reasonCodes,
        List<FraudEngineContribution> contributions,
        List<FraudEngineEvidence> evidence,
        Long latencyMs,
        String modelName,
        String modelVersion,
        String fallbackReason,
        Instant generatedAt
) {
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final Pattern SAFE_FALLBACK_REASON = Pattern.compile("[A-Z0-9_]{1,64}");

    public FraudEngineResult {
        requireIdentifier(engineId, "engineId");
        Objects.requireNonNull(engineType, "engineType is required");
        requireIdentifier(engineLanguage, "engineLanguage");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(confidence, "confidence is required");
        Objects.requireNonNull(generatedAt, "generatedAt is required");

        if (score != null && (!Double.isFinite(score) || score < 0.0d || score > 1.0d)) {
            throw new IllegalArgumentException("score must be null or between 0.0 and 1.0");
        }
        if (latencyMs != null && latencyMs < 0L) {
            throw new IllegalArgumentException("latencyMs must be null or non-negative");
        }

        reasonCodes = copyBoundedReasonCodes(reasonCodes);
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        validateOptionalIdentifier(modelName, "modelName");
        validateOptionalIdentifier(modelVersion, "modelVersion");
        if (fallbackReason != null
                && !fallbackReason.isEmpty()
                && !SAFE_FALLBACK_REASON.matcher(fallbackReason).matches()) {
            throw new IllegalArgumentException("fallbackReason must be a bounded reason code");
        }
    }

    private static List<String> copyBoundedReasonCodes(List<String> source) {
        if (source == null) {
            return List.of();
        }
        for (String reasonCode : source) {
            requireIdentifier(reasonCode, "reasonCode");
        }
        return List.copyOf(source);
    }

    private static void requireIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds the bounded contract length");
        }
    }

    private static void validateOptionalIdentifier(String value, String fieldName) {
        if (value != null && value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds the bounded contract length");
        }
    }
}
