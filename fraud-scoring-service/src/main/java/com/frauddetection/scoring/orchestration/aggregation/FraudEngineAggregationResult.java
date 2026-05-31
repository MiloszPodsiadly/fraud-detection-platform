package com.frauddetection.scoring.orchestration.aggregation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FraudEngineAggregationResult(
        List<NormalizedFraudEngineResult> normalizedEngineResults,
        FraudEngineAgreementStatus agreementStatus,
        FraudEngineScoreDelta scoreDelta,
        FraudEngineRiskMismatch riskMismatch,
        List<FraudEngineStrongestSignal> strongestSignals,
        List<FraudEngineAggregationWarning> warnings,
        Instant generatedAt
) {
    private static final int MAX_NORMALIZED_ENGINE_RESULTS = 16;
    private static final int MAX_STRONGEST_SIGNALS = 16;
    private static final int MAX_WARNINGS = 64;

    public FraudEngineAggregationResult {
        normalizedEngineResults = copy(normalizedEngineResults, "normalizedEngineResults");
        Objects.requireNonNull(agreementStatus, "agreementStatus is required");
        Objects.requireNonNull(scoreDelta, "scoreDelta is required");
        Objects.requireNonNull(riskMismatch, "riskMismatch is required");
        strongestSignals = copy(strongestSignals, "strongestSignals");
        warnings = copy(warnings, "warnings");
        Objects.requireNonNull(generatedAt, "generatedAt is required");
        requireSize(normalizedEngineResults, MAX_NORMALIZED_ENGINE_RESULTS, "NORMALIZED_ENGINE_RESULTS");
        requireSize(strongestSignals, MAX_STRONGEST_SIGNALS, "STRONGEST_SIGNALS");
        requireSize(warnings, MAX_WARNINGS, "WARNINGS");
    }

    private static <T> List<T> copy(List<T> source, String fieldName) {
        Objects.requireNonNull(source, fieldName + " is required");
        for (T item : source) {
            Objects.requireNonNull(item, fieldName + " must not contain null entries");
        }
        return List.copyOf(source);
    }

    private static void requireSize(List<?> source, int maximum, String fieldName) {
        if (source.size() > maximum) {
            throw new IllegalArgumentException("AGGREGATION_" + fieldName + "_LIMIT_EXCEEDED");
        }
    }
}
