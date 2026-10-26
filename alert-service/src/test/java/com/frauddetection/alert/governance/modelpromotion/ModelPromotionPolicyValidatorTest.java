package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionPolicyValidatorTest {

    private final ModelPromotionPolicyValidator validator = new ModelPromotionPolicyValidator(new ObjectMapper());

    @Test
    void existingPolicyAndMathematicalBoundaryValuesRemainValid() {
        ModelPromotionPolicy originalPolicy = ModelPromotionAssessmentTestFixtures.policy();
        assertThatCode(() -> validate(originalPolicy)).doesNotThrowAnyException();

        ModelPromotionPolicy policy = withThreshold(originalPolicy, ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                "minimumEvaluatedRecords", BigDecimal.ONE);
        policy = withThreshold(policy, ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                "minimumPositiveLabels", BigDecimal.ONE);
        policy = withThreshold(policy, ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                "minimumNegativeLabels", BigDecimal.ONE);
        policy = withThreshold(policy, ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                "alertReviewBudget", BigDecimal.ONE);
        policy = withThreshold(policy, ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                "minimumMlPrecision", BigDecimal.ZERO);
        policy = withThreshold(policy, ModelPromotionCriterion.FALSE_POSITIVE_RATE,
                "maximumRate", BigDecimal.ONE);
        policy = withThreshold(policy, ModelPromotionCriterion.EXACT_MODEL_LATENCY_STABILITY,
                "maximumMilliseconds", new BigDecimal("0.001"));
        policy = withThreshold(policy, ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS,
                "maximumCriticalRegressions", BigDecimal.ZERO);
        ModelPromotionPolicy boundaryPolicy = policy;

        assertThatCode(() -> validate(boundaryPolicy)).doesNotThrowAnyException();
    }

    @Test
    void zeroAndNegativeMinimumsAndLatencyFailClosed() {
        ModelPromotionPolicy original = ModelPromotionAssessmentTestFixtures.policy();
        for (var entry : List.of(
                new Threshold(ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE, "minimumEvaluatedRecords"),
                new Threshold(ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE, "minimumPositiveLabels"),
                new Threshold(ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE, "minimumNegativeLabels"),
                new Threshold(ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET, "alertReviewBudget"),
                new Threshold(ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS, "minimumSegmentsEvaluated"),
                new Threshold(ModelPromotionCriterion.EXACT_MODEL_LATENCY_STABILITY, "minimumSampleSize"),
                new Threshold(ModelPromotionCriterion.EXACT_MODEL_FALLBACK_RATE, "minimumDenominator"),
                new Threshold(ModelPromotionCriterion.EXACT_MODEL_LATENCY_STABILITY, "maximumMilliseconds")
        )) {
            assertInvalid(withThreshold(original, entry.criterion(), entry.name(), BigDecimal.ZERO));
            assertInvalid(withThreshold(original, entry.criterion(), entry.name(), BigDecimal.ONE.negate()));
        }
    }

    @Test
    void invalidRatesCountsAndImpossibleClassPopulationFailClosed() {
        ModelPromotionPolicy original = ModelPromotionAssessmentTestFixtures.policy();
        assertInvalid(withThreshold(original, ModelPromotionCriterion.FALSE_POSITIVE_RATE,
                "maximumRate", new BigDecimal("1.01")));
        assertInvalid(withThreshold(original, ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                "minimumMlPrecision", new BigDecimal("1.01")));
        assertInvalid(withThreshold(original, ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                "minimumDeltaVsRules", new BigDecimal("1.01")));
        assertInvalid(withThreshold(original, ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS,
                "maximumCriticalRegressions", new BigDecimal("0.5")));
        assertInvalid(withThreshold(original, ModelPromotionCriterion.EXACT_MODEL_FALLBACK_RATE,
                "minimumDenominator", new BigDecimal("2147483648")));
        ModelPromotionPolicy impossible = withThreshold(original,
                ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                "minimumPositiveLabels", new BigDecimal(Integer.MAX_VALUE));
        assertInvalid(withThreshold(impossible, ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                "minimumNegativeLabels", BigDecimal.ONE));
    }

    private void validate(ModelPromotionPolicy policy) {
        validator.validate(policy, ModelPromotionAssessmentTestFixtures.scope(),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME);
    }

    private void assertInvalid(ModelPromotionPolicy policy) {
        assertThatThrownBy(() -> validate(policy))
                .isInstanceOf(ModelPromotionPolicyValidator.InvalidPolicyException.class)
                .satisfies(error -> assertThat(((ModelPromotionPolicyValidator.InvalidPolicyException) error).reason())
                        .isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_CONTRACT_INVALID));
    }

    private ModelPromotionPolicy withThreshold(
            ModelPromotionPolicy original,
            ModelPromotionCriterion criterion,
            String name,
            BigDecimal value
    ) {
        List<ModelPromotionPolicy.CriterionRule> rules = original.content().criteria().stream().map(rule -> {
            if (rule.criterion() != criterion) {
                return rule;
            }
            var thresholds = new HashMap<>(rule.thresholds());
            thresholds.put(name, value);
            return new ModelPromotionPolicy.CriterionRule(
                    rule.criterion(), rule.applicability(), thresholds, rule.maximumEvidenceAgeSeconds(),
                    rule.requiredVerificationLevel(), rule.evidenceSource(), rule.metricSemantics(),
                    rule.populationDefinition(), rule.denominatorDefinition(), rule.applicabilityJustification()
            );
        }).toList();
        ModelPromotionPolicy.Content content = original.content();
        ModelPromotionPolicy.Content revised = new ModelPromotionPolicy.Content(
                content.policyVersion(), content.policyId(), content.revision(), content.governanceScope(),
                content.intendedUse(), content.provenance(), content.effectiveFromInclusive(),
                content.effectiveToExclusive(), rules
        );
        return new ModelPromotionPolicy(revised, validator.canonicalSha256(revised));
    }

    private record Threshold(ModelPromotionCriterion criterion, String name) {
    }
}
