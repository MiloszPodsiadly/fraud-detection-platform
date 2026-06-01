package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record NormalizedFraudEngineResult(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus status,
        Double score,
        RiskLevel riskLevel,
        FraudEngineConfidence confidence,
        List<String> reasonCodes,
        List<BoundedFraudEngineEvidenceSummary> evidence,
        List<BoundedFraudEngineContributionSummary> contributions,
        Long latencyMs
) {
    private static final Set<String> ALLOWED_ENGINE_IDS = Set.of("rules.primary", "ml.python.primary");
    private static final int MAX_REASON_CODES = 32;
    private static final int MAX_EVIDENCE_ITEMS = 16;
    private static final int MAX_CONTRIBUTIONS = 32;

    public NormalizedFraudEngineResult {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("AGGREGATION_ENGINE_ID_REQUIRED");
        }
        if (!ALLOWED_ENGINE_IDS.contains(engineId)) {
            throw new IllegalArgumentException("AGGREGATION_UNKNOWN_ENGINE_ID");
        }
        Objects.requireNonNull(engineType, "engineType is required");
        if (!hasExpectedEngineType(engineId, engineType)) {
            throw new IllegalArgumentException("AGGREGATION_ENGINE_TYPE_MISMATCH");
        }
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(confidence, "confidence is required");
        if (score != null && (!Double.isFinite(score) || score < 0.0d || score > 1.0d)) {
            throw new IllegalArgumentException("AGGREGATION_SCORE_OUT_OF_RANGE");
        }
        if (latencyMs != null && latencyMs < 0L) {
            throw new IllegalArgumentException("AGGREGATION_LATENCY_INVALID");
        }
        reasonCodes = copy(reasonCodes, "reasonCodes");
        evidence = copy(evidence, "evidence");
        contributions = copy(contributions, "contributions");
        requireSize(reasonCodes, MAX_REASON_CODES, "reasonCodes");
        requireSize(evidence, MAX_EVIDENCE_ITEMS, "evidence");
        requireSize(contributions, MAX_CONTRIBUTIONS, "contributions");
        FraudEngineReasonCodeNormalizer reasonCodeNormalizer = new FraudEngineReasonCodeNormalizer();
        if (reasonCodes.stream().anyMatch(reasonCode ->
                !FraudEngineAggregationSafety.isSafe(reasonCode) || !reasonCodeNormalizer.isAllowed(reasonCode))) {
            throw new IllegalArgumentException("AGGREGATION_UNNORMALIZED_REASON_CODE");
        }
    }

    private static <T> List<T> copy(List<T> source, String fieldName) {
        Objects.requireNonNull(source, fieldName + " is required");
        for (T item : source) {
            Objects.requireNonNull(item, fieldName + " must not contain null entries");
        }
        return List.copyOf(source);
    }

    private static boolean hasExpectedEngineType(String engineId, FraudEngineType engineType) {
        return switch (engineId) {
            case "rules.primary" -> engineType == FraudEngineType.RULES;
            case "ml.python.primary" -> engineType == FraudEngineType.ML_MODEL;
            default -> false;
        };
    }

    private static void requireSize(List<?> source, int maximum, String fieldName) {
        if (source.size() > maximum) {
            throw new IllegalArgumentException("AGGREGATION_" + fieldName.toUpperCase() + "_LIMIT_EXCEEDED");
        }
    }
}
