package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;

import java.util.Objects;
import java.util.Set;

public record BoundedFraudEngineEvidenceSummary(
        FraudEngineEvidenceType evidenceType,
        String reasonCode,
        String title,
        String description,
        String source,
        FraudEngineEvidenceStatus status
) {
    private static final Set<String> ALLOWED_SOURCES = Set.of("RULES", "ML_MODEL", "ORCHESTRATOR");
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 256;

    public BoundedFraudEngineEvidenceSummary {
        Objects.requireNonNull(evidenceType, "evidenceType is required");
        requireSafe(title, MAX_TITLE_LENGTH, "title");
        requireSafe(description, MAX_DESCRIPTION_LENGTH, "description");
        requireSafe(source, "source");
        if (reasonCode != null && !FraudEngineAggregationSafety.isSafe(reasonCode)) {
            throw new IllegalArgumentException("AGGREGATION_EVIDENCE_UNSAFE_REASON_CODE");
        }
        if (reasonCode != null && !new FraudEngineReasonCodeNormalizer().isAllowed(reasonCode)) {
            throw new IllegalArgumentException("AGGREGATION_EVIDENCE_UNNORMALIZED_REASON_CODE");
        }
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new IllegalArgumentException("AGGREGATION_EVIDENCE_UNKNOWN_SOURCE");
        }
        Objects.requireNonNull(status, "status is required");
    }

    private static void requireSafe(String value, int maximumLength, String fieldName) {
        requireSafe(value, fieldName);
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException("AGGREGATION_EVIDENCE_" + fieldName.toUpperCase() + "_TOO_LONG");
        }
    }

    private static void requireSafe(String value, String fieldName) {
        if (value == null || value.isBlank() || !FraudEngineAggregationSafety.isSafe(value)) {
            throw new IllegalArgumentException("AGGREGATION_EVIDENCE_UNSAFE_" + fieldName.toUpperCase());
        }
    }
}
