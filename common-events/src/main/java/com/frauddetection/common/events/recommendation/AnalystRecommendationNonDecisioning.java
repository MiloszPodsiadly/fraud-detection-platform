package com.frauddetection.common.events.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalystRecommendationNonDecisioning(
        boolean notPaymentAuthorization,
        boolean notAutomaticDecisioning,
        boolean notCaseAction,
        boolean notWorkflowAction,
        boolean notModelPromotion,
        boolean notThresholdRecommendation
) {
    private static final AnalystRecommendationNonDecisioning ADVISORY_ONLY =
            new AnalystRecommendationNonDecisioning(true, true, true, true, true, true);

    public AnalystRecommendationNonDecisioning {
        if (!notPaymentAuthorization
                || !notAutomaticDecisioning
                || !notCaseAction
                || !notWorkflowAction
                || !notModelPromotion
                || !notThresholdRecommendation) {
            throw new IllegalArgumentException("ANALYST_RECOMMENDATION_DECISIONING_FLAG_INVALID");
        }
    }

    public static AnalystRecommendationNonDecisioning advisoryOnly() {
        return ADVISORY_ONLY;
    }
}
