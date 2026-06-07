package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.frauddetection.common.events.enums.RiskLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        @JsonAlias("fallbackReason") String statusReason,
        Instant generatedAt
) {
    public static final int REASON_CODES_MAX_SIZE = 10;
    public static final int CONTRIBUTIONS_MAX_SIZE = 10;
    public static final int EVIDENCE_MAX_SIZE = 10;
    public static final int LATENCY_MS_MAX = 300_000;
    public static final int SCORE_SCALE_MAX = 4;

    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_SCORE = BigDecimal.ONE;
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
        engineId = FraudEngineValuePolicy.requireSafeIdentifier(
                engineId,
                "engineId",
                FraudEngineValuePolicy.ENGINE_ID_MAX_LENGTH
        );
        Objects.requireNonNull(engineType, "engineType is required");
        engineLanguage = validateEngineLanguage(engineLanguage);
        Objects.requireNonNull(status, "status is required");
        confidence = normalizeConfidenceForStatus(status, confidence);
        Objects.requireNonNull(generatedAt, "generatedAt is required");

        if (score != null) {
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("score must be finite when present");
            }
            BigDecimal decimalScore = BigDecimal.valueOf(score);
            if (decimalScore.compareTo(MIN_SCORE) < 0 || decimalScore.compareTo(MAX_SCORE) > 0) {
                throw new IllegalArgumentException("score must be null or between 0.0000 and 1.0000");
            }
            double rounded = Math.rint(score * 10_000.0d) / 10_000.0d;
            if (Math.abs(score - rounded) > 0.000000001d) {
                throw new IllegalArgumentException("score scale must be less than or equal to 4");
            }
        }
        if (latencyMs != null && (latencyMs < 0L || latencyMs > LATENCY_MS_MAX)) {
            throw new IllegalArgumentException("latencyMs must be null or between 0 and 300000");
        }

        reasonCodes = copyBoundedReasonCodes(reasonCodes);
        contributions = copyBoundedContributions(contributions);
        evidence = copyBoundedEvidence(evidence);
        modelName = FraudEngineValuePolicy.optionalSafeIdentifier(
                modelName,
                "modelName",
                FraudEngineValuePolicy.MODEL_NAME_MAX_LENGTH
        );
        modelVersion = FraudEngineValuePolicy.optionalSafeIdentifier(
                modelVersion,
                "modelVersion",
                FraudEngineValuePolicy.MODEL_VERSION_MAX_LENGTH
        );
        statusReason = FraudEngineValuePolicy.optionalMachineCode(
                statusReason,
                "statusReason",
                FraudEngineValuePolicy.FALLBACK_REASON_MAX_LENGTH
        );
        validateStatusSemantics(status, score, riskLevel, confidence, statusReason);
    }

    @JsonIgnore
    public String fallbackReason() {
        return statusReason;
    }

    private static List<String> copyBoundedReasonCodes(List<String> source) {
        if (source == null) {
            return List.of();
        }
        validateListSize(source, REASON_CODES_MAX_SIZE, "reasonCodes");
        for (String reasonCode : source) {
            FraudEngineValuePolicy.requireMachineCode(
                    reasonCode,
                    "reasonCode",
                    FraudEngineValuePolicy.REASON_CODE_MAX_LENGTH
            );
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

    private static List<FraudEngineContribution> copyBoundedContributions(List<FraudEngineContribution> source) {
        List<FraudEngineContribution> copy = copyBoundedList(source, CONTRIBUTIONS_MAX_SIZE, "contributions");
        for (FraudEngineContribution contribution : copy) {
            Objects.requireNonNull(contribution, "contribution is required");
            FraudEngineValuePolicy.requireMachineCode(
                    contribution.feature(),
                    "feature",
                    FraudEngineValuePolicy.FEATURE_CODE_MAX_LENGTH
            );
            FraudEngineValuePolicy.validateOptionalSafeSummary(
                    contribution.value(),
                    "value",
                    FraudEngineValuePolicy.VALUE_BUCKET_MAX_LENGTH
            );
            Objects.requireNonNull(contribution.direction(), "direction is required");
        }
        return copy;
    }

    private static List<FraudEngineEvidence> copyBoundedEvidence(List<FraudEngineEvidence> source) {
        List<FraudEngineEvidence> copy = copyBoundedList(source, EVIDENCE_MAX_SIZE, "evidence");
        for (FraudEngineEvidence evidenceItem : copy) {
            Objects.requireNonNull(evidenceItem, "evidence item is required");
            Objects.requireNonNull(evidenceItem.evidenceType(), "evidenceType is required");
            FraudEngineValuePolicy.optionalMachineCode(
                    evidenceItem.reasonCode(),
                    "reasonCode",
                    FraudEngineValuePolicy.EVIDENCE_CODE_MAX_LENGTH
            );
            FraudEngineValuePolicy.requireSafeSummary(
                    evidenceItem.title(),
                    "title",
                    FraudEngineValuePolicy.DESCRIPTION_CODE_MAX_LENGTH
            );
            FraudEngineValuePolicy.validateOptionalSafeSummary(
                    evidenceItem.description(),
                    "description",
                    FraudEngineValuePolicy.DESCRIPTION_CODE_MAX_LENGTH
            );
            FraudEngineValuePolicy.requireMachineCode(
                    evidenceItem.source(),
                    "source",
                    FraudEngineValuePolicy.EVIDENCE_CODE_MAX_LENGTH
            );
            Objects.requireNonNull(evidenceItem.status(), "status is required");
        }
        return copy;
    }

    private static void validateListSize(List<?> source, int maxSize, String fieldName) {
        if (source.size() > maxSize) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum collection size of " + maxSize);
        }
    }

    private static String validateEngineLanguage(String engineLanguage) {
        String normalized = FraudEngineValuePolicy.requireSafeIdentifier(
                engineLanguage,
                "engineLanguage",
                FraudEngineValuePolicy.ENGINE_LANGUAGE_MAX_LENGTH
        );
        if (!ENGINE_LANGUAGES.contains(normalized)) {
            throw new IllegalArgumentException("engineLanguage must be one of the canonical lowercase values");
        }
        return normalized;
    }

    private static void validateStatusSemantics(
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            FraudEngineConfidence confidence,
            String statusReason
    ) {
        switch (status) {
            case AVAILABLE -> {
                requireScoreAndRiskLevel(score, riskLevel, status);
                requireKnownConfidence(confidence, status);
                if (statusReason != null) {
                    throw new IllegalArgumentException("AVAILABLE status must not declare statusReason");
                }
            }
            case UNAVAILABLE, TIMEOUT, SKIPPED -> {
                requireAbsentScoreAndRiskLevel(score, riskLevel, status);
                if (confidence != FraudEngineConfidence.UNKNOWN) {
                    throw new IllegalArgumentException(status + " status must declare UNKNOWN confidence");
                }
                requireStatusReason(statusReason, status);
            }
            case DEGRADED -> {
                requirePairedScoreAndRiskLevel(score, riskLevel, status);
                if (confidence == FraudEngineConfidence.HIGH) {
                    throw new IllegalArgumentException("DEGRADED status must not declare HIGH confidence");
                }
                requireStatusReason(statusReason, status);
            }
            case FALLBACK_USED -> {
                requirePairedScoreAndRiskLevel(score, riskLevel, status);
                if (confidence == FraudEngineConfidence.HIGH) {
                    throw new IllegalArgumentException("FALLBACK_USED status must not declare HIGH confidence");
                }
                requireStatusReason(statusReason, status);
            }
        }
    }

    private static FraudEngineConfidence normalizeConfidenceForStatus(
            FraudEngineStatus status,
            FraudEngineConfidence confidence
    ) {
        if (status == FraudEngineStatus.AVAILABLE) {
            return confidence;
        }
        return confidence == null ? FraudEngineConfidence.UNKNOWN : confidence;
    }

    private static void requireKnownConfidence(FraudEngineConfidence confidence, FraudEngineStatus status) {
        if (confidence == null) {
            throw new IllegalArgumentException(status + " status requires confidence");
        }
        if (confidence == FraudEngineConfidence.UNKNOWN) {
            throw new IllegalArgumentException(status + " status requires known confidence");
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

    private static void requireStatusReason(String statusReason, FraudEngineStatus status) {
        if (statusReason == null) {
            throw new IllegalArgumentException(status + " status requires statusReason");
        }
    }
}
