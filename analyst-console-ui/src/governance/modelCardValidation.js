import { isCanonicalUtcTimestamp, isOrderedCanonicalUtcTimestamp } from "./canonicalUtcTimestamp.js";

const ROOT_FIELDS = [
  "schemaVersion",
  "cardType",
  "generatedAt",
  "subject",
  "promotionState",
  "modelArtifactVerification",
  "evaluationEvidence",
  "evaluationSummary",
  "verifiedUsageBoundary"
];
const SUBJECT_FIELDS = [
  "subjectType",
  "modelName",
  "modelVersion",
  "featureContractVersion",
  "modelArtifactSha256",
  "identityCompleteness"
];
const AVAILABLE_EVIDENCE_FIELDS = [
  "status",
  "reportType",
  "artifactSetVersion",
  "reportGeneratedAt",
  "manifestSha256",
  "integrityVerification",
  "sourceDatasetVerificationLevel"
];
const SUMMARY_FIELDS = [
  "reportType",
  "generatedAt",
  "sourceDataset",
  "metricBasis",
  "evaluationSubject",
  "evaluationWindow",
  "population",
  "classBalance",
  "lineagePolicy",
  "supportedMetrics",
  "limitations",
  "warnings"
];
const POPULATION_FIELDS = [
  "recordsConsidered",
  "recordsWithPredictionEvidence",
  "recordsEvaluated",
  "recordsExcludedIdentityMismatch",
  "recordsExcludedSourceIdentityMismatch",
  "recordsExcludedMissingPredictionEvidence",
  "recordsExcludedUnexpectedMissingPredictionEvidence",
  "recordsExcludedInvalidPredictionEvidence"
];
const ML_METRIC_FIELDS = [
  "available",
  "reason",
  "classificationPolicy",
  "rankingPolicy",
  "recordsEvaluated",
  "truePositive",
  "falsePositive",
  "trueNegative",
  "falseNegative",
  "precision",
  "recall",
  "truePositiveRate",
  "falsePositiveRate",
  "falseNegativeRate",
  "precisionAtK",
  "recallAtK"
];
const SHA256 = /^[a-f0-9]{64}$/;
const SAFE_IDENTITY = /^[A-Za-z0-9._:-]{1,120}$/;
const MACHINE_CODE = /^[A-Z][A-Z0-9_]{0,127}$/;
const ALLOWED_MODES = ["COMPARE", "OFFLINE_EVALUATION", "SHADOW"];
const RISK_CLASSIFICATION_POLICY = "RISK_HIGH_OR_CRITICAL_POSITIVE_V1";

export function isValidModelCard(card) {
  if (!isPlainObject(card)
      || !hasExactKeys(card, ROOT_FIELDS)
      || card.schemaVersion !== "ml-model-card-v1"
      || card.cardType !== "ML_MODEL_CARD_V1"
      || !isCanonicalUtcTimestamp(card.generatedAt)
      || card.promotionState !== "NOT_EVALUATED_FOR_PROMOTION"
      || !isValidSubject(card.subject)
      || !isValidArtifactVerification(card.modelArtifactVerification, card.subject)
      || !isValidUsageBoundary(card.verifiedUsageBoundary)) {
    return false;
  }
  return isValidEvaluation(card.evaluationEvidence, card.evaluationSummary, card.subject, card.generatedAt);
}

function isValidEvaluation(evidence, summary, subject, cardGeneratedAt) {
  if (!isPlainObject(evidence)) {
    return false;
  }
  if (evidence.status === "NOT_AVAILABLE") {
    return hasExactKeys(evidence, ["status", "reason"])
      && evidence.reason === "MODEL_EVALUATION_NOT_CONFIGURED"
      && summary === null;
  }
  if (evidence.status !== "AVAILABLE"
      || !hasExactKeys(evidence, AVAILABLE_EVIDENCE_FIELDS)
      || evidence.reportType !== "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
      || evidence.artifactSetVersion !== "ml-model-feedback-dataset-evaluation-artifact-set-v1"
      || !isCanonicalUtcTimestamp(evidence.reportGeneratedAt)
      || !SHA256.test(evidence.manifestSha256)
      || evidence.integrityVerification !== "VERIFIED_REPORT_AND_MANIFEST"
      || !["CLAIM_ONLY", "VERIFIED_AGAINST_SOURCE_BYTES"].includes(evidence.sourceDatasetVerificationLevel)) {
    return false;
  }
  return isValidSummary(summary, subject, evidence.reportGeneratedAt, cardGeneratedAt);
}

