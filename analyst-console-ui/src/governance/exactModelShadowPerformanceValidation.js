const ROOT_FIELDS = [
  "schemaVersion", "reportType", "generatedAt", "populationType", "cohortEligibility",
  "sourceEvaluation", "evaluationSummary", "coverage", "operationalDiagnostics", "governance"
];
const SOURCE_FIELDS = [
  "reportType", "artifactSetVersion", "reportGeneratedAt", "manifestSha256",
  "artifactIntegrityVerification", "sourceDatasetVerificationLevel", "externalAttestation",
  "immutableStorageGuarantee"
];
const COVERAGE_FIELDS = ["status", "sourceTruncated", "sourceInvalid"];
const GOVERNANCE_FIELDS = [
  "diagnosticOnly", "runtimeDecisionAuthority", "promotionAuthority", "thresholdChangeAuthority",
  "paymentAuthorizationAuthority", "workflowAuthority"
];
const OPERATIONAL_FIELDS = ["mlLatencyP95", "fallbackRate", "mlReasonCodeDistribution"];

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
    && subject?.subjectType === "ML_MODEL"
    && subject.identityCompleteness === "COMPLETE"
    && identityPart(subject.modelName, 64)
    && identityPart(subject.modelVersion, 64)
    && identityPart(subject.featureContractVersion, 96)
    && /^[0-9a-f]{64}$/.test(subject.modelArtifactSha256 || "")
    && window?.timeBasis === "FEEDBACK_CREATED_AT"
    && optionalTimestamp(window.fromInclusive)
    && optionalTimestamp(window.toInclusive)
    && exactObject(value.sourceEvaluation, SOURCE_FIELDS)
    && value.sourceEvaluation.reportType === "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
    && value.sourceEvaluation.artifactSetVersion === "ml-model-feedback-dataset-evaluation-artifact-set-v1"
    && value.sourceEvaluation?.reportGeneratedAt === evaluation.generatedAt
    && ["CLAIM_ONLY", "VERIFIED_AGAINST_SOURCE_BYTES"].includes(
      value.sourceEvaluation?.sourceDatasetVerificationLevel
    )
    && exactObject(value.coverage, COVERAGE_FIELDS)
    && ["COMPLETE", "PARTIAL_COVERAGE", "NO_DATA"].includes(value.coverage.status)
    && value.coverage?.sourceInvalid === false
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
    && unavailable(diagnostics.mlLatencyP95, "EXACT_ARTIFACT_LATENCY_EVIDENCE_UNAVAILABLE")
    && unavailable(diagnostics?.fallbackRate, "EXACT_ARTIFACT_FALLBACK_EVIDENCE_UNAVAILABLE")
    && unavailable(diagnostics?.mlReasonCodeDistribution, "EXACT_ARTIFACT_REASON_CODE_EVIDENCE_UNAVAILABLE");
}

function unavailable(metric, reason) {
  return metric?.available === false && metric.reason === reason;
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
  return typeof value === "string" && !Number.isNaN(Date.parse(value));
}
