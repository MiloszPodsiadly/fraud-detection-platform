package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessment.CriterionOutcome;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionAssessmentServiceTest {

    @Test
    void missingApprovedPolicyLeavesEvidenceReviewableButBlocksRecommendationAndAuthority() {
        ModelPromotionAssessment assessment = ModelPromotionAssessmentTestFixtures
                .service((scope, use, assessmentTime) -> Optional.empty())
                .assess(ModelPromotionAssessmentTestFixtures.completeEvidence(), ASSESSMENT_TIME);

        assertThat(assessment.content().diagnosticEvidenceAvailable()).isTrue();
        assertThat(assessment.content().humanReviewable()).isTrue();
        assertThat(assessment.content().formalRecommendationEligible()).isFalse();
        assertThat(assessment.content().governanceApproved()).isFalse();
        assertThat(assessment.content().deploymentAuthorized()).isFalse();
        assertThat(assessment.content().blockers()).contains("APPROVED_POLICY_NOT_FOUND");
        assertThat(assessment.content().criteria())
                .allMatch(result -> result.outcome() == CriterionOutcome.UNAVAILABLE);
    }

    @Test
    void currentUnavailableOperationalAndComparisonEvidenceRemainExplicitBlockers() {
        ModelPromotionAssessment assessment = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy())
                .assess(ModelPromotionAssessmentTestFixtures.currentLimitedEvidence(), ASSESSMENT_TIME);

        assertOutcome(assessment, ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                CriterionOutcome.UNAVAILABLE);
        assertOutcome(assessment, ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                CriterionOutcome.FAIL);
        assertOutcome(assessment, ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS,
                CriterionOutcome.UNAVAILABLE);
        assertOutcome(assessment, ModelPromotionCriterion.EXACT_MODEL_LATENCY_STABILITY,
                CriterionOutcome.UNAVAILABLE);
        assertOutcome(assessment, ModelPromotionCriterion.EXACT_MODEL_FALLBACK_RATE,
                CriterionOutcome.UNAVAILABLE);
        assertThat(assessment.content().assessmentStatus())
                .isEqualTo(ModelPromotionAssessment.AssessmentStatus.REVIEWABLE_BLOCKED);
        assertThat(assessment.content().humanReviewable()).isTrue();
        assertThat(assessment.content().formalRecommendationEligible()).isFalse();
    }

    @Test
    void incompletePopulationIsNotReviewableEvenWhenDiagnosticArtifactsExist() {
        ModelPromotionEvidenceBundle source = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionEvidenceBundle.PopulationEvidence incompletePopulation =
                new ModelPromotionEvidenceBundle.PopulationEvidence(
                        100, 100, 50, 50, true, "COMPLETE", "BOUNDED_SOURCE_TRUNCATED"
                );

        ModelPromotionAssessment assessment = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy())
                .assess(withPopulation(source, incompletePopulation), ASSESSMENT_TIME);

        assertThat(assessment.content().diagnosticEvidenceAvailable()).isTrue();
        assertThat(assessment.content().humanReviewable()).isFalse();
        assertThat(assessment.content().assessmentStatus())
                .isEqualTo(ModelPromotionAssessment.AssessmentStatus.NOT_REVIEWABLE);
        assertThat(assessment.content().blockers()).contains("EVIDENCE_NOT_REVIEWABLE");
        assertThat(assessment.content().formalRecommendationEligible()).isFalse();
    }

    @Test
    void unverifiedExternalAttestationClaimsRemainUnavailable() {
        List<ModelPromotionPolicy.CriterionRule> rules = new ArrayList<>(
                ModelPromotionAssessmentTestFixtures.defaultRules()
        );
        ModelPromotionPolicy.CriterionRule provenance = ModelPromotionAssessmentTestFixtures.rule(
                ModelPromotionCriterion.COMPLETE_EVALUATION_PROVENANCE,
                ModelPromotionPolicy.CriterionApplicability.MANDATORY
        );
        replaceRule(rules, new ModelPromotionPolicy.CriterionRule(
                provenance.criterion(),
                provenance.applicability(),
                provenance.thresholds(),
                provenance.maximumEvidenceAgeSeconds(),
                "EXTERNALLY_ATTESTED",
                provenance.evidenceSource(),
                provenance.metricSemantics(),
                provenance.populationDefinition(),
                provenance.denominatorDefinition()
        ));
        ModelPromotionEvidenceBundle source = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.evidence(
                source.content().evaluation(),
                new ModelPromotionEvidenceBundle.EvidenceTrust(
                        "VERIFIED_REPORTS_AND_MANIFESTS",
                        "VERIFIED_AGAINST_SOURCE_BYTES",
                        "PROVIDED",
                        "VERIFIED"
                )
        );

        ModelPromotionAssessment assessment = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy(
                rules, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"
        )).assess(evidence, ASSESSMENT_TIME);

        assertOutcome(assessment, ModelPromotionCriterion.COMPLETE_EVALUATION_PROVENANCE,
                CriterionOutcome.UNAVAILABLE);
        assertThat(assessment.content().humanReviewable()).isTrue();
        assertThat(assessment.content().assessmentStatus())
                .isEqualTo(ModelPromotionAssessment.AssessmentStatus.REVIEWABLE_BLOCKED);
    }

    @Test
    void syntheticCompleteEvidencePassesIndividualCriteriaButTestPolicyCannotAuthorizeRecommendation() {
        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionAssessmentService service = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy());

        ModelPromotionAssessment first = service.assess(evidence, ASSESSMENT_TIME);
        ModelPromotionAssessment second = service.assess(evidence, ASSESSMENT_TIME);

        assertThat(first.content().criteria()).allMatch(result -> result.outcome() == CriterionOutcome.PASS);
        assertThat(first.content().formalRecommendationEligible()).isFalse();
        assertThat(first.content().blockers()).containsExactly("POLICY_NOT_INSTITUTIONALLY_APPROVED");
        assertThat(first.canonicalSha256()).isEqualTo(second.canonicalSha256());
    }

    @Test
    void truncatedOrBiasedCohortCannotPassSampleCriterion() {
        ModelPromotionEvidenceBundle complete = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionEvidenceBundle.EvaluationEvidence evaluation = complete.content().evaluation();
        var truncatedPopulation = new ModelPromotionEvidenceBundle.PopulationEvidence(
                5000, 5000, 500, 4500, true, "TRUNCATED", "BOUNDED_SOURCE_TRUNCATED"
        );
        ModelPromotionAssessment truncated = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy())
                .assess(withPopulation(complete, truncatedPopulation), ASSESSMENT_TIME);
        assertOutcome(truncated, ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                CriterionOutcome.INCONCLUSIVE);

        var biasedPopulation = new ModelPromotionEvidenceBundle.PopulationEvidence(
                5000, 5000, 10, 4990, false, "COMPLETE", "BOUNDED_SOURCE_NOT_TRUNCATED"
        );
        ModelPromotionAssessment biased = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy())
                .assess(withPopulation(complete, biasedPopulation), ASSESSMENT_TIME);
        assertOutcome(biased, ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE,
                CriterionOutcome.FAIL);
        assertThat(biased.content().criteria().stream()
                .filter(result -> result.criterion() == ModelPromotionCriterion.STATISTICALLY_MEANINGFUL_SAMPLE)
                .findFirst().orElseThrow().reason()).isEqualTo("INSUFFICIENT_OR_BIASED_LABEL_SAMPLE");
        assertThat(evaluation.population().positiveClassCount()).isEqualTo(500);
    }

    @Test
    void unmatchedReviewBudgetAndMissingSegmentEvidenceDoNotPass() {
        List<ModelPromotionPolicy.CriterionRule> rules = new ArrayList<>(
                ModelPromotionAssessmentTestFixtures.defaultRules()
        );
        replaceRule(rules, new ModelPromotionPolicy.CriterionRule(
                ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                ModelPromotionPolicy.CriterionApplicability.MANDATORY,
                Map.of(
                        "alertReviewBudget", new BigDecimal("200"),
                        "minimumMlPrecision", new BigDecimal("0.70"),
                        "minimumDeltaVsRules", BigDecimal.ZERO
                ),
                604800,
                "VERIFIED_REPORTS_AND_MANIFESTS",
                ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET.evidenceSource(),
                ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET.metricSemantics(),
                ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET.populationDefinition(),
                ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET.denominatorDefinition()
        ));
        ModelPromotionPolicy policy = ModelPromotionAssessmentTestFixtures.policy(
                rules, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"
        );

        ModelPromotionAssessment unfair = serviceWithPolicy(policy)
                .assess(ModelPromotionAssessmentTestFixtures.completeEvidence(), ASSESSMENT_TIME);
        assertOutcome(unfair, ModelPromotionCriterion.PRECISION_AT_ALERT_REVIEW_BUDGET,
                CriterionOutcome.INCONCLUSIVE);

        ModelPromotionAssessment missingSegments = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy())
                .assess(ModelPromotionAssessmentTestFixtures.currentLimitedEvidence(), ASSESSMENT_TIME);
        assertOutcome(missingSegments, ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS,
                CriterionOutcome.UNAVAILABLE);
    }

    @Test
    void staleEvidenceIsInconclusiveAndExplicitNotApplicableIsHonored() {
        List<ModelPromotionPolicy.CriterionRule> rules = new ArrayList<>(
                ModelPromotionAssessmentTestFixtures.defaultRules()
        );
        replaceRule(rules, ModelPromotionAssessmentTestFixtures.rule(
                ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS,
                ModelPromotionPolicy.CriterionApplicability.NOT_APPLICABLE_AUTHORIZED
        ));
        ModelPromotionPolicy policy = ModelPromotionAssessmentTestFixtures.policy(
                rules, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"
        );

        ModelPromotionAssessment assessment = serviceWithPolicy(policy).assess(
                ModelPromotionAssessmentTestFixtures.completeEvidence(),
                Instant.parse("2026-07-01T00:00:00Z")
        );

        assertOutcome(assessment, ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS,
                CriterionOutcome.NOT_APPLICABLE);
        assertThat(assessment.content().assessmentStatus())
                .isEqualTo(ModelPromotionAssessment.AssessmentStatus.REVIEWABLE_BLOCKED);
        assertThat(assessment.content().criteria().stream()
                .filter(result -> result.criterion() != ModelPromotionCriterion.CRITICAL_SEGMENT_REGRESSIONS)
                .allMatch(result -> result.outcome() == CriterionOutcome.INCONCLUSIVE)).isTrue();
    }

    @Test
    void inconsistentDenominatorAndModifiedEvidenceDigestFailClosed() {
        assertThatThrownBy(() -> new ModelPromotionEvidenceBundle.RateMetricEvidence(
                true, BigDecimal.ZERO, 0, 0, "FEEDBACK_CREATED_AT", null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denominator");

        ModelPromotionEvidenceBundle evidence = ModelPromotionAssessmentTestFixtures.completeEvidence();
        ModelPromotionEvidenceBundle.Content original = evidence.content();
        List<String> modifiedLimitations = new ArrayList<>(original.limitations());
        modifiedLimitations.add("UNBOUND_MODIFICATION");
        ModelPromotionEvidenceBundle modified = new ModelPromotionEvidenceBundle(
                new ModelPromotionEvidenceBundle.Content(
                        original.bundleVersion(), original.artifactIdentity(), original.governanceScope(),
                        original.intendedUse(), original.evaluationWindow(), original.modelCard(),
                        original.shadowPerformance(), original.evaluation(), original.trust(), modifiedLimitations
                ),
                evidence.canonicalSha256()
        );
        assertThatThrownBy(() -> serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy())
                .assess(modified, ASSESSMENT_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void criticalLineageGapFailsItsCriterion() {
        ModelPromotionEvidenceBundle source = ModelPromotionAssessmentTestFixtures.completeEvidence();
        var evaluation = source.content().evaluation();
        var withGap = new ModelPromotionEvidenceBundle.EvaluationEvidence(
                evaluation.population(),
                evaluation.mlPrecisionAtReviewBudget(),
                evaluation.fairPrecisionComparison(),
                evaluation.falsePositiveRate(),
                evaluation.rulesVsMlDisagreement(),
                evaluation.criticalSegmentRegressions(),
                evaluation.exactModelLatencyP95(),
                evaluation.exactModelFallbackRate(),
                new ModelPromotionEvidenceBundle.LineageEvidence("EXCLUDE", 1, 0, 0, 0, 0)
        );

        ModelPromotionAssessment assessment = serviceWithPolicy(ModelPromotionAssessmentTestFixtures.policy())
                .assess(ModelPromotionAssessmentTestFixtures.evidence(withGap), ASSESSMENT_TIME);

        assertOutcome(assessment, ModelPromotionCriterion.IDENTITY_AND_LINEAGE_INTEGRITY,
                CriterionOutcome.FAIL);
    }

    private ModelPromotionAssessmentService serviceWithPolicy(ModelPromotionPolicy policy) {
        return ModelPromotionAssessmentTestFixtures.service((scope, use, assessmentTime) -> Optional.of(policy));
    }

    private ModelPromotionEvidenceBundle withPopulation(
            ModelPromotionEvidenceBundle source,
            ModelPromotionEvidenceBundle.PopulationEvidence population
    ) {
        var evaluation = source.content().evaluation();
        var replaced = new ModelPromotionEvidenceBundle.EvaluationEvidence(
                population,
                evaluation.mlPrecisionAtReviewBudget(),
                evaluation.fairPrecisionComparison(),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        true, BigDecimal.ZERO, 0, population.negativeClassCount(),
                        "FEEDBACK_CREATED_AT", null
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        true, BigDecimal.ZERO, 0, population.recordsEvaluated(),
                        "FEEDBACK_CREATED_AT", null
                ),
                evaluation.criticalSegmentRegressions(),
                evaluation.exactModelLatencyP95(),
                evaluation.exactModelFallbackRate(),
                evaluation.lineage()
        );
        return ModelPromotionAssessmentTestFixtures.evidence(replaced);
    }

    private void replaceRule(
            List<ModelPromotionPolicy.CriterionRule> rules,
            ModelPromotionPolicy.CriterionRule replacement
    ) {
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).criterion() == replacement.criterion()) {
                rules.set(index, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("criterion not found");
    }

    private void assertOutcome(
            ModelPromotionAssessment assessment,
            ModelPromotionCriterion criterion,
            CriterionOutcome outcome
    ) {
        assertThat(assessment.content().criteria())
                .filteredOn(result -> result.criterion() == criterion)
                .singleElement()
                .extracting(ModelPromotionAssessment.CriterionAssessment::outcome)
                .isEqualTo(outcome);
    }
}
