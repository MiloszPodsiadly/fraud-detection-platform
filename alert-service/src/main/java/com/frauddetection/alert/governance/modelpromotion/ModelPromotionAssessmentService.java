package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessment.AssessmentStatus;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessment.CriterionOutcome;

@Service
public class ModelPromotionAssessmentService {

    static final String EVALUATOR_IDENTITY = "JAVA_MODEL_PROMOTION_POLICY_EVALUATOR";
    static final String EVALUATOR_VERSION = "1";

    private final ModelPromotionPolicyResolver policyResolver;
    private final ObjectMapper objectMapper;

    public ModelPromotionAssessmentService(
            ModelPromotionPolicyResolver policyResolver,
            ObjectMapper objectMapper
    ) {
        this.policyResolver = Objects.requireNonNull(policyResolver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public ModelPromotionAssessment assess(
            ModelPromotionEvidenceBundle evidenceBundle,
            Instant assessmentTime
    ) {
        Objects.requireNonNull(evidenceBundle, "evidenceBundle is required");
        Objects.requireNonNull(assessmentTime, "assessmentTime is required");
        if (!canonicalSha256(evidenceBundle.content()).equals(evidenceBundle.canonicalSha256())) {
            throw new IllegalArgumentException("evidence bundle digest mismatch");
        }
        ModelPromotionEvidenceBundle.Content evidence = evidenceBundle.content();
        ModelPromotionPolicyResolution policyResolution = policyResolver.resolve(
                evidence.governanceScope(), evidence.intendedUse(), assessmentTime
        );
        List<ModelPromotionAssessment.CriterionAssessment> criteria = policyResolution.policy() == null
                ? unavailableCriteria(policyResolution)
                : assessCriteria(evidence, policyResolution.policy(), assessmentTime);
        boolean humanReviewable = humanReviewable(evidence);
        List<String> blockers = new ArrayList<>(blockers(policyResolution, criteria));
        if (!humanReviewable) {
            blockers.add(0, "EVIDENCE_NOT_REVIEWABLE");
        }
        boolean institutionallyApprovedPolicy = policyResolution.policy() != null
                && policyResolution.policy().content().provenance().authority()
                == ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED;
        boolean eligible = humanReviewable && institutionallyApprovedPolicy && blockers.isEmpty();
        AssessmentStatus status = !humanReviewable
                ? AssessmentStatus.NOT_REVIEWABLE
                : eligible
                        ? AssessmentStatus.ELIGIBLE_FOR_FORMAL_RECOMMENDATION
                        : AssessmentStatus.REVIEWABLE_BLOCKED;
        ModelPromotionEvidenceBundle.PopulationEvidence population = evidence.evaluation().population();
        ModelPromotionAssessment.Content content = new ModelPromotionAssessment.Content(
                ModelPromotionAssessment.CURRENT_VERSION,
                assessmentTime.toString(),
                EVALUATOR_IDENTITY,
                EVALUATOR_VERSION,
                evidence.artifactIdentity(),
                evidence.governanceScope(),
                evidence.intendedUse(),
                policyReference(policyResolution),
                new ModelPromotionAssessment.EvidenceReference(
                        evidenceBundle.canonicalSha256(),
                        evidence.modelCard().manifestSha256(),
                        evidence.shadowPerformance().manifestSha256(),
                        evidence.evaluationWindow()
                ),
                new ModelPromotionAssessment.PopulationAndCoverage(
                        population.recordsConsidered(),
                        population.recordsEvaluated(),
                        population.positiveClassCount(),
                        population.negativeClassCount(),
                        population.sourceDatasetTruncated(),
                        population.returnedCohortStatus(),
                        population.sourcePopulationStatus(),
                        evidence.evaluation().lineage().criticalGapCount()
                ),
                criteria,
                status,
                true,
                humanReviewable,
                eligible,
                false,
                false,
                blockers,
                evidence.limitations()
        );
        return new ModelPromotionAssessment(content, canonicalSha256(content));
    }

    private List<ModelPromotionAssessment.CriterionAssessment> unavailableCriteria(
            ModelPromotionPolicyResolution resolution
    ) {
        return java.util.Arrays.stream(ModelPromotionCriterion.values())
                .map(criterion -> criterion(
                        criterion,
                        CriterionOutcome.UNAVAILABLE,
                        resolution.reason().name(),
                        Map.of(),
                        "POLICY_NOT_RESOLVED",
                        null
                ))
                .toList();
    }

    private List<ModelPromotionAssessment.CriterionAssessment> assessCriteria(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy policy,
            Instant assessmentTime
    ) {
        Map<ModelPromotionCriterion, ModelPromotionPolicy.CriterionRule> rules = new EnumMap<>(
                ModelPromotionCriterion.class
        );
        policy.content().criteria().forEach(rule -> rules.put(rule.criterion(), rule));
        return rules.values().stream()
                .sorted(Comparator.comparing(rule -> rule.criterion().ordinal()))
                .map(rule -> assessCriterion(evidence, rule, assessmentTime))
                .toList();
    }

    private ModelPromotionAssessment.CriterionAssessment assessCriterion(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule,
            Instant assessmentTime
    ) {
        if (rule.applicability() == ModelPromotionPolicy.CriterionApplicability.NOT_APPLICABLE_AUTHORIZED) {
            return criterion(rule, CriterionOutcome.NOT_APPLICABLE, "POLICY_AUTHORIZED_NOT_APPLICABLE", Map.of());
        }
        if (stale(evidence, rule, assessmentTime)) {
            return criterion(rule, CriterionOutcome.INCONCLUSIVE, "EVIDENCE_OUTSIDE_POLICY_FRESHNESS", Map.of());
        }
        return switch (rule.criterion()) {
            case STATISTICALLY_MEANINGFUL_SAMPLE -> sample(evidence, rule);
            case PRECISION_AT_ALERT_REVIEW_BUDGET -> precisionComparison(evidence, rule);
            case FALSE_POSITIVE_RATE -> rate(
                    rule, evidence.evaluation().falsePositiveRate(), "RATE_WITHIN_POLICY_BOUND"
            );
            case RULES_VS_ML_DISAGREEMENT -> rate(
                    rule, evidence.evaluation().rulesVsMlDisagreement(), "DISAGREEMENT_WITHIN_POLICY_BOUND"
            );
            case CRITICAL_SEGMENT_REGRESSIONS -> segmentRegressions(evidence, rule);
            case EXACT_MODEL_LATENCY_STABILITY -> latency(evidence, rule);
            case EXACT_MODEL_FALLBACK_RATE -> fallback(evidence, rule);
            case COMPLETE_EVALUATION_PROVENANCE -> provenance(evidence, rule);
            case EXACT_SHA_MODEL_CARD -> exactModelCard(evidence, rule);
            case IDENTITY_AND_LINEAGE_INTEGRITY -> lineage(evidence, rule);
        };
    }

    private ModelPromotionAssessment.CriterionAssessment sample(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        var population = evidence.evaluation().population();
        Map<String, String> values = values(
                "recordsEvaluated", population.recordsEvaluated(),
                "positiveClassCount", population.positiveClassCount(),
                "negativeClassCount", population.negativeClassCount(),
                "sourceDatasetTruncated", population.sourceDatasetTruncated()
        );
        if (population.sourceDatasetTruncated()) {
            return criterion(rule, CriterionOutcome.INCONCLUSIVE, "SOURCE_COHORT_TRUNCATED", values);
        }
        boolean pass = population.recordsEvaluated() >= integer(rule, "minimumEvaluatedRecords")
                && population.positiveClassCount() >= integer(rule, "minimumPositiveLabels")
                && population.negativeClassCount() >= integer(rule, "minimumNegativeLabels");
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? "SAMPLE_REQUIREMENTS_SATISFIED" : "INSUFFICIENT_OR_BIASED_LABEL_SAMPLE", values);
    }

    private ModelPromotionAssessment.CriterionAssessment precisionComparison(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        var comparison = evidence.evaluation().fairPrecisionComparison();
        int budget = integer(rule, "alertReviewBudget");
        if (!comparison.available()) {
            return criterion(rule, CriterionOutcome.UNAVAILABLE, comparison.reason(), Map.of(
                    "requiredReviewBudget", Integer.toString(budget)
            ));
        }
        Map<String, String> values = values(
                "requestedBudget", comparison.requestedBudget(),
                "actualBudget", comparison.actualBudget(),
                "mlPrecision", comparison.mlPrecision(),
                "rulesPrecision", comparison.rulesPrecision(),
                "matchedPopulationIdentity", comparison.matchedPopulationIdentity()
        );
        if (comparison.requestedBudget() != budget || comparison.actualBudget() != budget) {
            return criterion(rule, CriterionOutcome.INCONCLUSIVE, "UNFAIR_ALERT_REVIEW_BUDGET", values);
        }
        BigDecimal delta = comparison.mlPrecision().subtract(comparison.rulesPrecision());
        boolean pass = comparison.mlPrecision().compareTo(threshold(rule, "minimumMlPrecision")) >= 0
                && delta.compareTo(threshold(rule, "minimumDeltaVsRules")) >= 0;
        values = new LinkedHashMap<>(values);
        values.put("precisionDeltaVsRules", delta.toPlainString());
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? "FAIR_PRECISION_REQUIREMENTS_SATISFIED" : "PRECISION_REQUIREMENTS_NOT_SATISFIED",
                values);
    }

