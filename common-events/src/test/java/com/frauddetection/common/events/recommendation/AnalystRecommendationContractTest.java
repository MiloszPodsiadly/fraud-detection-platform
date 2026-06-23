package com.frauddetection.common.events.recommendation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalystRecommendationContractTest {

    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void unavailableShapeContainsNoRecommendationAndKeepsAdvisoryFlags() throws Exception {
        String json = objectMapper.writeValueAsString(AnalystRecommendationResult.unavailable());

        assertThat(json).contains(
                "\"status\":\"UNAVAILABLE\"",
                "\"recommendation\":null",
                "\"confidence\":\"UNKNOWN\"",
                "\"source\":\"ENGINE_INTELLIGENCE_UNAVAILABLE\"",
                "\"reasonCodes\":[]",
                "\"warnings\":[]",
                "\"notPaymentAuthorization\":true",
                "\"notAutomaticDecisioning\":true",
                "\"notCaseAction\":true",
                "\"notWorkflowAction\":true",
                "\"notModelPromotion\":true",
                "\"notThresholdRecommendation\":true"
        );
    }

    @Test
    void absentShapeDoesNotMeanNoAction() {
        var absent = AnalystRecommendationResult.absent();

        assertThat(absent.status()).isEqualTo(AnalystRecommendationStatus.ABSENT);
        assertThat(absent.recommendation()).isNull();
    }

    @Test
    void reasonCodesAndWarningsAreBounded() {
        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.RULES_RISK,
                List.of("R1", "R2", "R3", "R4", "R5", "R6"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_REASONCODES_LIMIT_EXCEEDED");

        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.RULES_RISK,
                List.of("RULES_HIGH_RISK"),
                java.util.stream.IntStream.range(0, 11)
                        .mapToObj(index -> new AnalystRecommendationWarning("WARNING_" + index, 1))
                        .toList(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_WARNINGS_LIMIT_EXCEEDED");
    }

    @Test
    void availableAndDegradedRecommendationsRequireReasonCodes() {
        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.RULES_RISK,
                List.of(),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_AVAILABLE_REASON_REQUIRED");

        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.DEGRADED,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_DEGRADED,
                List.of(),
                List.of(new AnalystRecommendationWarning("ENGINE_INTELLIGENCE_DEGRADED", 1)),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_AVAILABLE_REASON_REQUIRED");
    }

    @Test
    void unavailableStatusesRejectRecommendationValues() {
        assertUnavailableStatusRejectsRecommendation(AnalystRecommendationStatus.UNAVAILABLE);
        assertUnavailableStatusRejectsRecommendation(AnalystRecommendationStatus.ABSENT);
        assertUnavailableStatusRejectsRecommendation(AnalystRecommendationStatus.INSUFFICIENT_DATA);
        assertUnavailableStatusRejectsRecommendation(AnalystRecommendationStatus.NOT_APPLICABLE);
    }

    @Test
    void decisioningFlagsMustAllRemainTrue() {
        assertThatThrownBy(() -> new AnalystRecommendationNonDecisioning(true, true, false, true, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_DECISIONING_FLAG_INVALID");
    }

    @Test
    void rawOrDecisioningCodesAreRejected() {
        assertThatThrownBy(() -> new AnalystRecommendationWarning("PAYMENT_AUTHORIZATION", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_WARNING_CODE_INVALID");
    }

    private void assertUnavailableStatusRejectsRecommendation(AnalystRecommendationStatus status) {
        assertThatThrownBy(() -> new AnalystRecommendationResult(
                status,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_UNAVAILABLE,
                List.of("REASON_PRESENT"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_UNAVAILABLE_RECOMMENDATION_INVALID");
    }
}
