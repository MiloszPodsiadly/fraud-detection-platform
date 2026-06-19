package com.frauddetection.common.events.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalystRecommendationResult(
        AnalystRecommendationStatus status,
        AnalystRecommendation recommendation,
        AnalystRecommendationConfidence confidence,
        AnalystRecommendationSource source,
        List<String> reasonCodes,
        List<AnalystRecommendationWarning> warnings,
        AnalystRecommendationNonDecisioning nonDecisioning
) {
    public static final int MAX_REASON_CODES = 5;
    public static final int MAX_WARNINGS = 10;

    public AnalystRecommendationResult {
        Objects.requireNonNull(status, "status is required");
        confidence = confidence == null ? AnalystRecommendationConfidence.UNKNOWN : confidence;
        reasonCodes = AnalystRecommendationValuePolicy.copyBounded(reasonCodes, MAX_REASON_CODES, "reasonCodes");
        warnings = AnalystRecommendationValuePolicy.copyBounded(warnings, MAX_WARNINGS, "warnings");
        reasonCodes.forEach(reasonCode ->
                AnalystRecommendationValuePolicy.requireBoundedCode(
                        reasonCode,
                        "ANALYST_RECOMMENDATION_REASON_CODE_INVALID"
                )
        );
        nonDecisioning = nonDecisioning == null
                ? AnalystRecommendationNonDecisioning.advisoryOnly()
                : nonDecisioning;
        if (recommendation == null && (status == AnalystRecommendationStatus.AVAILABLE
                || status == AnalystRecommendationStatus.DEGRADED)) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_AVAILABLE_RECOMMENDATION_REQUIRED");
        }
        if (recommendation != null && (status == AnalystRecommendationStatus.ABSENT
                || status == AnalystRecommendationStatus.NOT_APPLICABLE
                || status == AnalystRecommendationStatus.INSUFFICIENT_DATA
                || status == AnalystRecommendationStatus.UNAVAILABLE)) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_UNAVAILABLE_RECOMMENDATION_INVALID");
        }
    }

    public static AnalystRecommendationResult absent() {
        return unavailableShape(AnalystRecommendationStatus.ABSENT, AnalystRecommendationSource.ENGINE_INTELLIGENCE_ABSENT);
    }

    public static AnalystRecommendationResult insufficientData(String reasonCode) {
        return new AnalystRecommendationResult(
                AnalystRecommendationStatus.INSUFFICIENT_DATA,
                null,
                AnalystRecommendationConfidence.UNKNOWN,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_ABSENT,
                List.of(reasonCode),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }

    public static AnalystRecommendationResult unavailable() {
        return unavailableShape(
                AnalystRecommendationStatus.UNAVAILABLE,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_UNAVAILABLE
        );
    }

    public static AnalystRecommendationResult notApplicable(String reasonCode) {
        return new AnalystRecommendationResult(
                AnalystRecommendationStatus.NOT_APPLICABLE,
                null,
                AnalystRecommendationConfidence.UNKNOWN,
                AnalystRecommendationSource.NOT_APPLICABLE,
                List.of(reasonCode),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }

    private static AnalystRecommendationResult unavailableShape(
            AnalystRecommendationStatus status,
            AnalystRecommendationSource source
    ) {
        return new AnalystRecommendationResult(
                status,
                null,
                AnalystRecommendationConfidence.UNKNOWN,
                source,
                List.of(),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }
}
