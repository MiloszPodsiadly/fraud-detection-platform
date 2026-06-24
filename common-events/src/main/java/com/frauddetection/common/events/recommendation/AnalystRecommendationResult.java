package com.frauddetection.common.events.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalystRecommendationResult(
        AnalystRecommendationStatus status,
        AnalystRecommendation recommendation,
        String recommendationVersion,
        Instant generatedAt,
        AnalystRecommendationConfidence confidence,
        AnalystRecommendationSource source,
        List<String> reasonCodes,
        List<AnalystRecommendationWarning> warnings,
        AnalystRecommendationNonDecisioning nonDecisioning
) {
    public static final String RECOMMENDATION_VERSION = "analyst-recommendation-v1";
    public static final int MAX_REASON_CODES = 5;
    public static final int MAX_WARNINGS = 10;

    public AnalystRecommendationResult {
        Objects.requireNonNull(status, "status is required");
        recommendationVersion = requireVersion(recommendationVersion);
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt is required");
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
        if (source == null && (status == AnalystRecommendationStatus.AVAILABLE
                || status == AnalystRecommendationStatus.DEGRADED)) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_AVAILABLE_SOURCE_REQUIRED");
        }
        Objects.requireNonNull(source, "source is required");
        if (reasonCodes.isEmpty() && (status == AnalystRecommendationStatus.AVAILABLE
                || status == AnalystRecommendationStatus.DEGRADED)) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_AVAILABLE_REASON_REQUIRED");
        }
        if (recommendation != null && (status == AnalystRecommendationStatus.ABSENT
                || status == AnalystRecommendationStatus.NOT_APPLICABLE
                || status == AnalystRecommendationStatus.INSUFFICIENT_DATA
                || status == AnalystRecommendationStatus.UNAVAILABLE)) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_UNAVAILABLE_RECOMMENDATION_INVALID");
        }
    }

    public static AnalystRecommendationResult absent() {
        return absent(Instant.EPOCH);
    }

    public static AnalystRecommendationResult absent(Instant generatedAt) {
        return unavailableShape(
                AnalystRecommendationStatus.ABSENT,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_ABSENT,
                generatedAt
        );
    }

    public static AnalystRecommendationResult insufficientData(String reasonCode) {
        return insufficientData(reasonCode, Instant.EPOCH);
    }

    public static AnalystRecommendationResult insufficientData(String reasonCode, Instant generatedAt) {
        return new AnalystRecommendationResult(
                AnalystRecommendationStatus.INSUFFICIENT_DATA,
                null,
                RECOMMENDATION_VERSION,
                generatedAt,
                AnalystRecommendationConfidence.UNKNOWN,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_ABSENT,
                List.of(reasonCode),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }

    public static AnalystRecommendationResult unavailable() {
        return unavailable(Instant.EPOCH);
    }

    public static AnalystRecommendationResult unavailable(Instant generatedAt) {
        return unavailableShape(
                AnalystRecommendationStatus.UNAVAILABLE,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_UNAVAILABLE,
                generatedAt
        );
    }

    public static AnalystRecommendationResult notApplicable(String reasonCode) {
        return notApplicable(reasonCode, Instant.EPOCH);
    }

    public static AnalystRecommendationResult notApplicable(String reasonCode, Instant generatedAt) {
        return new AnalystRecommendationResult(
                AnalystRecommendationStatus.NOT_APPLICABLE,
                null,
                RECOMMENDATION_VERSION,
                generatedAt,
                AnalystRecommendationConfidence.UNKNOWN,
                AnalystRecommendationSource.NOT_APPLICABLE,
                List.of(reasonCode),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }

    private static AnalystRecommendationResult unavailableShape(
            AnalystRecommendationStatus status,
            AnalystRecommendationSource source,
            Instant generatedAt
    ) {
        return new AnalystRecommendationResult(
                status,
                null,
                RECOMMENDATION_VERSION,
                generatedAt,
                AnalystRecommendationConfidence.UNKNOWN,
                source,
                List.of(),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        );
    }

    private static String requireVersion(String recommendationVersion) {
        if (recommendationVersion == null || recommendationVersion.isBlank()) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_VERSION_REQUIRED");
        }
        return recommendationVersion;
    }
}
