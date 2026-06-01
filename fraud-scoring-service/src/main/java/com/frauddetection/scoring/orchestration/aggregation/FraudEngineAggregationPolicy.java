package com.frauddetection.scoring.orchestration.aggregation;

public record FraudEngineAggregationPolicy(
        int maxEngineResults,
        int maxReasonCodesPerEngine,
        int maxEvidenceItemsPerEngine,
        int maxContributionsPerEngine,
        int maxStrongestSignals,
        int maxWarnings,
        int maxStringLength,
        int maxEvidenceTitleLength,
        int maxEvidenceDescriptionLength
) {
    private static final int MAX_ENGINE_RESULTS = 16;
    private static final int MAX_REASON_CODES_PER_ENGINE = 32;
    private static final int MAX_EVIDENCE_ITEMS_PER_ENGINE = 16;
    private static final int MAX_CONTRIBUTIONS_PER_ENGINE = 32;
    private static final int MAX_STRONGEST_SIGNALS = 16;
    private static final int MAX_WARNINGS = 64;
    private static final int MAX_STRING_LENGTH = 128;
    private static final int MAX_EVIDENCE_TITLE_LENGTH = 120;
    private static final int MAX_EVIDENCE_DESCRIPTION_LENGTH = 256;

    public FraudEngineAggregationPolicy {
        requireBounded(maxEngineResults, MAX_ENGINE_RESULTS, "maxEngineResults");
        requireBounded(maxReasonCodesPerEngine, MAX_REASON_CODES_PER_ENGINE, "maxReasonCodesPerEngine");
        requireBounded(maxEvidenceItemsPerEngine, MAX_EVIDENCE_ITEMS_PER_ENGINE, "maxEvidenceItemsPerEngine");
        requireBounded(maxContributionsPerEngine, MAX_CONTRIBUTIONS_PER_ENGINE, "maxContributionsPerEngine");
        requireBounded(maxStrongestSignals, MAX_STRONGEST_SIGNALS, "maxStrongestSignals");
        requireBounded(maxWarnings, MAX_WARNINGS, "maxWarnings");
        requireBounded(maxStringLength, MAX_STRING_LENGTH, "maxStringLength");
        requireBounded(maxEvidenceTitleLength, MAX_EVIDENCE_TITLE_LENGTH, "maxEvidenceTitleLength");
        requireBounded(maxEvidenceDescriptionLength, MAX_EVIDENCE_DESCRIPTION_LENGTH, "maxEvidenceDescriptionLength");
    }

    public static FraudEngineAggregationPolicy defaultInternalPolicy() {
        return new FraudEngineAggregationPolicy(2, 10, 5, 5, 5, 20, 128, 120, 256);
    }

    private static void requireBounded(int value, int maximum, String fieldName) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException("AGGREGATION_POLICY_INVALID_" + fieldName.toUpperCase());
        }
    }
}
