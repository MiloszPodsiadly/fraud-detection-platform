package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelPromotionAssessment(Content content, String canonicalSha256) {

    public static final String CURRENT_VERSION = "model-promotion-assessment-v1";

    public ModelPromotionAssessment {
        Objects.requireNonNull(content, "content is required");
        if (canonicalSha256 == null || !canonicalSha256.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("canonicalSha256 must be lowercase SHA-256");
        }
    }

    public record Content(
            String assessmentVersion,
            String assessmentCreatedAt,
            String evaluatorIdentity,
            String evaluatorVersion,
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            PolicyReference policy,
            EvidenceReference evidence,
            PopulationAndCoverage populationAndCoverage,
            List<CriterionAssessment> criteria,
            AssessmentStatus assessmentStatus,
            boolean diagnosticEvidenceAvailable,
            boolean humanReviewable,
            boolean formalRecommendationEligible,
            boolean governanceApproved,
            boolean deploymentAuthorized,
            List<String> blockers,
            List<String> limitations
    ) {
        public Content {
            if (!CURRENT_VERSION.equals(assessmentVersion)) {
                throw new IllegalArgumentException("assessmentVersion is unsupported");
            }
            required(assessmentCreatedAt, "assessmentCreatedAt");
            required(evaluatorIdentity, "evaluatorIdentity");
            required(evaluatorVersion, "evaluatorVersion");
            Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
            Objects.requireNonNull(policy, "policy is required");
            Objects.requireNonNull(evidence, "evidence is required");
            Objects.requireNonNull(populationAndCoverage, "populationAndCoverage is required");
            criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria are required"));
            Objects.requireNonNull(assessmentStatus, "assessmentStatus is required");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers are required"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
            if (governanceApproved || deploymentAuthorized) {
                throw new IllegalArgumentException("assessment cannot grant approval or deployment authority");
            }
            if (formalRecommendationEligible != (assessmentStatus == AssessmentStatus.ELIGIBLE_FOR_FORMAL_RECOMMENDATION)) {
                throw new IllegalArgumentException("formal recommendation status is inconsistent");
            }
        }
    }

    public record PolicyReference(
            ModelPromotionPolicyResolution.Status resolutionStatus,
            ModelPromotionPolicyResolution.Reason resolutionReason,
            String policyId,
            Long revision,
            String canonicalSha256,
            ModelPromotionPolicy.PolicyAuthority authority
    ) {
        public PolicyReference {
            Objects.requireNonNull(resolutionStatus, "policy resolutionStatus is required");
            Objects.requireNonNull(resolutionReason, "policy resolutionReason is required");
            if (resolutionStatus == ModelPromotionPolicyResolution.Status.AVAILABLE) {
                required(policyId, "policyId");
                if (revision == null || revision < 1 || canonicalSha256 == null
                        || !canonicalSha256.matches("^[0-9a-f]{64}$") || authority == null) {
                    throw new IllegalArgumentException("available policy reference is incomplete");
                }
            } else if (policyId != null || revision != null || canonicalSha256 != null || authority != null) {
                throw new IllegalArgumentException("unavailable policy reference must not claim identity");
            }
        }
    }

    public record EvidenceReference(
            String evidenceBundleSha256,
            String modelCardManifestSha256,
            String shadowPerformanceManifestSha256,
            ModelPromotionEvaluationWindow evaluationWindow
    ) {
        public EvidenceReference {
            sha256(evidenceBundleSha256, "evidenceBundleSha256");
            sha256(modelCardManifestSha256, "modelCardManifestSha256");
            sha256(shadowPerformanceManifestSha256, "shadowPerformanceManifestSha256");
            Objects.requireNonNull(evaluationWindow, "evaluationWindow is required");
        }
    }

    public record PopulationAndCoverage(
            int recordsConsidered,
            int recordsEvaluated,
            int positiveClassCount,
            int negativeClassCount,
            boolean sourceDatasetTruncated,
            String returnedCohortStatus,
            String sourcePopulationStatus,
            int criticalLineageGapCount
    ) {
        public PopulationAndCoverage {
            if (recordsConsidered < 0 || recordsEvaluated < 0 || positiveClassCount < 0
                    || negativeClassCount < 0 || criticalLineageGapCount < 0) {
                throw new IllegalArgumentException("population values must not be negative");
            }
            required(returnedCohortStatus, "returnedCohortStatus");
            required(sourcePopulationStatus, "sourcePopulationStatus");
        }
    }

    public record CriterionAssessment(
            ModelPromotionCriterion criterion,
            CriterionOutcome outcome,
            String reason,
            Map<String, String> observedValues,
            String evidenceSource,
            String metricSemantics,
            String populationDefinition,
            String denominatorDefinition,
            String requiredVerificationLevel,
            Long maximumEvidenceAgeSeconds
    ) {
        public CriterionAssessment {
            Objects.requireNonNull(criterion, "criterion is required");
            Objects.requireNonNull(outcome, "outcome is required");
            required(reason, "reason");
            observedValues = Map.copyOf(Objects.requireNonNull(observedValues, "observedValues are required"));
            required(evidenceSource, "evidenceSource");
            required(metricSemantics, "metricSemantics");
            required(populationDefinition, "populationDefinition");
            required(denominatorDefinition, "denominatorDefinition");
            required(requiredVerificationLevel, "requiredVerificationLevel");
            if (maximumEvidenceAgeSeconds == null && outcome != CriterionOutcome.UNAVAILABLE) {
                throw new IllegalArgumentException("maximumEvidenceAgeSeconds is required for resolved criteria");
            }
            if (maximumEvidenceAgeSeconds != null && maximumEvidenceAgeSeconds <= 0) {
                throw new IllegalArgumentException("maximumEvidenceAgeSeconds must be positive");
            }
        }
    }

    public enum CriterionOutcome {
        PASS,
        FAIL,
        INCONCLUSIVE,
        UNAVAILABLE,
        NOT_APPLICABLE
    }

    public enum AssessmentStatus {
        NOT_REVIEWABLE,
        REVIEWABLE_BLOCKED,
        ELIGIBLE_FOR_FORMAL_RECOMMENDATION
    }

    private static void sha256(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
