package com.frauddetection.alert.governance.modelpromotion;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class ModelPromotionEligibility {

    private static final Set<ModelPromotionCriterion> SHADOW = EnumSet.of(
            ModelPromotionCriterion.COMPLETE_EVALUATION_PROVENANCE,
            ModelPromotionCriterion.EXACT_SHA_MODEL_CARD,
            ModelPromotionCriterion.IDENTITY_AND_LINEAGE_INTEGRITY,
            ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE
    );
    private static final Set<ModelPromotionCriterion> RECOMMENDATION = EnumSet.complementOf(EnumSet.of(
            ModelPromotionCriterion.EXACT_MODEL_LATENCY_STABILITY,
            ModelPromotionCriterion.EXACT_MODEL_FALLBACK_RATE
    ));
    private static final Set<ModelPromotionCriterion> CHAMPION = EnumSet.allOf(ModelPromotionCriterion.class);

    private ModelPromotionEligibility() {
    }

    static Set<ModelPromotionCriterion> requiredFor(ModelPromotionLifecycleState target) {
        return switch (target) {
            case SHADOW_APPROVED -> SHADOW;
            case PROMOTION_RECOMMENDED -> RECOMMENDATION;
            case CHAMPION -> CHAMPION;
            default -> Set.of();
        };
    }

    static boolean passed(
            ModelPromotionLifecycleState target,
            List<ModelPromotionAssessment.CriterionAssessment> assessments
    ) {
        EnumMap<ModelPromotionCriterion, ModelPromotionAssessment.CriterionOutcome> outcomes =
                new EnumMap<>(ModelPromotionCriterion.class);
        for (ModelPromotionAssessment.CriterionAssessment assessment : assessments) {
            if (outcomes.put(assessment.criterion(), assessment.outcome()) != null) {
                return false;
            }
        }
        return outcomes.size() == ModelPromotionCriterion.values().length
                && requiredFor(target).stream().allMatch(criterion -> outcomes.get(criterion)
                        == ModelPromotionAssessment.CriterionOutcome.PASS);
    }
}
