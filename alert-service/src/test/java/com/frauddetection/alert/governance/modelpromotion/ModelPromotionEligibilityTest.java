package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPromotionEligibilityTest {

    @Test
    void shadowRecommendationAndChampionRequireIncreasingEvidence() {
        ModelPromotionAssessment complete = ModelPromotionAssessmentTestFixtures.service(
                (scope, use, at) -> Optional.of(ModelPromotionAssessmentTestFixtures.policy())
        ).assess(ModelPromotionAssessmentTestFixtures.completeEvidence(),
                ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME);
        List<ModelPromotionAssessment.CriterionAssessment> criteria = complete.content().criteria();
        assertThat(ModelPromotionEligibility.passed(ModelPromotionLifecycleState.CHAMPION, criteria)).isTrue();

        List<ModelPromotionAssessment.CriterionAssessment> missingOperational = replace(
                replace(criteria, ModelPromotionCriterion.EXACT_MODEL_LATENCY_STABILITY),
                ModelPromotionCriterion.EXACT_MODEL_FALLBACK_RATE
        );
        assertThat(ModelPromotionEligibility.passed(ModelPromotionLifecycleState.SHADOW_APPROVED, missingOperational))
                .isTrue();
        assertThat(ModelPromotionEligibility.passed(ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, missingOperational))
                .isTrue();
        assertThat(ModelPromotionEligibility.passed(ModelPromotionLifecycleState.CHAMPION, missingOperational))
                .isFalse();

        for (ModelPromotionCriterion criterion : List.of(
                ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS,
                ModelPromotionCriterion.FALSE_POSITIVE_RATE,
                ModelPromotionCriterion.RULES_VS_ML_DISAGREEMENT
        )) {
            assertThat(ModelPromotionEligibility.passed(
                    ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, replace(criteria, criterion)
            )).isFalse();
        }
        for (ModelPromotionCriterion criterion : List.of(
                ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                ModelPromotionCriterion.COMPLETE_EVALUATION_PROVENANCE,
                ModelPromotionCriterion.EXACT_SHA_MODEL_CARD,
                ModelPromotionCriterion.IDENTITY_AND_LINEAGE_INTEGRITY
        )) {
            assertThat(ModelPromotionEligibility.passed(
                    ModelPromotionLifecycleState.SHADOW_APPROVED, replace(criteria, criterion)
            )).isFalse();
        }
    }

    private List<ModelPromotionAssessment.CriterionAssessment> replace(
            List<ModelPromotionAssessment.CriterionAssessment> source,
            ModelPromotionCriterion criterion
    ) {
        List<ModelPromotionAssessment.CriterionAssessment> result = new ArrayList<>(source);
        for (int index = 0; index < result.size(); index++) {
            ModelPromotionAssessment.CriterionAssessment original = result.get(index);
            if (original.criterion() == criterion) {
                result.set(index, new ModelPromotionAssessment.CriterionAssessment(
                        criterion,
                        ModelPromotionAssessment.CriterionOutcome.NOT_APPLICABLE,
                        "POLICY_EXCEPTION",
                        original.observedValues(),
                        original.evidenceSource(),
                        original.metricSemantics(),
                        original.populationDefinition(),
                        original.denominatorDefinition(),
                        original.requiredVerificationLevel(),
                        original.maximumEvidenceAgeSeconds()
                ));
                return result;
            }
        }
        throw new AssertionError("Missing criterion " + criterion);
    }
}