function isValidSummary(summary, subject, reportGeneratedAt, cardGeneratedAt) {
  const population = summary?.population;
  const classBalance = summary?.classBalance;
  const sourceDataset = summary?.sourceDataset;
  const metrics = summary?.supportedMetrics;
  if (!isPlainObject(summary)
      || !hasExactKeys(summary, SUMMARY_FIELDS)
      || summary.reportType !== "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
      || summary.generatedAt !== reportGeneratedAt
      || !isOrderedCanonicalUtcTimestamp(summary.generatedAt, cardGeneratedAt)
      || summary.metricBasis !== "BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_ARTIFACT"
      || !sameSubject(summary.evaluationSubject, subject)
      || !isValidEvaluationWindow(summary.evaluationWindow)
      || !isValidPopulation(population)
      || !isPlainObject(classBalance)
      || !hasExactKeys(classBalance, ["positiveClassCount", "negativeClassCount"])
      || !isCount(classBalance.positiveClassCount)
      || !isCount(classBalance.negativeClassCount)
      || classBalance.positiveClassCount + classBalance.negativeClassCount !== population.recordsEvaluated
      || !isValidSourceDataset(sourceDataset)
      || sourceDataset.recordsReturned !== population.recordsConsidered
      || !isValidLineagePolicy(summary.lineagePolicy)
      || !isValidSupportedMetrics(metrics, population.recordsEvaluated)
      || !isMachineCodeList(summary.limitations)
      || !isMachineCodeList(summary.warnings)) {
    return false;
  }
  return population.recordsConsidered === population.recordsEvaluated
    + population.recordsExcludedIdentityMismatch
    + population.recordsExcludedSourceIdentityMismatch
    + population.recordsExcludedMissingPredictionEvidence
    + population.recordsExcludedUnexpectedMissingPredictionEvidence
    + population.recordsExcludedInvalidPredictionEvidence
    && population.recordsWithPredictionEvidence === population.recordsEvaluated
      + population.recordsExcludedIdentityMismatch;
}

function isValidSubject(subject) {
  return isPlainObject(subject)
    && hasExactKeys(subject, SUBJECT_FIELDS)
    && subject.subjectType === "ML_MODEL"
    && SAFE_IDENTITY.test(subject.modelName)
    && SAFE_IDENTITY.test(subject.modelVersion)
    && SAFE_IDENTITY.test(subject.featureContractVersion)
    && SHA256.test(subject.modelArtifactSha256)
    && subject.identityCompleteness === "COMPLETE";
}

function isValidArtifactVerification(verification, subject) {
  return isPlainObject(verification)
    && hasExactKeys(verification, ["status", "sha256"])
    && verification.status === "VERIFIED_AGAINST_ARTIFACT_BYTES"
    && verification.sha256 === subject.modelArtifactSha256;
}

function isValidUsageBoundary(boundary) {
  return isPlainObject(boundary)
    && hasExactKeys(boundary, [
      "allowedModes",
      "runtimeDecisionAuthority",
      "promotionAuthority",
      "thresholdChangeAuthority",
      "paymentAuthorizationAuthority",
      "workflowAuthority"
    ])
    && Array.isArray(boundary.allowedModes)
    && boundary.allowedModes.length === ALLOWED_MODES.length
    && boundary.allowedModes.every((mode, index) => mode === ALLOWED_MODES[index])
    && [
      boundary.runtimeDecisionAuthority,
      boundary.promotionAuthority,
      boundary.thresholdChangeAuthority,
      boundary.paymentAuthorizationAuthority,
      boundary.workflowAuthority
    ].every((authority) => authority === "NONE");
}

function isValidEvaluationWindow(window) {
  if (!isPlainObject(window) || !hasExactKeys(window, ["timeBasis", "fromInclusive", "toInclusive"])) {
    return false;
  }
  if (window.timeBasis !== "FEEDBACK_CREATED_AT") {
    return false;
  }
  const validFrom = window.fromInclusive === null || isCanonicalUtcTimestamp(window.fromInclusive);
  const validTo = window.toInclusive === null || isCanonicalUtcTimestamp(window.toInclusive);
  return validFrom
    && validTo
    && (window.fromInclusive === null
      || window.toInclusive === null
      || isOrderedCanonicalUtcTimestamp(window.fromInclusive, window.toInclusive));
}

function isValidPopulation(population) {
  return isPlainObject(population)
    && hasExactKeys(population, POPULATION_FIELDS)
    && POPULATION_FIELDS.every((field) => isCount(population[field]));
}

function isValidSourceDataset(source) {
  const countFields = [
    "rawRowsRead",
    "recordsReturned",
    "excludedUnresolvedCount",
    "excludedGovernanceReviewCount",
    "skippedMissingRequiredFieldCount",
    "skippedInvalidSourceRecordCount"
  ];
  return isPlainObject(source)
    && hasExactKeys(source, ["datasetVersion", "sha256", ...countFields, "truncated"])
    && SAFE_IDENTITY.test(source.datasetVersion)
    && SHA256.test(source.sha256)
    && countFields.every((field) => field === "rawRowsRead" ? isCount(source[field], 1001) : isCount(source[field]))
    && typeof source.truncated === "boolean"
    && source.rawRowsRead === source.recordsReturned
      + source.excludedUnresolvedCount
      + source.excludedGovernanceReviewCount
      + source.skippedMissingRequiredFieldCount
      + source.skippedInvalidSourceRecordCount
      + (source.truncated ? 1 : 0);
}

