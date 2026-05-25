package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
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
    public static final int MAX_REASON_CODES = 32;
    public static final int MAX_CONTRIBUTIONS = 32;
    public static final int MAX_EVIDENCE = 16;

    private static final Pattern SAFE_FALLBACK_REASON = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Set<String> ENGINE_LANGUAGES = Set.of(
            "java",
            "python",
            "go",
            "kotlin",
            "scala",
            "javascript",
            "other"
    );

    public FraudEngineResult {
        FraudEngineValuePolicy.requireText(engineId, "engineId", FraudEngineValuePolicy.MAX_IDENTIFIER_LENGTH);
        Objects.requireNonNull(engineType, "engineType is required");
        validateEngineLanguage(engineLanguage);
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
        contributions = copyBoundedList(contributions, MAX_CONTRIBUTIONS, "contributions");
        evidence = copyBoundedList(evidence, MAX_EVIDENCE, "evidence");
        FraudEngineValuePolicy.validateOptionalText(modelName, "modelName", FraudEngineValuePolicy.MAX_IDENTIFIER_LENGTH);
        FraudEngineValuePolicy.validateOptionalText(modelVersion, "modelVersion", FraudEngineValuePolicy.MAX_IDENTIFIER_LENGTH);
        if (fallbackReason != null && !SAFE_FALLBACK_REASON.matcher(fallbackReason).matches()) {
            throw new IllegalArgumentException("fallbackReason must be a bounded reason code");
        }
        validateStatusSemantics(status, score, riskLevel, confidence, fallbackReason);
    }

    private static List<String> copyBoundedReasonCodes(List<String> source) {
        if (source == null) {
            return List.of();
        }
        validateListSize(source, MAX_REASON_CODES, "reasonCodes");
        for (String reasonCode : source) {
            FraudEngineValuePolicy.requireReasonCode(reasonCode, "reasonCode");
        }
        return List.copyOf(source);
    }

    private static <T> List<T> copyBoundedList(List<T> source, int maxSize, String fieldName) {
        if (source == null) {
            return List.of();
        }
        validateListSize(source, maxSize, fieldName);
        return List.copyOf(source);
    }

    private static void validateListSize(List<?> source, int maxSize, String fieldName) {
        if (source.size() > maxSize) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum collection size of " + maxSize);
        }
    }

    private static void validateEngineLanguage(String engineLanguage) {
        if (!ENGINE_LANGUAGES.contains(engineLanguage)) {
            throw new IllegalArgumentException("engineLanguage must be one of the canonical lowercase values");
        }
    }

    private static void validateStatusSemantics(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            String fallbackReason
    ) {
        switch (status) {
            case AVAILABLE -> {
                requireScoreAndRiskLevel(score, riskLevel, status);
                if (confidence == FraudEngineConfidence.UNKNOWN) {
                    throw new IllegalArgumentException("AVAILABLE status must declare known confidence");
                }
                if (fallbackReason != null) {
                    throw new IllegalArgumentException("AVAILABLE status must not declare fallbackReason");
                }
            }
            case UNAVAILABLE, TIMEOUT, SKIPPED -> {
                requireAbsentScoreAndRiskLevel(score, riskLevel, status);
                if (confidence != FraudEngineConfidence.UNKNOWN) {
                    throw new IllegalArgumentException(status + " status must declare UNKNOWN confidence");
                }
                requireFallbackReason(fallbackReason, status);
            }
            case DEGRADED -> {
                requirePairedScoreAndRiskLevel(score, riskLevel, status);
                if (confidence == FraudEngineConfidence.HIGH) {
                    throw new IllegalArgumentException("DEGRADED status must not declare HIGH confidence");
                }
                requireFallbackReason(fallbackReason, status);
            }
            case FALLBACK_USED -> {
                requirePairedScoreAndRiskLevel(score, riskLevel, status);
                if (confidence == FraudEngineConfidence.HIGH) {
                    throw new IllegalArgumentException("FALLBACK_USED status must not declare HIGH confidence");
                }
                requireFallbackReason(fallbackReason, status);
            }
        }
    }

    private static void requireScoreAndRiskLevel(Double score, RiskLevel riskLevel, FraudEngineStatus status) {
        if (score == null) {
            throw new IllegalArgumentException(status + " status requires score");
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException(status + " status requires riskLevel");
        }
    }

    private static void requireAbsentScoreAndRiskLevel(Double score, RiskLevel riskLevel, FraudEngineStatus status) {
        if (score != null) {
            throw new IllegalArgumentException(status + " status must not declare score");
        }
        if (riskLevel != null) {
            throw new IllegalArgumentException(status + " status must not declare riskLevel");
        }
    }

    private static void requirePairedScoreAndRiskLevel(Double score, RiskLevel riskLevel, FraudEngineStatus status) {
        if ((score == null) != (riskLevel == null)) {
            throw new IllegalArgumentException(status + " status requires score and riskLevel to be provided together");
        }
    }

    private static void requireFallbackReason(String fallbackReason, FraudEngineStatus status) {
        if (fallbackReason == null) {
            throw new IllegalArgumentException(status + " status requires fallbackReason");
        }
    }
}
