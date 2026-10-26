package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelPromotionEvidenceBundle(Content content, String canonicalSha256) {

    public static final String CURRENT_VERSION = "model-promotion-evidence-bundle-v1";

    public ModelPromotionEvidenceBundle {
        Objects.requireNonNull(content, "content is required");
        sha256(canonicalSha256, "canonicalSha256");
    }

    public record Content(
            String bundleVersion,
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            ModelPromotionEvaluationWindow evaluationWindow,
            ModelCardEvidence modelCard,
            ShadowPerformanceEvidence shadowPerformance,
            EvaluationEvidence evaluation,
            EvidenceTrust trust,
            List<String> limitations
    ) {
        public Content {
            if (!CURRENT_VERSION.equals(bundleVersion)) {
                throw new IllegalArgumentException("bundleVersion is unsupported");
            }
            Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
            Objects.requireNonNull(governanceScope, "governanceScope is required");
            Objects.requireNonNull(intendedUse, "intendedUse is required");
            Objects.requireNonNull(evaluationWindow, "evaluationWindow is required");
            Objects.requireNonNull(modelCard, "modelCard is required");
            Objects.requireNonNull(shadowPerformance, "shadowPerformance is required");
            Objects.requireNonNull(evaluation, "evaluation is required");
            Objects.requireNonNull(trust, "trust is required");
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
            if (limitations.isEmpty()) {
                throw new IllegalArgumentException("limitations must not be empty");
            }
            PopulationEvidence population = evaluation.population();
            if (!artifactIdentity.modelArtifactSha256().equals(modelCard.modelArtifactSha256())) {
                throw new IllegalArgumentException("Model Card artifact identity is inconsistent");
            }
            if (shadowPerformance.recordsConsidered() != population.recordsConsidered()
                    || shadowPerformance.recordsEvaluated() != population.recordsEvaluated()
                    || !shadowPerformance.returnedCohortStatus().equals(population.returnedCohortStatus())
                    || !shadowPerformance.sourcePopulationStatus().equals(population.sourcePopulationStatus())) {
                throw new IllegalArgumentException("shadow performance population evidence is inconsistent");
            }
        }
    }

    public record EvaluationEvidence(
            PopulationEvidence population,
            Map<Integer, RankedMetricEvidence> mlPrecisionAtReviewBudget,
            FairPrecisionComparisonEvidence fairPrecisionComparison,
            RateMetricEvidence falsePositiveRate,
            RateMetricEvidence rulesVsMlDisagreement,
            SegmentRegressionEvidence criticalSegmentRegressions,
            LatencyEvidence exactModelLatencyP95,
            RateMetricEvidence exactModelFallbackRate,
            LineageEvidence lineage
    ) {
        public EvaluationEvidence {
            Objects.requireNonNull(population, "evaluation.population is required");
            mlPrecisionAtReviewBudget = Map.copyOf(Objects.requireNonNull(
                    mlPrecisionAtReviewBudget, "evaluation.mlPrecisionAtReviewBudget is required"));
            Objects.requireNonNull(fairPrecisionComparison, "evaluation.fairPrecisionComparison is required");
            Objects.requireNonNull(falsePositiveRate, "evaluation.falsePositiveRate is required");
            Objects.requireNonNull(rulesVsMlDisagreement, "evaluation.rulesVsMlDisagreement is required");
            Objects.requireNonNull(criticalSegmentRegressions,
                    "evaluation.criticalSegmentRegressions is required");
            Objects.requireNonNull(exactModelLatencyP95, "evaluation.exactModelLatencyP95 is required");
            Objects.requireNonNull(exactModelFallbackRate, "evaluation.exactModelFallbackRate is required");
            Objects.requireNonNull(lineage, "evaluation.lineage is required");
            mlPrecisionAtReviewBudget.forEach((budget, metric) -> {
                if (budget == null || budget != metric.requestedBudget()
                        || metric.actualBudget() > population.recordsEvaluated()) {
                    throw new IllegalArgumentException("ranked precision population is inconsistent");
                }
            });
            if (fairPrecisionComparison.available()
                    && fairPrecisionComparison.actualBudget() > population.recordsEvaluated()) {
                throw new IllegalArgumentException("fair precision population is inconsistent");
            }
            if (falsePositiveRate.available()
                    && falsePositiveRate.denominator() != population.negativeClassCount()) {
                throw new IllegalArgumentException("false-positive denominator does not match negative class");
            }
            if (rulesVsMlDisagreement.available()
                    && rulesVsMlDisagreement.denominator() > population.recordsEvaluated()) {
                throw new IllegalArgumentException("disagreement denominator exceeds evaluated population");
            }
            if (lineage.criticalGapCount() > population.recordsConsidered()) {
                throw new IllegalArgumentException("lineage gaps exceed considered population");
            }
        }
    }

    public record PopulationEvidence(
            int recordsConsidered,
            int recordsEvaluated,
            int positiveClassCount,
            int negativeClassCount,
            boolean sourceDatasetTruncated,
            String returnedCohortStatus,
            String sourcePopulationStatus
    ) {
        public PopulationEvidence {
            if (recordsConsidered < 0 || recordsEvaluated < 0 || recordsEvaluated > recordsConsidered
                    || positiveClassCount < 0 || negativeClassCount < 0
                    || positiveClassCount + negativeClassCount != recordsEvaluated) {
                throw new IllegalArgumentException("evaluation population is inconsistent");
            }
            required(returnedCohortStatus, "evaluation.returnedCohortStatus");
            required(sourcePopulationStatus, "evaluation.sourcePopulationStatus");
        }
    }

    public record RankedMetricEvidence(
            boolean available,
            int requestedBudget,
            int actualBudget,
            BigDecimal value,
            String reason
    ) {
        public RankedMetricEvidence {
            if (requestedBudget <= 0 || actualBudget < 0 || actualBudget > requestedBudget) {
                throw new IllegalArgumentException("ranked metric budget is inconsistent");
            }
            availability(available, value, reason, "ranked metric");
        }
    }

    public record FairPrecisionComparisonEvidence(
            boolean available,
            Integer requestedBudget,
            Integer actualBudget,
            BigDecimal mlPrecision,
            BigDecimal rulesPrecision,
            String matchedPopulationIdentity,
            String reason
    ) {
        public FairPrecisionComparisonEvidence {
            if (available) {
                if (requestedBudget == null || requestedBudget <= 0 || actualBudget == null
                        || !requestedBudget.equals(actualBudget) || mlPrecision == null
                        || rulesPrecision == null || matchedPopulationIdentity == null
                        || matchedPopulationIdentity.isBlank() || reason != null) {
                    throw new IllegalArgumentException("fair precision comparison is inconsistent");
                }
            } else if (requestedBudget != null || actualBudget != null || mlPrecision != null
                    || rulesPrecision != null || matchedPopulationIdentity != null
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("unavailable fair precision comparison is inconsistent");
            }
        }
    }

    public record RateMetricEvidence(
            boolean available,
            BigDecimal value,
            Integer numerator,
            Integer denominator,
            String timeBasis,
            String reason
    ) {
        public RateMetricEvidence {
            availability(available, value, reason, "rate metric");
            if (available && (numerator == null || numerator < 0 || denominator == null
                    || denominator <= 0 || numerator > denominator)) {
                throw new IllegalArgumentException("available rate metric denominator is inconsistent");
            }
            if (!available && (numerator != null || denominator != null)) {
                throw new IllegalArgumentException("unavailable rate metric must not expose counts");
            }
        }
    }

    public record SegmentRegressionEvidence(
            boolean available,
            Integer segmentsEvaluated,
            Integer criticalRegressionCount,
            String segmentDefinitionIdentity,
            String reason
    ) {
        public SegmentRegressionEvidence {
            if (available) {
                if (segmentsEvaluated == null || segmentsEvaluated < 0 || criticalRegressionCount == null
                        || criticalRegressionCount < 0 || criticalRegressionCount > segmentsEvaluated
                        || segmentDefinitionIdentity == null || segmentDefinitionIdentity.isBlank()
                        || reason != null) {
                    throw new IllegalArgumentException("segment regression evidence is inconsistent");
                }
            } else if (segmentsEvaluated != null || criticalRegressionCount != null
                    || segmentDefinitionIdentity != null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("unavailable segment regression evidence is inconsistent");
            }
        }
    }

    public record LatencyEvidence(
            boolean available,
            BigDecimal valueMilliseconds,
            Integer sampleSize,
            String timeBasis,
            String reason
    ) {
        public LatencyEvidence {
            availability(available, valueMilliseconds, reason, "latency evidence");
            if (available && (sampleSize == null || sampleSize <= 0 || timeBasis == null || timeBasis.isBlank())) {
                throw new IllegalArgumentException("available latency evidence is inconsistent");
            }
            if (!available && (sampleSize != null || timeBasis != null)) {
                throw new IllegalArgumentException("unavailable latency evidence must not expose observations");
            }
        }
    }

    public record LineageEvidence(
            String identityMismatchBehavior,
            int recordsExcludedIdentityMismatch,
            int recordsExcludedSourceIdentityMismatch,
            int recordsExcludedInvalidPredictionEvidence,
            int recordsExcludedMissingPredictionEvidence,
            int recordsExcludedUnexpectedMissingPredictionEvidence
    ) {
        public LineageEvidence {
            required(identityMismatchBehavior, "lineage.identityMismatchBehavior");
            if (recordsExcludedIdentityMismatch < 0 || recordsExcludedSourceIdentityMismatch < 0
                    || recordsExcludedInvalidPredictionEvidence < 0
                    || recordsExcludedMissingPredictionEvidence < 0
                    || recordsExcludedUnexpectedMissingPredictionEvidence < 0) {
                throw new IllegalArgumentException("lineage exclusion counts must not be negative");
            }
        }

        public int criticalGapCount() {
            return recordsExcludedIdentityMismatch + recordsExcludedSourceIdentityMismatch
                    + recordsExcludedInvalidPredictionEvidence
                    + recordsExcludedMissingPredictionEvidence
                    + recordsExcludedUnexpectedMissingPredictionEvidence;
        }
    }

    public record ModelCardEvidence(
            String manifestSha256,
            String modelArtifactSha256,
            String generatedAt,
            String sourceEvaluationManifestSha256,
            String sourceDatasetVerificationLevel
    ) {
        public ModelCardEvidence {
            sha256(manifestSha256, "modelCard.manifestSha256");
            sha256(modelArtifactSha256, "modelCard.modelArtifactSha256");
            sha256(sourceEvaluationManifestSha256, "modelCard.sourceEvaluationManifestSha256");
            required(generatedAt, "modelCard.generatedAt");
            required(sourceDatasetVerificationLevel, "modelCard.sourceDatasetVerificationLevel");
        }
    }

    public record ShadowPerformanceEvidence(
            String manifestSha256,
            String generatedAt,
            String sourceEvaluationManifestSha256,
            String returnedCohortStatus,
            String sourcePopulationStatus,
            int recordsConsidered,
            int recordsEvaluated,
            boolean operationalEvidenceComplete
    ) {
        public ShadowPerformanceEvidence {
            sha256(manifestSha256, "shadowPerformance.manifestSha256");
            sha256(sourceEvaluationManifestSha256, "shadowPerformance.sourceEvaluationManifestSha256");
            required(generatedAt, "shadowPerformance.generatedAt");
            required(returnedCohortStatus, "shadowPerformance.returnedCohortStatus");
            required(sourcePopulationStatus, "shadowPerformance.sourcePopulationStatus");
            if (recordsConsidered < 0 || recordsEvaluated < 0 || recordsEvaluated > recordsConsidered) {
                throw new IllegalArgumentException("shadow performance population is inconsistent");
            }
        }
    }

    public record EvidenceTrust(
            String artifactIntegrityVerification,
            String sourceDatasetVerificationLevel,
            String externalAttestation,
            String immutableStorageGuarantee
    ) {
        public EvidenceTrust {
            required(artifactIntegrityVerification, "trust.artifactIntegrityVerification");
            required(sourceDatasetVerificationLevel, "trust.sourceDatasetVerificationLevel");
            required(externalAttestation, "trust.externalAttestation");
            required(immutableStorageGuarantee, "trust.immutableStorageGuarantee");
        }
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

    private static void availability(boolean available, BigDecimal value, String reason, String field) {
        if (available && (value == null || reason != null)) {
            throw new IllegalArgumentException(field + " available value is inconsistent");
        }
        if (!available && (value != null || reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(field + " unavailable value is inconsistent");
        }
    }
}