function isValidLineagePolicy(policy) {
  return isPlainObject(policy)
    && hasExactKeys(policy, ["policy", "identityMismatchBehavior", "identityMismatchReason"])
    && policy.policy === "REQUESTED_EXACT_ARTIFACT_IDENTITY"
    && policy.identityMismatchBehavior === "EXCLUDE"
    && policy.identityMismatchReason === "MODEL_IDENTITY_MISMATCH";
}

function isValidSupportedMetrics(metrics, recordsEvaluated) {
  const prediction = metrics?.mlPredictionMetrics;
  return isPlainObject(metrics)
    && hasExactKeys(metrics, ["classBalance", "mlPredictionMetrics", "rulesVsMlDisagreement"])
    && isAvailability(metrics.classBalance)
    && isPlainObject(prediction)
    && hasExactKeys(prediction, ML_METRIC_FIELDS)
    && typeof prediction.available === "boolean"
    && (prediction.available ? prediction.reason === null : isMachineCode(prediction.reason))
    && prediction.classificationPolicy === RISK_CLASSIFICATION_POLICY
    && prediction.rankingPolicy === "EXACT_ML_PREDICTION_SCORE_DESC_EVALUATION_RECORD_ID_ASC_V1"
    && prediction.recordsEvaluated === recordsEvaluated
    && ["truePositive", "falsePositive", "trueNegative", "falseNegative"].every((field) => isCount(prediction[field]))
    && ["precision", "recall", "truePositiveRate", "falsePositiveRate", "falseNegativeRate"].every(
      (field) => isMetricValue(prediction[field])
    )
    && isRankingMap(prediction.precisionAtK)
    && isRankingMap(prediction.recallAtK)
    && sameKeys(prediction.precisionAtK, prediction.recallAtK)
    && isValidDisagreement(metrics.rulesVsMlDisagreement, recordsEvaluated);
}

function isValidDisagreement(disagreement, recordsEvaluated) {
  return isPlainObject(disagreement)
    && hasExactKeys(disagreement, [
      "available",
      "reason",
      "classificationPolicy",
      "recordsWithExactMlEvidence",
      "recordsCompared",
      "recordsExcludedRulesEvidenceUnavailable",
      "categoryCounts"
    ])
    && typeof disagreement.available === "boolean"
    && (disagreement.available ? disagreement.reason === null : isMachineCode(disagreement.reason))
    && disagreement.classificationPolicy === RISK_CLASSIFICATION_POLICY
    && disagreement.recordsWithExactMlEvidence === recordsEvaluated
    && isCount(disagreement.recordsCompared)
    && isCount(disagreement.recordsExcludedRulesEvidenceUnavailable)
    && isPlainObject(disagreement.categoryCounts)
    && hasExactKeys(disagreement.categoryCounts, ["ML_HIGH_RULES_LOW", "RULES_HIGH_ML_LOW", "BOTH_HIGH", "BOTH_LOW"])
    && Object.values(disagreement.categoryCounts).every((count) => isCount(count))
    && disagreement.recordsCompared + disagreement.recordsExcludedRulesEvidenceUnavailable === recordsEvaluated
    && Object.values(disagreement.categoryCounts).reduce((total, count) => total + count, 0) === disagreement.recordsCompared;
}

function isAvailability(value) {
  return isPlainObject(value)
    && hasExactKeys(value, ["available", "reason"])
    && typeof value.available === "boolean"
    && (value.reason === null || isMachineCode(value.reason));
}

function isMetricValue(metric) {
  if (!isPlainObject(metric) || !hasExactKeys(metric, ["available", "reason", "value"])) {
    return false;
  }
  return metric.available === true
    ? metric.reason === null && Number.isFinite(metric.value) && metric.value >= 0 && metric.value <= 1
    : metric.available === false && metric.value === null && isMachineCode(metric.reason);
}

function isRankingMap(value) {
  return isPlainObject(value)
    && Object.keys(value).length > 0
    && Object.entries(value).every(([key, row]) => (
      isPlainObject(row)
      && hasExactKeys(row, ["requestedK", "actualK", "value"])
      && String(row.requestedK) === key
      && Number.isInteger(row.requestedK)
      && row.requestedK > 0
      && isCount(row.actualK)
      && isMetricValue(row.value)
    ));
}

function sameSubject(actual, expected) {
  return isValidSubject(actual) && SUBJECT_FIELDS.every((field) => actual[field] === expected[field]);
}

function isMachineCodeList(values) {
  return Array.isArray(values)
    && values.length <= 20
    && new Set(values).size === values.length
    && values.every(isMachineCode);
}

function isMachineCode(value) {
  return typeof value === "string" && MACHINE_CODE.test(value);
}

function isCount(value, maximum = 1000) {
  return Number.isInteger(value) && value >= 0 && value <= maximum;
}

function sameKeys(left, right) {
  const leftKeys = Object.keys(left).sort();
  const rightKeys = Object.keys(right).sort();
  return leftKeys.length === rightKeys.length && leftKeys.every((key, index) => key === rightKeys[index]);
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function hasExactKeys(value, expectedKeys) {
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}
