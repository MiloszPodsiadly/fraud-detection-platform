package com.frauddetection.common.events.recommendation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalystRecommendationContractTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-06-19T10:00:00Z");

    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void unavailableShapeContainsNoRecommendationAndKeepsAdvisoryFlags() throws Exception {
        String json = objectMapper.writeValueAsString(AnalystRecommendationResult.unavailable());

        assertThat(json).contains(
                "\"status\":\"UNAVAILABLE\"",
                "\"recommendation\":null",
                "\"recommendationVersion\":\"analyst-recommendation-v1\"",
                "\"generatedAt\":null",
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
        assertThat(absent.generatedAt()).isNull();
    }

    @Test
    void nonGeneratedFactoryShapesDoNotExposeFakeGeneratedAt() {
        assertThat(AnalystRecommendationResult.absent().generatedAt()).isNull();
        assertThat(AnalystRecommendationResult.unavailable().generatedAt()).isNull();
        assertThat(AnalystRecommendationResult.insufficientData("ENGINE_INTELLIGENCE_NO_ENGINES").generatedAt())
                .isNull();
        assertThat(AnalystRecommendationResult.notApplicable("ENGINE_INTELLIGENCE_NO_COMPARABLE_ENGINES").generatedAt())
                .isNull();
    }

    @Test
    void timestampedFactoryShapesKeepRealGeneratedAt() {
        assertThat(AnalystRecommendationResult.absent(GENERATED_AT).generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(AnalystRecommendationResult.unavailable(GENERATED_AT).generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(AnalystRecommendationResult.insufficientData(
                "ENGINE_INTELLIGENCE_NO_ENGINES",
                GENERATED_AT
        ).generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(AnalystRecommendationResult.notApplicable(
                "ENGINE_INTELLIGENCE_NO_COMPARABLE_ENGINES",
                GENERATED_AT
        ).generatedAt()).isEqualTo(GENERATED_AT);
    }

    @Test
    void reasonCodesAndWarningsAreBounded() {
        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                GENERATED_AT,
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
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                GENERATED_AT,
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
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                GENERATED_AT,
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
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                GENERATED_AT,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_DEGRADED,
                List.of(),
                List.of(new AnalystRecommendationWarning("ENGINE_INTELLIGENCE_DEGRADED", 1)),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_AVAILABLE_REASON_REQUIRED");
    }

    @Test
    void availableAndDegradedRecommendationsRequireSource() {
        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                GENERATED_AT,
                AnalystRecommendationConfidence.LOW,
                null,
                List.of("RULES_HIGH_RISK"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_AVAILABLE_SOURCE_REQUIRED");

        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.DEGRADED,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                GENERATED_AT,
                AnalystRecommendationConfidence.LOW,
                null,
                List.of("RULES_HIGH_RISK"),
                List.of(new AnalystRecommendationWarning("ENGINE_INTELLIGENCE_DEGRADED", 1)),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_AVAILABLE_SOURCE_REQUIRED");
    }

    @Test
    void recommendationsRequireVersionAndAvailableGeneratedAt() {
        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                " ",
                GENERATED_AT,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.RULES_RISK,
                List.of("RULES_HIGH_RISK"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_VERSION_REQUIRED");

        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.AVAILABLE,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                null,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.RULES_RISK,
                List.of("RULES_HIGH_RISK"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_AVAILABLE_GENERATED_AT_REQUIRED");

        assertThatThrownBy(() -> new AnalystRecommendationResult(
                AnalystRecommendationStatus.DEGRADED,
                AnalystRecommendation.RECOMMEND_REVIEW,
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                null,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_DEGRADED,
                List.of("RULES_HIGH_RISK"),
                List.of(new AnalystRecommendationWarning("ENGINE_INTELLIGENCE_DEGRADED", 1)),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_AVAILABLE_GENERATED_AT_REQUIRED");
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
                AnalystRecommendationResult.RECOMMENDATION_VERSION,
                GENERATED_AT,
                AnalystRecommendationConfidence.LOW,
                AnalystRecommendationSource.ENGINE_INTELLIGENCE_UNAVAILABLE,
                List.of("REASON_PRESENT"),
                List.of(),
                AnalystRecommendationNonDecisioning.advisoryOnly()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ANALYST_RECOMMENDATION_UNAVAILABLE_RECOMMENDATION_INVALID");
    }
}
