package com.frauddetection.common.events.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalystRecommendationWarning(
        String warningCode,
        int count
) {
    public AnalystRecommendationWarning {
        AnalystRecommendationValuePolicy.requireBoundedCode(warningCode, "ANALYST_RECOMMENDATION_WARNING_CODE_INVALID");
        if (count < 0) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_WARNING_COUNT_NEGATIVE");
        }
    }
}