    private ModelPromotionAssessment.CriterionAssessment rate(
            ModelPromotionPolicy.CriterionRule rule,
            ModelPromotionEvidenceBundle.RateMetricEvidence metric,
            String passReason
    ) {
        if (!metric.available()) {
            return criterion(rule, CriterionOutcome.UNAVAILABLE, metric.reason(), Map.of());
        }
        Map<String, String> values = values(
                "value", metric.value(), "numerator", metric.numerator(), "denominator", metric.denominator()
        );
        boolean pass = metric.value().compareTo(threshold(rule, "maximumRate")) <= 0;
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? passReason : "RATE_EXCEEDS_POLICY_BOUND", values);
    }

    private ModelPromotionAssessment.CriterionAssessment segmentRegressions(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        var segments = evidence.evaluation().criticalSegmentRegressions();
        if (!segments.available()) {
            return criterion(rule, CriterionOutcome.UNAVAILABLE, segments.reason(), Map.of());
        }
        Map<String, String> values = values(
                "segmentsEvaluated", segments.segmentsEvaluated(),
                "criticalRegressionCount", segments.criticalRegressionCount(),
                "segmentDefinitionIdentity", segments.segmentDefinitionIdentity()
        );
        boolean pass = segments.segmentsEvaluated() >= integer(rule, "minimumSegmentsEvaluated")
                && segments.criticalRegressionCount() <= integer(rule, "maximumCriticalRegressions");
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? "NO_UNACCEPTABLE_CRITICAL_SEGMENT_REGRESSION" : "CRITICAL_SEGMENT_REGRESSION",
                values);
    }

    private ModelPromotionAssessment.CriterionAssessment latency(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        var latency = evidence.evaluation().exactModelLatencyP95();
        if (!latency.available()) {
            return criterion(rule, CriterionOutcome.UNAVAILABLE, latency.reason(), Map.of());
        }
        Map<String, String> values = values(
                "valueMilliseconds", latency.valueMilliseconds(), "sampleSize", latency.sampleSize(),
                "timeBasis", latency.timeBasis()
        );
        boolean pass = latency.valueMilliseconds().compareTo(threshold(rule, "maximumMilliseconds")) <= 0
                && latency.sampleSize() >= integer(rule, "minimumSampleSize");
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? "LATENCY_WITHIN_POLICY_BOUND" : "LATENCY_REQUIREMENTS_NOT_SATISFIED", values);
    }

    private ModelPromotionAssessment.CriterionAssessment fallback(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        var fallback = evidence.evaluation().exactModelFallbackRate();
        if (!fallback.available()) {
            return criterion(rule, CriterionOutcome.UNAVAILABLE, fallback.reason(), Map.of());
        }
        Map<String, String> values = values(
                "value", fallback.value(), "numerator", fallback.numerator(),
                "denominator", fallback.denominator(), "timeBasis", fallback.timeBasis()
        );
        boolean pass = fallback.value().compareTo(threshold(rule, "maximumRate")) <= 0
                && fallback.denominator() >= integer(rule, "minimumDenominator");
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? "FALLBACK_WITHIN_POLICY_BOUND" : "FALLBACK_REQUIREMENTS_NOT_SATISFIED", values);
    }

    private ModelPromotionAssessment.CriterionAssessment provenance(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        var trust = evidence.trust();
        var population = evidence.evaluation().population();
        Map<String, String> values = values(
                "artifactIntegrityVerification", trust.artifactIntegrityVerification(),
                "sourceDatasetVerificationLevel", trust.sourceDatasetVerificationLevel(),
                "externalAttestation", trust.externalAttestation(),
                "immutableStorageGuarantee", trust.immutableStorageGuarantee()
        );
        if ("EXTERNALLY_ATTESTED".equals(rule.requiredVerificationLevel())) {
            return criterion(rule, CriterionOutcome.UNAVAILABLE,
                    "TRUSTED_EXTERNAL_ATTESTATION_UNAVAILABLE", values);
        }
        boolean baseComplete = !population.sourceDatasetTruncated()
                && "COMPLETE".equals(population.returnedCohortStatus())
                && "BOUNDED_SOURCE_NOT_TRUNCATED".equals(population.sourcePopulationStatus())
                && trust.artifactIntegrityVerification().startsWith("VERIFIED")
                && trust.sourceDatasetVerificationLevel().startsWith("VERIFIED");
        boolean requiredLevel = verificationSatisfied(rule.requiredVerificationLevel(), trust);
        boolean pass = baseComplete && requiredLevel;
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.INCONCLUSIVE,
                pass ? "REQUIRED_PROVENANCE_VERIFIED" : "REQUIRED_PROVENANCE_NOT_ESTABLISHED", values);
    }

    private boolean humanReviewable(ModelPromotionEvidenceBundle.Content evidence) {
        ModelPromotionEvidenceBundle.PopulationEvidence population = evidence.evaluation().population();
        return population.recordsConsidered() > 0
                && population.recordsEvaluated() > 0
                && !population.sourceDatasetTruncated()
                && "COMPLETE".equals(population.returnedCohortStatus())
                && "BOUNDED_SOURCE_NOT_TRUNCATED".equals(population.sourcePopulationStatus())
                && evidence.trust().artifactIntegrityVerification().startsWith("VERIFIED_REPORT")
                && evidence.modelCard().modelArtifactSha256()
                .equals(evidence.artifactIdentity().modelArtifactSha256());
    }

    private ModelPromotionAssessment.CriterionAssessment exactModelCard(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        boolean pass = evidence.artifactIdentity().modelArtifactSha256()
                .equals(evidence.modelCard().modelArtifactSha256());
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? "EXACT_SHA_MODEL_CARD_VERIFIED" : "MODEL_CARD_ARTIFACT_SHA_MISMATCH", values(
                "modelArtifactSha256", evidence.artifactIdentity().modelArtifactSha256(),
                "modelCardArtifactSha256", evidence.modelCard().modelArtifactSha256(),
                "modelCardManifestSha256", evidence.modelCard().manifestSha256()
        ));
    }

    private ModelPromotionAssessment.CriterionAssessment lineage(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule
    ) {
        var lineage = evidence.evaluation().lineage();
        Map<String, String> values = values(
                "identityMismatchBehavior", lineage.identityMismatchBehavior(),
                "criticalGapCount", lineage.criticalGapCount()
        );
        boolean pass = lineage.criticalGapCount() <= integer(rule, "maximumCriticalLineageGaps")
                && "EXCLUDE".equals(lineage.identityMismatchBehavior());
        return criterion(rule, pass ? CriterionOutcome.PASS : CriterionOutcome.FAIL,
                pass ? "IDENTITY_AND_LINEAGE_INTEGRITY_SATISFIED" : "CRITICAL_IDENTITY_OR_LINEAGE_GAP",
                values);
    }

    private boolean stale(
            ModelPromotionEvidenceBundle.Content evidence,
            ModelPromotionPolicy.CriterionRule rule,
            Instant assessmentTime
    ) {
        Instant generatedAt = GovernanceTimestampContract.parse(
                rule.criterion() == ModelPromotionCriterion.EXACT_SHA_MODEL_CARD
                        ? evidence.modelCard().generatedAt()
                        : evidence.shadowPerformance().generatedAt(),
                "evidence generatedAt"
        );
        Duration age = Duration.between(generatedAt, assessmentTime);
        return age.isNegative() || age.getSeconds() > rule.maximumEvidenceAgeSeconds();
    }

    private boolean verificationSatisfied(
            String requiredLevel,
            ModelPromotionEvidenceBundle.EvidenceTrust trust
    ) {
        return switch (requiredLevel) {
            case "VERIFIED_REPORTS_AND_MANIFESTS" -> trust.artifactIntegrityVerification().startsWith("VERIFIED")
                    && trust.sourceDatasetVerificationLevel().startsWith("VERIFIED");
            case "EXTERNALLY_ATTESTED" -> false;
            default -> false;
        };
    }

    private List<String> blockers(
            ModelPromotionPolicyResolution policyResolution,
            List<ModelPromotionAssessment.CriterionAssessment> criteria
    ) {
        List<String> blockers = new ArrayList<>();
        if (policyResolution.status() != ModelPromotionPolicyResolution.Status.AVAILABLE) {
            blockers.add(policyResolution.reason().name());
        } else if (policyResolution.policy().content().provenance().authority()
                != ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED) {
            blockers.add("POLICY_NOT_INSTITUTIONALLY_APPROVED");
        }
        criteria.stream()
                .filter(result -> ModelPromotionEligibility.requiredFor(
                        ModelPromotionLifecycleState.PROMOTION_RECOMMENDED
                ).contains(result.criterion()))
                .filter(result -> result.outcome() != CriterionOutcome.PASS)
                .map(result -> result.criterion().name() + ":" + result.reason())
                .forEach(blockers::add);
        if (!ModelPromotionEligibility.passed(ModelPromotionLifecycleState.PROMOTION_RECOMMENDED, criteria)
                && blockers.isEmpty()) {
            blockers.add("REQUIRED_CRITERIA_INCOMPLETE");
        }
        return List.copyOf(blockers);
    }

    private ModelPromotionAssessment.PolicyReference policyReference(
            ModelPromotionPolicyResolution resolution
    ) {
        if (resolution.policy() == null) {
            return new ModelPromotionAssessment.PolicyReference(
                    resolution.status(), resolution.reason(), null, null, null, null
            );
        }
        ModelPromotionPolicy policy = resolution.policy();
        return new ModelPromotionAssessment.PolicyReference(
                resolution.status(), resolution.reason(), policy.content().policyId(),
                policy.content().revision(), policy.canonicalSha256(), policy.content().provenance().authority()
        );
    }

    private ModelPromotionAssessment.CriterionAssessment criterion(
            ModelPromotionPolicy.CriterionRule rule,
            CriterionOutcome outcome,
            String reason,
            Map<String, String> values
    ) {
        return new ModelPromotionAssessment.CriterionAssessment(
                rule.criterion(),
                outcome,
                reason,
                values,
                rule.evidenceSource(),
                rule.metricSemantics(),
                rule.populationDefinition(),
                rule.denominatorDefinition(),
                rule.requiredVerificationLevel(),
                rule.maximumEvidenceAgeSeconds()
        );
    }

    private ModelPromotionAssessment.CriterionAssessment criterion(
            ModelPromotionCriterion criterion,
            CriterionOutcome outcome,
            String reason,
            Map<String, String> values,
            String verificationLevel,
            Long maximumEvidenceAgeSeconds
    ) {
        return new ModelPromotionAssessment.CriterionAssessment(
                criterion,
                outcome,
                reason,
                values,
                criterion.evidenceSource(),
                criterion.metricSemantics(),
                criterion.populationDefinition(),
                criterion.denominatorDefinition(),
                verificationLevel,
                maximumEvidenceAgeSeconds
        );
    }

    private BigDecimal threshold(ModelPromotionPolicy.CriterionRule rule, String name) {
        return rule.thresholds().get(name);
    }

    private int integer(ModelPromotionPolicy.CriterionRule rule, String name) {
        return threshold(rule, name).intValueExact();
    }

    private Map<String, String> values(Object... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), String.valueOf(pairs[index + 1]));
        }
        return values;
    }

    private String canonicalSha256(ModelPromotionAssessment.Content content) {
        return GovernanceCanonicalJson.sha256(objectMapper, content);
    }

    private String canonicalSha256(ModelPromotionEvidenceBundle.Content content) {
        return GovernanceCanonicalJson.sha256(objectMapper, content);
    }
}
