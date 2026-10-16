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
const SAFE_IDENTITY = /^[A-Za-z0-9._-]+$/;
const ALLOWED_MODES = ["COMPARE", "OFFLINE_EVALUATION", "SHADOW"];
const REQUIRED_LIMITATIONS = [
  "ANALYST_FEEDBACK_LABELS_ARE_REVIEW_SIGNALS",
  "ML_PREDICTION_METRICS_REQUIRE_DIRECT_ML_OUTPUT_SIGNALS",
  "MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION",
  "MODEL_SPECIFIC_EVALUATION_DOES_NOT_AUTHORIZE_PAYMENTS",
  "MODEL_SPECIFIC_EVALUATION_DOES_NOT_CHANGE_SCORING"
];
const INTENDED_USE = [
  "EXACT_MODEL_OFFLINE_EVALUATION",
  "RULES_VS_ML_DIAGNOSTIC_COMPARISON",
  "SHADOW_MODEL_MONITORING"
];
const NOT_INTENDED_USE = [
  "AUTOMATIC_APPROVE_DECLINE_OR_BLOCK",
  "MODEL_PROMOTION_APPROVAL",
  "PAYMENT_AUTHORIZATION",
  "PRIMARY_PRODUCTION_FRAUD_DECISIONING",
  "SCORING_THRESHOLD_CHANGE",
  "WORKFLOW_OR_ANALYST_RECOMMENDATION"
];
const USE_LIMITATIONS = [
  "ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_GROUND_TRUTH",
  "BOUNDED_RETURNED_POPULATION_ONLY",
  "DOES_NOT_ESTABLISH_GLOBAL_DATASET_COMPLETENESS",
  "DOES_NOT_ESTABLISH_MODEL_CALIBRATION",
  "DOES_NOT_ESTABLISH_PRODUCTION_READINESS",
  "SOURCE_VERIFICATION_LEVEL_MUST_BE_INTERPRETED_EXPLICITLY"
];
const RISK_CLASSIFICATION_POLICY = "RISK_HIGH_OR_CRITICAL_POSITIVE_V1";
const FLOAT_BUFFER = new ArrayBuffer(8);
const FLOAT_VIEW = new DataView(FLOAT_BUFFER);
const FORBIDDEN_IDENTITY_TERMS = [
  "accountid", "apikey", "authorization", "bearer", "cardid", "correlationid", "customerid",
  "deviceid", "email", "endpoint", "exceptionmessage", "feedbackid", "finaldecision",
  "groundtruth", "idempotencykey", "merchantid", "metadata", "modeltraininglabel", "password",
  "paymentauthorization", "rawfeaturevector", "rawmlrequest", "rawmlresponse", "rawpayload",
  "rawrequest", "rawresponse", "requestpayloadhash", "secret", "stacktrace", "submittedby",
  "token", "traininglabel", "transactionid"
];

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
  return isValidModelEvaluationSummary(summary, subject, evidence.reportGeneratedAt, cardGeneratedAt);
}

export function isValidModelEvaluationSummary(summary, subject, reportGeneratedAt, cardGeneratedAt) {
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
      || !isValidSupportedMetrics(metrics, population.recordsEvaluated, classBalance)
      || !sameOrderedValues(summary.limitations, REQUIRED_LIMITATIONS)
      || !sameOrderedValues(summary.warnings, expectedWarnings(sourceDataset, population, classBalance))) {
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
    && isValidIdentity(subject.modelName, 64)
    && isValidIdentity(subject.modelVersion, 64)
    && isValidIdentity(subject.featureContractVersion, 96)
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
      "intendedUse",
      "notIntendedUse",
      "useLimitations",
      "runtimeDecisionAuthority",
      "promotionAuthority",
      "thresholdChangeAuthority",
      "paymentAuthorizationAuthority",
      "workflowAuthority"
    ])
    && Array.isArray(boundary.allowedModes)
    && boundary.allowedModes.length === ALLOWED_MODES.length
    && boundary.allowedModes.every((mode, index) => mode === ALLOWED_MODES[index])
    && sameOrderedValues(boundary.intendedUse, INTENDED_USE)
    && sameOrderedValues(boundary.notIntendedUse, NOT_INTENDED_USE)
    && sameOrderedValues(boundary.useLimitations, USE_LIMITATIONS)
    && [
      boundary.runtimeDecisionAuthority,
      boundary.promotionAuthority,
      boundary.thresholdChangeAuthority,
      boundary.paymentAuthorizationAuthority,
      boundary.workflowAuthority
    ].every((authority) => authority === "NONE");
}

