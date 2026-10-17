package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class ModelPromotionAssessmentTestFixtures {

    static final Instant ASSESSMENT_TIME = Instant.parse("2026-06-11T00:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ModelPromotionAssessmentTestFixtures() {
    }

    static ModelPromotionEvidenceBundle completeEvidence() {
        return evidence(completeEvaluation());
    }

    static ModelPromotionEvidenceBundle currentLimitedEvidence() {
        ModelPromotionEvidenceBundle.EvaluationEvidence complete = completeEvaluation();
        return evidence(new ModelPromotionEvidenceBundle.EvaluationEvidence(
                new ModelPromotionEvidenceBundle.PopulationEvidence(
                        4, 4, 2, 2, false, "COMPLETE", "BOUNDED_SOURCE_NOT_TRUNCATED"
                ),
                Map.of(2, new ModelPromotionEvidenceBundle.RankedMetricEvidence(
                        true, 2, 2, new BigDecimal("1.0"), null
                )),
                new ModelPromotionEvidenceBundle.FairPrecisionComparisonEvidence(
                        false, null, null, null, null, null,
                        "MATCHED_RULES_PRECISION_AT_REVIEW_BUDGET_UNAVAILABLE"
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        true, BigDecimal.ZERO, 0, 2, "FEEDBACK_CREATED_AT", null
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        true, new BigDecimal("0.5"), 2, 4, "FEEDBACK_CREATED_AT", null
                ),
                new ModelPromotionEvidenceBundle.SegmentRegressionEvidence(
                        false, null, null, null, "IDENTIFIABLE_SEGMENT_REGRESSION_EVIDENCE_UNAVAILABLE"
                ),
                new ModelPromotionEvidenceBundle.LatencyEvidence(
                        false, null, null, null, "EXACT_ARTIFACT_LATENCY_EVIDENCE_UNAVAILABLE"
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        false, null, null, null, null, "EXACT_ARTIFACT_FALLBACK_EVIDENCE_UNAVAILABLE"
                ),
                complete.lineage()
        ));
    }

    static ModelPromotionEvidenceBundle evidence(ModelPromotionEvidenceBundle.EvaluationEvidence evaluation) {
        ModelPromotionEvidenceBundle.Content content = new ModelPromotionEvidenceBundle.Content(
                ModelPromotionEvidenceBundle.CURRENT_VERSION,
                identity(),
                scope(),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                new ModelPromotionEvaluationWindow(
                        "2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z"
                ),
                new ModelPromotionEvidenceBundle.ModelCardEvidence(
                        "b".repeat(64), identity().modelArtifactSha256(),
                        "2026-06-10T00:00:01Z", "1".repeat(64),
                        "VERIFIED_AGAINST_SOURCE_BYTES"
                ),
                new ModelPromotionEvidenceBundle.ShadowPerformanceEvidence(
                        "c".repeat(64), "2026-06-10T00:00:01Z", "1".repeat(64),
                        evaluation.population().returnedCohortStatus(),
                        evaluation.population().sourcePopulationStatus(),
                        evaluation.population().recordsConsidered(),
                        evaluation.population().recordsEvaluated(),
                        evaluation.exactModelLatencyP95().available()
                                && evaluation.exactModelFallbackRate().available()
                ),
                evaluation,
                new ModelPromotionEvidenceBundle.EvidenceTrust(
                        "VERIFIED_REPORTS_AND_MANIFESTS",
                        "VERIFIED_AGAINST_SOURCE_BYTES",
                        "NOT_PROVIDED",
                        "NOT_CLAIMED"
                ),
                List.of(
                        "ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_CERTIFIED_GROUND_TRUTH",
                        "BOUNDED_FEEDBACK_POPULATION_DOES_NOT_PROVE_GLOBAL_REPRESENTATIVENESS"
                )
        );
        return new ModelPromotionEvidenceBundle(content, digest(content));
    }

    static ModelPromotionPolicy policy() {
        return policy(defaultRules(), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
    }

    static ModelPromotionPolicy policy(
            List<ModelPromotionPolicy.CriterionRule> rules,
            String effectiveFrom,
            String effectiveTo
    ) {
        ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(
                ModelPromotionPolicy.CURRENT_VERSION,
                "synthetic-test-policy",
                1,
                scope(),
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                new ModelPromotionPolicy.PolicyProvenance(
                        ModelPromotionPolicy.PolicyAuthority.NON_INSTITUTIONAL_TEST,
                        "test-author",
                        "test-fixture-only",
                        "2025-12-31T00:00:00Z",
                        "synthetic-policy-proof"
                ),
                effectiveFrom,
                effectiveTo,
                rules
        );
        return new ModelPromotionPolicy(content, policyValidator().canonicalSha256(content));
    }

    static List<ModelPromotionPolicy.CriterionRule> defaultRules() {
        return Arrays.stream(ModelPromotionCriterion.values()).map(criterion -> rule(
                criterion, ModelPromotionPolicy.CriterionApplicability.MANDATORY
        )).toList();
    }

    static ModelPromotionPolicy.CriterionRule rule(
            ModelPromotionCriterion criterion,
            ModelPromotionPolicy.CriterionApplicability applicability
    ) {
        Map<String, BigDecimal> thresholds = applicability
                == ModelPromotionPolicy.CriterionApplicability.NOT_APPLICABLE_AUTHORIZED
                ? Map.of()
                : switch (criterion) {
                    case STATISTICALLY_MEANINGFUL_SAMPLE -> Map.of(
                            "minimumEvaluatedRecords", new BigDecimal("1000"),
                            "minimumPositiveLabels", new BigDecimal("100"),
                            "minimumNegativeLabels", new BigDecimal("100")
                    );
                    case PRECISION_AT_ALERT_REVIEW_BUDGET -> Map.of(
                            "alertReviewBudget", new BigDecimal("100"),
                            "minimumMlPrecision", new BigDecimal("0.70"),
                            "minimumDeltaVsRules", BigDecimal.ZERO
                    );
                    case FALSE_POSITIVE_RATE, RULES_VS_ML_DISAGREEMENT -> Map.of(
                            "maximumRate", new BigDecimal("0.10")
                    );
                    case CRITICAL_SEGMENT_REGRESSIONS -> Map.of(
                            "maximumCriticalRegressions", BigDecimal.ZERO,
                            "minimumSegmentsEvaluated", new BigDecimal("5")
                    );
                    case EXACT_MODEL_LATENCY_STABILITY -> Map.of(
                            "maximumMilliseconds", new BigDecimal("100.0"),
                            "minimumSampleSize", new BigDecimal("1000")
                    );
                    case EXACT_MODEL_FALLBACK_RATE -> Map.of(
                            "maximumRate", new BigDecimal("0.02"),
                            "minimumDenominator", new BigDecimal("1000")
                    );
                    case COMPLETE_EVALUATION_PROVENANCE, EXACT_SHA_MODEL_CARD -> Map.of();
                    case IDENTITY_AND_LINEAGE_INTEGRITY -> Map.of(
                            "maximumCriticalLineageGaps", BigDecimal.ZERO
                    );
                };
        return new ModelPromotionPolicy.CriterionRule(
                criterion,
                applicability,
                thresholds,
                7 * 24 * 60 * 60,
                "VERIFIED_REPORTS_AND_MANIFESTS",
                criterion.evidenceSource(),
                criterion.metricSemantics(),
                criterion.populationDefinition(),
                criterion.denominatorDefinition()
        );
    }

    static ModelPromotionAssessmentService service(ModelPromotionPolicyStore store) {
        return new ModelPromotionAssessmentService(
                new ModelPromotionPolicyResolver(store, policyValidator()),
                OBJECT_MAPPER
        );
    }

    static ModelPromotionPolicyValidator policyValidator() {
        return new ModelPromotionPolicyValidator(OBJECT_MAPPER);
    }

    static ExactModelArtifactIdentity identity() {
        return new ExactModelArtifactIdentity(
                "fraud-model", "2026.10", "feature-contract-v2", "a".repeat(64)
        );
    }

    static ModelGovernanceScope scope() {
        return new ModelGovernanceScope(
                ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.PRODUCTION
        );
    }

    private static ModelPromotionEvidenceBundle.EvaluationEvidence completeEvaluation() {
        return new ModelPromotionEvidenceBundle.EvaluationEvidence(
                new ModelPromotionEvidenceBundle.PopulationEvidence(
                        5000, 5000, 500, 4500, false, "COMPLETE", "BOUNDED_SOURCE_NOT_TRUNCATED"
                ),
                Map.of(100, new ModelPromotionEvidenceBundle.RankedMetricEvidence(
                        true, 100, 100, new BigDecimal("0.80"), null
                )),
                new ModelPromotionEvidenceBundle.FairPrecisionComparisonEvidence(
                        true, 100, 100, new BigDecimal("0.80"), new BigDecimal("0.70"),
                        "synthetic-matched-population", null
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        true, new BigDecimal("0.02"), 90, 4500, "FEEDBACK_CREATED_AT", null
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        true, new BigDecimal("0.05"), 250, 5000, "FEEDBACK_CREATED_AT", null
                ),
                new ModelPromotionEvidenceBundle.SegmentRegressionEvidence(
                        true, 10, 0, "synthetic-segment-contract", null
                ),
                new ModelPromotionEvidenceBundle.LatencyEvidence(
                        true, new BigDecimal("50.0"), 5000, "SCORING_OCCURRED_AT", null
                ),
                new ModelPromotionEvidenceBundle.RateMetricEvidence(
                        true, new BigDecimal("0.01"), 50, 5000, "SCORING_OCCURRED_AT", null
                ),
                new ModelPromotionEvidenceBundle.LineageEvidence("EXCLUDE", 0, 0, 0, 0, 0)
        );
    }

    private static String digest(Object value) {
        try {
            byte[] canonical = GovernanceCanonicalJson.serialize(OBJECT_MAPPER, OBJECT_MAPPER.valueToTree(value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
