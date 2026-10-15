import { isCanonicalUtcTimestamp, isOrderedCanonicalUtcTimestamp } from "./canonicalUtcTimestamp.js";
import { isValidModelEvaluationSummary } from "./modelCardValidation.js";

const ROOT_FIELDS = [
  "schemaVersion", "reportType", "generatedAt", "populationType", "cohortEligibility",
  "sourceEvaluation", "evaluationSummary", "coverage", "operationalDiagnostics", "governance"
];
const SOURCE_FIELDS = [
  "reportType", "artifactSetVersion", "reportGeneratedAt", "manifestSha256",
  "artifactIntegrityVerification", "sourceDatasetVerificationLevel", "externalAttestation",
  "immutableStorageGuarantee"
];
const COVERAGE_FIELDS = ["returnedCohortStatus", "sourcePopulationStatus"];
const GOVERNANCE_FIELDS = [
  "diagnosticOnly", "runtimeDecisionAuthority", "promotionAuthority", "thresholdChangeAuthority",
  "paymentAuthorizationAuthority", "workflowAuthority"
];
const OPERATIONAL_FIELDS = ["mlLatencyP95", "fallbackRate", "mlReasonCodeDistribution"];
const LATENCY_FIELDS = ["available", "valueMilliseconds", "sampleSize", "timeBasis", "reason"];
const RATE_FIELDS = ["available", "value", "numerator", "denominator", "timeBasis", "reason"];
const DISTRIBUTION_FIELDS = ["available", "counts", "recordsEvaluated", "timeBasis", "reason"];

export function isValidExactModelShadowPerformance(value) {
  if (!exactObject(value, ROOT_FIELDS)
      || value.schemaVersion !== "exact-model-shadow-performance-v1"
      || value.reportType !== "EXACT_MODEL_SHADOW_PERFORMANCE_V1"
      || value.populationType !== "LABELED_FEEDBACK"
      || !validTimestamp(value.generatedAt)) {
    return false;
  }
  const evaluation = value.evaluationSummary;
  const subject = evaluation?.evaluationSubject;
  const window = evaluation?.evaluationWindow;
  const governance = value.governance;
  const diagnostics = value.operationalDiagnostics;
  return evaluation?.reportType === "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
    && evaluation.metricBasis === "BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT"
    && isValidModelEvaluationSummary(
      evaluation,
      evaluation?.evaluationSubject,
      value.sourceEvaluation?.reportGeneratedAt,
      value.generatedAt
    )
    && subject?.subjectType === "ML_MODEL"
    && subject.identityCompleteness === "COMPLETE"
    && identityPart(subject.modelName, 64)
    && identityPart(subject.modelVersion, 64)
    && identityPart(subject.featureContractVersion, 96)
    && /^[0-9a-f]{64}$/.test(subject.modelArtifactSha256 || "")
    && window?.timeBasis === "FEEDBACK_CREATED_AT"
    && optionalTimestamp(window.fromInclusive)
    && optionalTimestamp(window.toInclusive)
    && (window.fromInclusive === null || window.toInclusive === null
      || isOrderedCanonicalUtcTimestamp(window.fromInclusive, window.toInclusive))
    && exactObject(value.sourceEvaluation, SOURCE_FIELDS)
    && value.sourceEvaluation.reportType === "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
    && value.sourceEvaluation.artifactSetVersion === "ml-model-feedback-dataset-evaluation-artifact-set-v1"
    && value.sourceEvaluation?.reportGeneratedAt === evaluation.generatedAt
    && /^[0-9a-f]{64}$/.test(value.sourceEvaluation.manifestSha256 || "")
    && value.sourceEvaluation.artifactIntegrityVerification === "VERIFIED_REPORT_AND_MANIFEST"
    && ["CLAIM_ONLY", "VERIFIED_AGAINST_SOURCE_BYTES"].includes(
      value.sourceEvaluation?.sourceDatasetVerificationLevel
    )
    && value.sourceEvaluation.externalAttestation === "NOT_PROVIDED"
    && value.sourceEvaluation.immutableStorageGuarantee === "NOT_CLAIMED"
    && exactObject(value.coverage, COVERAGE_FIELDS)
    && expectedReturnedCohortStatus(evaluation?.population) === value.coverage.returnedCohortStatus
    && expectedSourcePopulationStatus(evaluation?.sourceDataset) === value.coverage.sourcePopulationStatus
    && exactObject(governance, GOVERNANCE_FIELDS)
    && governance?.diagnosticOnly === true
    && [
      governance.runtimeDecisionAuthority,
      governance.promotionAuthority,
      governance.thresholdChangeAuthority,
      governance.paymentAuthorizationAuthority,
      governance.workflowAuthority
    ].every((authority) => authority === "NONE")
    && exactObject(diagnostics, OPERATIONAL_FIELDS)
    && unavailableLatency(diagnostics.mlLatencyP95)
    && unavailableRate(diagnostics.fallbackRate)
    && unavailableDistribution(diagnostics.mlReasonCodeDistribution);
}

function unavailable(metric, fields, reason) {
  return exactObject(metric, fields)
    && metric.available === false
    && metric.reason === reason;
}

function unavailableLatency(metric) {
  return unavailable(metric, LATENCY_FIELDS, "EXACT_ARTIFACT_LATENCY_EVIDENCE_UNAVAILABLE")
    && metric.valueMilliseconds === null
    && metric.sampleSize === null
    && metric.timeBasis === null;
}

function unavailableRate(metric) {
  return unavailable(metric, RATE_FIELDS, "EXACT_ARTIFACT_FALLBACK_EVIDENCE_UNAVAILABLE")
    && metric.value === null
    && metric.numerator === null
    && metric.denominator === null
    && metric.timeBasis === null;
}

function unavailableDistribution(metric) {
  return unavailable(metric, DISTRIBUTION_FIELDS, "EXACT_ARTIFACT_REASON_CODE_EVIDENCE_UNAVAILABLE")
    && exactObject(metric.counts, [])
    && metric.recordsEvaluated === null
    && metric.timeBasis === null;
}

function expectedReturnedCohortStatus(population) {
  if (!population) return null;
  if (population.recordsEvaluated === 0) return "NO_DATA";
  return population.recordsEvaluated < population.recordsConsidered ? "PARTIAL_COVERAGE" : "COMPLETE";
}

function expectedSourcePopulationStatus(sourceDataset) {
  if (sourceDataset?.truncated === true) return "BOUNDED_SOURCE_TRUNCATED";
  if (sourceDataset?.truncated === false) return "BOUNDED_SOURCE_NOT_TRUNCATED";
  return null;
}

function exactObject(value, fields) {
  return value !== null
    && typeof value === "object"
    && !Array.isArray(value)
    && Object.keys(value).length === fields.length
    && fields.every((field) => Object.hasOwn(value, field));
}

function identityPart(value, maximum) {
  return typeof value === "string" && value.length > 0 && value.length <= maximum
    && /^[A-Za-z0-9._-]+$/.test(value);
}

function optionalTimestamp(value) {
  return value === null || validTimestamp(value);
}

function validTimestamp(value) {
  return isCanonicalUtcTimestamp(value);
}