function sameOrderedValues(actual, expected) {
  return Array.isArray(actual)
    && actual.length === expected.length
    && actual.every((value, index) => value === expected[index]);
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
    && source.datasetVersion === "feedback-dataset-v3"
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

function isValidSupportedMetrics(metrics, recordsEvaluated, classBalance) {
  const prediction = metrics?.mlPredictionMetrics;
  const tp = prediction?.truePositive;
  const fp = prediction?.falsePositive;
  const tn = prediction?.trueNegative;
  const fn = prediction?.falseNegative;
  return isPlainObject(metrics)
    && hasExactKeys(metrics, ["classBalance", "mlPredictionMetrics", "rulesVsMlDisagreement"])
    && isPlainObject(metrics.classBalance)
    && hasExactKeys(metrics.classBalance, ["available", "reason"])
    && isExpectedAvailability(
      metrics.classBalance,
      recordsEvaluated > 0,
      "INSUFFICIENT_MODEL_LINEAGE_RECORDS"
    )
    && isPlainObject(prediction)
    && hasExactKeys(prediction, ML_METRIC_FIELDS)
    && isExpectedAvailability(prediction, recordsEvaluated > 0, "MODEL_PREDICTION_SIGNAL_UNAVAILABLE")
    && prediction.classificationPolicy === RISK_CLASSIFICATION_POLICY
    && prediction.rankingPolicy === "EXACT_ML_PREDICTION_SCORE_DESC_EVALUATION_RECORD_ID_ASC_V1"
    && prediction.recordsEvaluated === recordsEvaluated
    && ["truePositive", "falsePositive", "trueNegative", "falseNegative"].every((field) => isCount(prediction[field]))
    && tp + fn === classBalance.positiveClassCount
    && tn + fp === classBalance.negativeClassCount
    && tp + fp + tn + fn === recordsEvaluated
    && isExpectedMetric(prediction.precision, tp, tp + fp, "NO_PREDICTED_POSITIVES")
    && isExpectedMetric(prediction.recall, tp, tp + fn, "NO_ACTUAL_POSITIVES")
    && isExpectedMetric(prediction.truePositiveRate, tp, tp + fn, "NO_ACTUAL_POSITIVES")
    && isExpectedMetric(prediction.falsePositiveRate, fp, fp + tn, "NO_ACTUAL_NEGATIVES")
    && isExpectedMetric(prediction.falseNegativeRate, fn, fn + tp, "NO_ACTUAL_POSITIVES")
    && isValidRankedMetrics(
      prediction.precisionAtK,
      prediction.recallAtK,
      recordsEvaluated,
      classBalance.positiveClassCount
    )
    && isValidDisagreement(
      metrics.rulesVsMlDisagreement,
      recordsEvaluated,
      classBalance.positiveClassCount
    );
}

function isValidDisagreement(disagreement, recordsEvaluated, positiveClassCount) {
  const categoryFields = ["ML_HIGH_RULES_LOW", "RULES_HIGH_ML_LOW", "BOTH_HIGH", "BOTH_LOW"];
  return isPlainObject(disagreement)
    && hasExactKeys(disagreement, [
      "available",
      "reason",
      "classificationPolicy",
      "recordsWithExactMlEvidence",
      "recordsCompared",
      "recordsExcludedRulesEvidenceUnavailable",
      "categoryCounts",
      "disagreementRate",
      "analystLabeledFraudByCategory",
      "analystLabeledFraudRecordsCompared",
      "analystLabeledFraudRecordsExcludedRulesEvidenceUnavailable"
    ])
    && disagreement.classificationPolicy === RISK_CLASSIFICATION_POLICY
    && disagreement.recordsWithExactMlEvidence === recordsEvaluated
    && isCount(disagreement.recordsCompared)
    && isCount(disagreement.recordsExcludedRulesEvidenceUnavailable)
    && isPlainObject(disagreement.categoryCounts)
    && hasExactKeys(disagreement.categoryCounts, categoryFields)
    && Object.values(disagreement.categoryCounts).every((count) => isCount(count))
    && disagreement.recordsCompared + disagreement.recordsExcludedRulesEvidenceUnavailable === recordsEvaluated
    && Object.values(disagreement.categoryCounts).reduce((total, count) => total + count, 0) === disagreement.recordsCompared
    && isExpectedMetric(
      disagreement.disagreementRate,
      disagreement.categoryCounts.ML_HIGH_RULES_LOW + disagreement.categoryCounts.RULES_HIGH_ML_LOW,
      disagreement.recordsCompared,
      "RULES_SIGNAL_UNAVAILABLE"
    )
    && isPlainObject(disagreement.analystLabeledFraudByCategory)
    && hasExactKeys(disagreement.analystLabeledFraudByCategory, categoryFields)
    && Object.values(disagreement.analystLabeledFraudByCategory).every((count) => isCount(count))
    && categoryFields.every((field) => disagreement.analystLabeledFraudByCategory[field]
      <= disagreement.categoryCounts[field])
    && isCount(disagreement.analystLabeledFraudRecordsCompared)
    && isCount(disagreement.analystLabeledFraudRecordsExcludedRulesEvidenceUnavailable)
    && Object.values(disagreement.analystLabeledFraudByCategory)
      .reduce((total, count) => total + count, 0) === disagreement.analystLabeledFraudRecordsCompared
    && disagreement.analystLabeledFraudRecordsCompared
      + disagreement.analystLabeledFraudRecordsExcludedRulesEvidenceUnavailable === positiveClassCount
    && isExpectedAvailability(disagreement, disagreement.recordsCompared > 0, "RULES_SIGNAL_UNAVAILABLE");
}

function isExpectedAvailability(value, expectedAvailable, unavailableReason) {
  return isPlainObject(value)
    && typeof value.available === "boolean"
    && value.available === expectedAvailable
    && value.reason === (expectedAvailable ? null : unavailableReason);
}

function isExpectedMetric(metric, numerator, denominator, unavailableReason) {
  if (!isPlainObject(metric) || !hasExactKeys(metric, ["available", "reason", "value"])) {
    return false;
  }
  if (denominator === 0) {
    return metric.available === false && metric.reason === unavailableReason && metric.value === null;
  }
  return metric.available === true
    && metric.reason === null
    && Number.isFinite(metric.value)
    && metric.value === roundedRatio(numerator, denominator);
}

function expectedWarnings(sourceDataset, population, classBalance) {
  const warnings = [];
  if (sourceDataset.truncated) warnings.push("SOURCE_DATASET_TRUNCATED");
  if (sourceDataset.skippedMissingRequiredFieldCount > 0) {
    warnings.push("SOURCE_DATASET_REQUIRED_FIELDS_MISSING");
  }
  if (sourceDataset.skippedInvalidSourceRecordCount > 0) {
    warnings.push("SOURCE_DATASET_INVALID_ROWS_SKIPPED");
  }
  if (population.recordsEvaluated === 0) {
    warnings.push("INSUFFICIENT_MODEL_LINEAGE_RECORDS", "MODEL_PREDICTION_SIGNAL_UNAVAILABLE");
  } else if (classBalance.positiveClassCount === 0 || classBalance.negativeClassCount === 0) {
    warnings.push("SINGLE_CLASS_MODEL_LINEAGE_RECORDS");
  }
  if (population.recordsExcludedUnexpectedMissingPredictionEvidence > 0) {
    warnings.push("UNEXPECTED_ML_EVIDENCE_LOSS");
  }
  if (population.recordsExcludedInvalidPredictionEvidence > 0) {
    warnings.push("INVALID_ML_EVIDENCE_PRESENT");
  }
  if (population.recordsEvaluated < population.recordsConsidered) {
    warnings.push("MODEL_EVALUATION_PARTIAL_COVERAGE");
  }
  return warnings.sort();
}

function roundedRatio(numerator, denominator) {
  const value = numerator / denominator;
  FLOAT_VIEW.setFloat64(0, value, false);
  const high = FLOAT_VIEW.getUint32(0, false);
  const low = FLOAT_VIEW.getUint32(4, false);
  const exponentBits = (high >>> 20) & 0x7ff;
  const fraction = (BigInt(high & 0xfffff) << 32n) | BigInt(low);
  const significand = exponentBits === 0 ? fraction : (1n << 52n) | fraction;
  const exponent = exponentBits === 0 ? -1074 : exponentBits - 1023 - 52;
  let scaledNumerator = significand * 1_000_000n;
  let exactDenominator = 1n;
  if (exponent >= 0) {
    scaledNumerator <<= BigInt(exponent);
  } else {
    exactDenominator <<= BigInt(-exponent);
  }
  let rounded = scaledNumerator / exactDenominator;
  const remainder = scaledNumerator % exactDenominator;
  if (remainder * 2n > exactDenominator
      || (remainder * 2n === exactDenominator && rounded % 2n === 1n)) {
    rounded += 1n;
  }
  return Number(rounded) / 1_000_000;
}

function isValidRankedMetrics(precisionAtK, recallAtK, recordsEvaluated, totalPositive) {
  if (!isPlainObject(precisionAtK)
      || !isPlainObject(recallAtK)
      || Object.keys(precisionAtK).length === 0
      || Object.keys(precisionAtK).length > 32
      || !sameKeys(precisionAtK, recallAtK)) {
    return false;
  }
  const prefixes = [];
  for (const [key, precisionRow] of Object.entries(precisionAtK)) {
    const recallRow = recallAtK[key];
    if (!isRankingRow(precisionRow, key) || !isRankingRow(recallRow, key)
        || recallRow.requestedK !== precisionRow.requestedK
        || recallRow.actualK !== precisionRow.actualK
        || precisionRow.actualK !== Math.min(precisionRow.requestedK, recordsEvaluated)) {
      return false;
    }
    const matchingPositiveCounts = [];
    for (let positiveAtK = 0; positiveAtK <= Math.min(precisionRow.actualK, totalPositive); positiveAtK += 1) {
      const precisionMatches = isExpectedMetric(
        precisionRow.value,
        positiveAtK,
        precisionRow.actualK,
        "NO_SCORED_RECORDS"
      );
      const recallMatches = isExpectedMetric(
        recallRow.value,
        recordsEvaluated > 0 ? positiveAtK : 0,
        recordsEvaluated > 0 ? totalPositive : 0,
        recordsEvaluated > 0 ? "NO_POSITIVE_RECORDS" : "NO_SCORED_RECORDS"
      );
      if (precisionMatches && recallMatches) {
        matchingPositiveCounts.push(positiveAtK);
      }
    }
    if (matchingPositiveCounts.length !== 1) {
      return false;
    }
    prefixes.push({
      requestedK: precisionRow.requestedK,
      actualK: precisionRow.actualK,
      positiveCount: matchingPositiveCounts[0]
    });
  }
  prefixes.sort((left, right) => left.requestedK - right.requestedK);
  let previousActualK = 0;
  let previousPositiveCount = 0;
  for (const prefix of prefixes) {
    if (prefix.actualK < previousActualK
        || prefix.positiveCount < previousPositiveCount
        || prefix.positiveCount - previousPositiveCount > prefix.actualK - previousActualK) {
      return false;
    }
    previousActualK = prefix.actualK;
    previousPositiveCount = prefix.positiveCount;
  }
  return previousActualK !== recordsEvaluated || previousPositiveCount === totalPositive;
}

function isRankingRow(row, key) {
  return isPlainObject(row)
    && hasExactKeys(row, ["requestedK", "actualK", "value"])
    && Number.isInteger(row.requestedK)
    && row.requestedK > 0
    && row.requestedK <= 1000
    && String(row.requestedK) === key
    && isCount(row.actualK);
}

function isValidIdentity(value, maximumLength) {
  if (typeof value !== "string"
      || value.length === 0
      || value.length > maximumLength
      || !SAFE_IDENTITY.test(value)) {
    return false;
  }
  const compact = value.toLowerCase().replace(/[^a-z0-9]/g, "");
  return FORBIDDEN_IDENTITY_TERMS.every((term) => !compact.includes(term));
}

function sameSubject(actual, expected) {
  return isValidSubject(actual) && SUBJECT_FIELDS.every((field) => actual[field] === expected[field]);
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
