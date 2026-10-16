package com.frauddetection.alert.governance.exactmodelshadowperformance;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;
import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Set;

@Component
public class ExactModelShadowPerformanceValidator {

    private final ModelEvaluationGovernanceValidator modelEvaluationValidator;

    public ExactModelShadowPerformanceValidator(ModelEvaluationGovernanceValidator modelEvaluationValidator) {
        this.modelEvaluationValidator = Objects.requireNonNull(modelEvaluationValidator);
    }

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "reportType", "generatedAt", "populationType", "cohortEligibility",
            "sourceEvaluation", "evaluationSummary", "coverage", "operationalDiagnostics", "governance"
    );
    private static final Set<String> SUBJECT_FIELDS = Set.of(
            "subjectType", "modelName", "modelVersion", "featureContractVersion",
            "modelArtifactSha256", "identityCompleteness"
    );
    private static final Set<String> EVALUATION_FIELDS = Set.of(
            "reportType", "generatedAt", "sourceDataset", "metricBasis", "evaluationSubject",
            "evaluationWindow", "population", "classBalance", "lineagePolicy", "supportedMetrics",
            "limitations", "warnings"
    );
    private static final Set<String> WINDOW_FIELDS = Set.of("timeBasis", "fromInclusive", "toInclusive");
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "reportType", "artifactSetVersion", "reportGeneratedAt", "manifestSha256",
            "artifactIntegrityVerification", "sourceDatasetVerificationLevel", "externalAttestation",
            "immutableStorageGuarantee"
    );
    private static final Set<String> COHORT_FIELDS = Set.of(
            "policy", "eligibleLabels", "requiresExactModelPredictionEvidence", "maximumRecords",
            "metricBasis", "timeBasis"
    );
    private static final Set<String> COVERAGE_FIELDS = Set.of("returnedCohortStatus", "sourcePopulationStatus");
    private static final Set<String> LATENCY_FIELDS = Set.of(
            "available", "valueMilliseconds", "sampleSize", "timeBasis", "reason");
    private static final Set<String> RATE_FIELDS = Set.of(
            "available", "value", "numerator", "denominator", "timeBasis", "reason");
    private static final Set<String> DISTRIBUTION_FIELDS = Set.of(
            "available", "counts", "recordsEvaluated", "timeBasis", "reason");
    private static final Set<String> GOVERNANCE_FIELDS = Set.of(
            "diagnosticOnly", "runtimeDecisionAuthority", "promotionAuthority", "thresholdChangeAuthority",
            "paymentAuthorizationAuthority", "workflowAuthority"
    );

    public void validate(JsonNode summary, ExactModelShadowPerformanceSelector selector) {
        exactFields(summary, ROOT_FIELDS, "summary");
        require("exact-model-shadow-performance-v1".equals(text(summary, "schemaVersion")), "schemaVersion unsupported");
        require("EXACT_MODEL_SHADOW_PERFORMANCE_V1".equals(text(summary, "reportType")), "reportType unsupported");
        timestamp(text(summary, "generatedAt"), "generatedAt");
        require("LABELED_FEEDBACK".equals(text(summary, "populationType")), "populationType unsupported");
        validateCohort(object(summary, "cohortEligibility"));

        JsonNode evaluation = object(summary, "evaluationSummary");
        exactFields(evaluation, EVALUATION_FIELDS, "evaluationSummary");
        require("ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1".equals(text(evaluation, "reportType")),
                "evaluation reportType unsupported");
        require("BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT".equals(text(evaluation, "metricBasis")),
                "evaluation metricBasis unsupported");
        String evaluationGeneratedAt = text(evaluation, "generatedAt");
        timestamp(evaluationGeneratedAt, "evaluationSummary.generatedAt");
        require(!GovernanceTimestampContract.parse(text(summary, "generatedAt"), "generatedAt")
                        .isBefore(GovernanceTimestampContract.parse(
                                evaluationGeneratedAt, "evaluationSummary.generatedAt")),
                "generatedAt must not precede evaluationSummary.generatedAt");
        JsonNode subject = object(evaluation, "evaluationSubject");
        exactFields(subject, SUBJECT_FIELDS, "evaluationSubject");
        require("ML_MODEL".equals(text(subject, "subjectType")), "subjectType unsupported");
        require("COMPLETE".equals(text(subject, "identityCompleteness")), "identity completeness unsupported");
        require(selector.modelName().equals(text(subject, "modelName")), "modelName mismatch");
        require(selector.modelVersion().equals(text(subject, "modelVersion")), "modelVersion mismatch");
        require(selector.featureContractVersion().equals(text(subject, "featureContractVersion")),
                "featureContractVersion mismatch");
        require(selector.modelArtifactSha256().equals(text(subject, "modelArtifactSha256")),
                "modelArtifactSha256 mismatch");

        JsonNode window = object(evaluation, "evaluationWindow");
        exactFields(window, WINDOW_FIELDS, "evaluationWindow");
        require("FEEDBACK_CREATED_AT".equals(text(window, "timeBasis")), "timeBasis unsupported");
        require(Objects.equals(nullableText(window, "fromInclusive"), selector.fromInclusive()), "fromInclusive mismatch");
        require(Objects.equals(nullableText(window, "toInclusive"), selector.toInclusive()), "toInclusive mismatch");

        JsonNode source = object(summary, "sourceEvaluation");
        exactFields(source, SOURCE_FIELDS, "sourceEvaluation");
        require("ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1".equals(text(source, "reportType")),
                "source reportType unsupported");
        require("ml-model-feedback-dataset-evaluation-artifact-set-v1".equals(text(source, "artifactSetVersion")),
                "source artifactSetVersion unsupported");
        timestamp(text(source, "reportGeneratedAt"), "sourceEvaluation.reportGeneratedAt");
        require(text(source, "reportGeneratedAt").equals(text(evaluation, "generatedAt")),
                "source evaluation timestamp mismatch");
        require("VERIFIED_REPORT_AND_MANIFEST".equals(text(source, "artifactIntegrityVerification")),
                "source integrity verification unsupported");
        require(Set.of("CLAIM_ONLY", "VERIFIED_AGAINST_SOURCE_BYTES")
                .contains(text(source, "sourceDatasetVerificationLevel")), "source verification unsupported");
        require("NOT_PROVIDED".equals(text(source, "externalAttestation")), "external attestation unsupported");
        require("NOT_CLAIMED".equals(text(source, "immutableStorageGuarantee")),
                "immutable storage guarantee unsupported");
        require(text(source, "manifestSha256").matches("^[0-9a-f]{64}$"), "manifestSha256 invalid");
        try {
            modelEvaluationValidator.validateEvaluationSummary(
                    evaluation,
                    new ModelEvaluationGovernanceValidator.ExpectedSubject(
                            "ML_MODEL",
                            selector.modelName(),
                            selector.modelVersion(),
                            selector.featureContractVersion(),
                            selector.modelArtifactSha256(),
                            "COMPLETE"
                    ),
                    text(source, "reportGeneratedAt"),
                    text(summary, "generatedAt")
            );
        } catch (ModelEvaluationValidationException exception) {
            throw new ExactModelShadowPerformanceValidationException(
                    "evaluationSummary invalid: " + exception.getMessage());
        }

        JsonNode coverage = object(summary, "coverage");
        exactFields(coverage, COVERAGE_FIELDS, "coverage");
        require(Set.of("COMPLETE", "PARTIAL_COVERAGE", "NO_DATA")
                        .contains(text(coverage, "returnedCohortStatus")),
                "returned cohort status unsupported");
        require(Set.of("BOUNDED_SOURCE_NOT_TRUNCATED", "BOUNDED_SOURCE_TRUNCATED")
                        .contains(text(coverage, "sourcePopulationStatus")),
                "source population status unsupported");
        JsonNode population = object(evaluation, "population");
        int considered = nonNegativeInt(population, "recordsConsidered");
        int evaluated = nonNegativeInt(population, "recordsEvaluated");
        String expectedCoverage = evaluated == 0 ? "NO_DATA"
                : evaluated < considered ? "PARTIAL_COVERAGE" : "COMPLETE";
        require(expectedCoverage.equals(text(coverage, "returnedCohortStatus")),
                "returned cohort status inconsistent");
        JsonNode sourceDataset = object(evaluation, "sourceDataset");
        require(sourceDataset.get("truncated") != null && sourceDataset.get("truncated").isBoolean(),
                "source dataset truncated flag invalid");
        String expectedSourceStatus = sourceDataset.get("truncated").booleanValue()
                ? "BOUNDED_SOURCE_TRUNCATED" : "BOUNDED_SOURCE_NOT_TRUNCATED";
        require(expectedSourceStatus.equals(text(coverage, "sourcePopulationStatus")),
                "source population status inconsistent");

        JsonNode governance = object(summary, "governance");
        exactFields(governance, GOVERNANCE_FIELDS, "governance");
        require(governance.get("diagnosticOnly").isBoolean() && governance.get("diagnosticOnly").booleanValue(),
                "diagnosticOnly must be true");
        for (String field : GOVERNANCE_FIELDS) {
            if (!"diagnosticOnly".equals(field)) {
                require("NONE".equals(text(governance, field)), field + " must be NONE");
            }
        }
        validateOperationalDiagnostics(object(summary, "operationalDiagnostics"));
    }

    private void validateCohort(JsonNode cohort) {
        exactFields(cohort, COHORT_FIELDS, "cohortEligibility");
        require("BINARY_ANALYST_FEEDBACK_WITH_EXACT_ML_PREDICTION".equals(text(cohort, "policy")),
                "cohort policy unsupported");
        require(cohort.get("requiresExactModelPredictionEvidence").isBoolean()
                && cohort.get("requiresExactModelPredictionEvidence").booleanValue(),
                "exact ML evidence must be required");
        require(cohort.get("maximumRecords").isInt() && cohort.get("maximumRecords").intValue() == 1000,
                "maximumRecords unsupported");
        require("BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT".equals(text(cohort, "metricBasis")),
                "cohort metricBasis unsupported");
        require("FEEDBACK_CREATED_AT".equals(text(cohort, "timeBasis")), "cohort timeBasis unsupported");
        JsonNode labels = cohort.get("eligibleLabels");
        require(labels != null && labels.isArray() && labels.size() == 2
                && "CONFIRMED_FRAUD".equals(labels.get(0).textValue())
                && "CONFIRMED_LEGITIMATE".equals(labels.get(1).textValue()), "eligibleLabels unsupported");
    }

    private void validateOperationalDiagnostics(JsonNode diagnostics) {
        require(diagnostics.size() == 3, "operationalDiagnostics field set unsupported");
        JsonNode latency = object(diagnostics, "mlLatencyP95");
        JsonNode fallback = object(diagnostics, "fallbackRate");
        JsonNode reasonCodes = object(diagnostics, "mlReasonCodeDistribution");
        exactFields(latency, LATENCY_FIELDS, "mlLatencyP95");
        exactFields(fallback, RATE_FIELDS, "fallbackRate");
        exactFields(reasonCodes, DISTRIBUTION_FIELDS, "mlReasonCodeDistribution");
        unavailable(latency, "EXACT_ARTIFACT_LATENCY_EVIDENCE_UNAVAILABLE");
        requireNull(latency, "valueMilliseconds", "mlLatencyP95.valueMilliseconds");
        requireNull(latency, "sampleSize", "mlLatencyP95.sampleSize");
        requireNull(latency, "timeBasis", "mlLatencyP95.timeBasis");
        unavailable(fallback, "EXACT_ARTIFACT_FALLBACK_EVIDENCE_UNAVAILABLE");
        requireNull(fallback, "value", "fallbackRate.value");
        requireNull(fallback, "numerator", "fallbackRate.numerator");
        requireNull(fallback, "denominator", "fallbackRate.denominator");
        requireNull(fallback, "timeBasis", "fallbackRate.timeBasis");
        unavailable(reasonCodes,
                "EXACT_ARTIFACT_REASON_CODE_EVIDENCE_UNAVAILABLE");
        JsonNode counts = reasonCodes.get("counts");
        require(counts != null && counts.isObject() && counts.size() == 0,
                "mlReasonCodeDistribution.counts must be empty");
        requireNull(reasonCodes, "recordsEvaluated", "mlReasonCodeDistribution.recordsEvaluated");
        requireNull(reasonCodes, "timeBasis", "mlReasonCodeDistribution.timeBasis");
    }

    private int nonNegativeInt(JsonNode value, String field) {
        JsonNode node = value.get(field);
        require(node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0,
                field + " must be a non-negative integer");
        return node.intValue();
    }

    private void unavailable(JsonNode metric, String reason) {
        require(metric.has("available") && metric.get("available").isBoolean()
                && !metric.get("available").booleanValue(), "operational metric must be unavailable");
        require(reason.equals(text(metric, "reason")), "operational metric reason unsupported");
    }

    private void requireNull(JsonNode value, String field, String label) {
        require(value.has(field) && value.get(field).isNull(), label + " must be null");
    }

    private String nullableText(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        require(node.isTextual(), field + " must be text or null");
        timestamp(node.textValue(), field);
        return node.textValue();
    }

    private void timestamp(String value, String field) {
        try {
            GovernanceTimestampContract.parse(value, field);
        } catch (IllegalArgumentException exception) {
            throw new ExactModelShadowPerformanceValidationException(exception.getMessage());
        }
    }

    private JsonNode object(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isObject(), field + " must be an object");
        return node;
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isTextual(), field + " must be text");
        return node.textValue();
    }

    private void exactFields(JsonNode value, Set<String> expected, String label) {
        require(value != null && value.isObject() && value.size() == expected.size(), label + " field set unsupported");
        expected.forEach(field -> require(value.has(field), label + " missing " + field));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ExactModelShadowPerformanceValidationException(message);
        }
    }
}
