package com.frauddetection.scoring.orchestration.aggregation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class FraudEngineAggregationWarningSummarizer {
    private FraudEngineAggregationWarningSummarizer() {
    }

    static List<FraudEngineAggregationWarningSummary> summarize(List<FraudEngineAggregationWarning> warnings) {
        Objects.requireNonNull(warnings, "warnings is required");
        Map<FraudEngineAggregationWarningCode, Integer> counts =
                new EnumMap<>(FraudEngineAggregationWarningCode.class);
        for (FraudEngineAggregationWarning warning : warnings) {
            Objects.requireNonNull(warning, "warnings must not contain null entries");
            counts.merge(warning.code(), 1, Math::addExact);
        }
        return counts.entrySet().stream()
                .map(entry -> new FraudEngineAggregationWarningSummary(entry.getKey(), entry.getValue()))
                .toList();
    }
}
