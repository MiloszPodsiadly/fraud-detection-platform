const PROMOTION_REVIEW_READINESS_REPORT_TYPE = "PROMOTION_REVIEW_READINESS_REPORT_V1";
const PROMOTION_REVIEW_READINESS_REPORT_VERSION = "1.0";
const PROMOTION_REVIEW_READINESS_GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY";
const PROMOTION_REVIEW_READINESS_STATUSES = new Set([
  "INSUFFICIENT_DATA",
  "INCONCLUSIVE",
  "NOT_REVIEWABLE",
  "REVIEWABLE"
]);
const CHECK_STATUSES = new Set(["PASS", "FAIL", "INCONCLUSIVE"]);
const CHECK_SEVERITY_VALUES = new Set(["INFO", "LOW", "MEDIUM", "HIGH"]);
const MAX_DIAGNOSTIC_RECORDS = 1000;
const MAX_BANNER_LENGTH = 512;
const MAX_CHECKS = 50;
const MAX_MACHINE_CODE_ITEMS = 20;
const MAX_CHECK_NAME_LENGTH = 128;
const MAX_MACHINE_CODE_LENGTH = 128;
const MAX_SUMMARY_VERSION_LENGTH = 32;
const MACHINE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,127}$/;
const RFC3339_TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const REQUIRED_CHECK_NAMES = [
  "CURRENT_SUMMARY_PRESENT",
  "CURRENT_SUMMARY_VERSION_SUPPORTED",
  "EVALUATION_CARD_PRESENT",
  "EVALUATION_CARD_VERSION_SUPPORTED",
  "GOVERNANCE_STATUS_DIAGNOSTIC_ONLY",
  "NOT_PRODUCTION_APPROVAL_TRUE",
  "NOT_PROMOTION_APPROVAL_TRUE",
  "NOT_THRESHOLD_RECOMMENDATION_TRUE",
  "NOT_PAYMENT_AUTHORIZATION_TRUE",
  "NOT_AUTOMATIC_DECISIONING_TRUE",
  "EVALUATION_REPORT_TYPE_SUPPORTED",
  "METRIC_BASIS_SUPPORTED",
  "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS",
  "ALERT_RECOMMENDED_PRECISION_AVAILABLE",
  "ALERT_RECOMMENDED_RECALL_AVAILABLE",
  "FALSE_POSITIVE_RATE_AVAILABLE",
  "FALSE_NEGATIVE_RATE_AVAILABLE"
];
const REQUIRED_CHECK_NAME_SET = new Set(REQUIRED_CHECK_NAMES);
const CHECK_SEVERITIES = new Map([
  ["CURRENT_SUMMARY_PRESENT", "INFO"],
  ["CURRENT_SUMMARY_VERSION_SUPPORTED", "INFO"],
  ["EVALUATION_CARD_PRESENT", "INFO"],
  ["EVALUATION_CARD_VERSION_SUPPORTED", "INFO"],
  ["GOVERNANCE_STATUS_DIAGNOSTIC_ONLY", "INFO"],
  ["NOT_PRODUCTION_APPROVAL_TRUE", "INFO"],
  ["NOT_PROMOTION_APPROVAL_TRUE", "INFO"],
  ["NOT_THRESHOLD_RECOMMENDATION_TRUE", "INFO"],
  ["NOT_PAYMENT_AUTHORIZATION_TRUE", "INFO"],
  ["NOT_AUTOMATIC_DECISIONING_TRUE", "INFO"],
  ["EVALUATION_REPORT_TYPE_SUPPORTED", "INFO"],
  ["METRIC_BASIS_SUPPORTED", "INFO"],
  ["MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", "HIGH"],
  ["ALERT_RECOMMENDED_PRECISION_AVAILABLE", "MEDIUM"],
  ["ALERT_RECOMMENDED_RECALL_AVAILABLE", "MEDIUM"],
  ["FALSE_POSITIVE_RATE_AVAILABLE", "MEDIUM"],
  ["FALSE_NEGATIVE_RATE_AVAILABLE", "MEDIUM"]
]);
const REQUIRED_LIMITATIONS = new Set([
  "OFFLINE_DIAGNOSTIC_AID_ONLY",
  "HUMAN_REVIEW_START_ONLY",
  "DOES_NOT_RECOMMEND_THRESHOLDS",
  "DOES_NOT_AUTHORIZE_PAYMENTS",
  "DOES_NOT_CHANGE_SCORING"
]);
const FORBIDDEN_RAW_TERMS = [
  "transactionReference",
  "evaluationRecordId",
  "rawPayload",
  "rawFeatureVector",
  "rawMlRequest",
  "rawMlResponse",
  "groundTruth",
  "trainingLabel",
  "finalDecision",
  "secret",
  "token",
  "stacktrace",
  "stack trace",
  "filesystem",
  "file path",
  "C:\\",
  "/var/",
  "/tmp/",
  "/home/",
  "/users/"
];
const FORBIDDEN_DECISIONING_TERMS = [
  "APPROVED",
  "PROMOTED",
  "READY_FOR_PRODUCTION",
  "DEPLOYABLE",
  "RECOMMENDED_THRESHOLD",
  "THRESHOLD_RECOMMENDATION",
  "PAYMENT_AUTHORIZED",
  "AUTO_APPROVE",
  "AUTO_DECLINE",
  "BLOCK_TRANSACTION",
  "ANALYST_RECOMMENDATION"
];
const FORBIDDEN_PATH_TERMS = FORBIDDEN_RAW_TERMS
  .filter((term) => term.includes("\\") || term.includes("/"))
  .map((term) => term.toLowerCase());
const FORBIDDEN_RAW_COMPACT_TERMS = FORBIDDEN_RAW_TERMS
  .filter((term) => !term.includes("\\") && !term.includes("/"))
  .map(compactText);
const FORBIDDEN_DECISIONING_COMPACT_TERMS = FORBIDDEN_DECISIONING_TERMS.map(compactText);

export function isValidPromotionReviewReadinessReport(report) {
  return isPlainObject(report)
    && hasExactKeys(report, [
      "reportType",
      "reportVersion",
      "generatedAt",
      "governanceStatus",
      "readinessStatus",
      "diagnosticOnly",
      "notPromotionApproval",
      "notThresholdRecommendation",
      "notProductionDecisioning",
      "notPaymentAuthorization",
      "notAutomaticDecisioning",
      "notAnalystRecommendation",
      "inputs",
      "checkInputs",
      "checks",
      "reasonCodes",
      "warnings",
      "limitations",
      "banner"
    ])
    && report.reportType === PROMOTION_REVIEW_READINESS_REPORT_TYPE
    && report.reportVersion === PROMOTION_REVIEW_READINESS_REPORT_VERSION
    && isStrictRfc3339Timestamp(report.generatedAt)
    && report.governanceStatus === PROMOTION_REVIEW_READINESS_GOVERNANCE_STATUS
    && PROMOTION_REVIEW_READINESS_STATUSES.has(report.readinessStatus)
    && report.diagnosticOnly === true
    && report.notPromotionApproval === true
    && report.notThresholdRecommendation === true
    && report.notProductionDecisioning === true
    && report.notPaymentAuthorization === true
    && report.notAutomaticDecisioning === true
    && report.notAnalystRecommendation === true
    && isBoundedNonEmptyString(report.banner, MAX_BANNER_LENGTH)
    && isValidInputs(report.inputs, report.generatedAt)
    && isValidCheckInputs(report.checkInputs)
    && inputsMatchCheckInputs(report.inputs, report.checkInputs)
    && isValidChecks(report.checks)
    && isSameCheckList(report.checks, checksFromInputs(report.checkInputs))
    && report.readinessStatus === deriveReadinessStatus(report.checks)
    && isExactReasonCodes(report.reasonCodes, report.checks)
    && isValidMachineCodeList(report.warnings)
    && isValidMachineCodeList(report.limitations)
    && containsRequiredLimitations(report.limitations);
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isValidInputs(inputs, reportGeneratedAt) {
  const shadowPerformanceSummary = inputs?.shadowPerformanceSummary;
  return isPlainObject(inputs)
    && hasExactKeys(inputs, ["shadowPerformanceSummary", "minimumDiagnosticEvidenceRecords", "recordsEvaluated"])
    && isPlainObject(shadowPerformanceSummary)
    && hasExactKeys(shadowPerformanceSummary, ["present", "reportType", "summaryVersion", "generatedAt"])
    && shadowPerformanceSummary.present === true
    && shadowPerformanceSummary.reportType === "SHADOW_PERFORMANCE_SUMMARY_V2"
    && isBoundedString(shadowPerformanceSummary.summaryVersion, MAX_SUMMARY_VERSION_LENGTH)
    && shadowPerformanceSummary.summaryVersion === "shadow-performance-summary-v2"
    && !containsForbiddenTerm(shadowPerformanceSummary.reportType)
    && !containsForbiddenTerm(shadowPerformanceSummary.summaryVersion)
    && isStrictRfc3339Timestamp(shadowPerformanceSummary.generatedAt)
    && isOrderedTimestamp(shadowPerformanceSummary.generatedAt, reportGeneratedAt)
    && isBoundedInteger(inputs.minimumDiagnosticEvidenceRecords, 1, MAX_DIAGNOSTIC_RECORDS)
    && isBoundedInteger(inputs.recordsEvaluated, 0, MAX_DIAGNOSTIC_RECORDS);
}

function isValidCheckInputs(inputs) {
  const summary = inputs?.shadowPerformanceSummary;
  const governance = inputs?.governance;
  const evaluation = inputs?.evaluation;
  const metrics = inputs?.metrics;
  return isPlainObject(inputs)
    && hasExactKeys(inputs, [
      "sourceShadowSummaryManifestSha256",
      "shadowPerformanceSummary",
      "governance",
      "evaluation",
      "metricBasis",
      "minimumDiagnosticEvidenceRecords",
      "recordsEvaluated",
      "metrics"
    ])
    && typeof inputs.sourceShadowSummaryManifestSha256 === "string"
    && SHA256_PATTERN.test(inputs.sourceShadowSummaryManifestSha256)
    && isPlainObject(summary)
    && hasExactKeys(summary, ["present", "reportType", "summaryVersion", "generatedAt", "sourceEvaluationCardManifestSha256"])
    && summary.present === true
    && summary.reportType === "SHADOW_PERFORMANCE_SUMMARY_V2"
    && summary.summaryVersion === "shadow-performance-summary-v2"
    && isStrictRfc3339Timestamp(summary.generatedAt)
    && typeof summary.sourceEvaluationCardManifestSha256 === "string"
    && SHA256_PATTERN.test(summary.sourceEvaluationCardManifestSha256)
    && isPlainObject(governance)
    && hasExactKeys(governance, [
      "governanceStatus",
      "diagnosticOnly",
      "notProductionApproval",
      "notPromotionApproval",
      "notThresholdRecommendation",
      "notPaymentAuthorization",
      "notAutomaticDecisioning"
    ])
    && governance.governanceStatus === PROMOTION_REVIEW_READINESS_GOVERNANCE_STATUS
    && governance.diagnosticOnly === true
    && governance.notProductionApproval === true
    && governance.notPromotionApproval === true
    && governance.notThresholdRecommendation === true
    && governance.notPaymentAuthorization === true
    && governance.notAutomaticDecisioning === true
    && isPlainObject(evaluation)
    && hasExactKeys(evaluation, ["evaluationCardType", "evaluationCardVersion", "evaluationReportType"])
    && isBoundedNonEmptyString(evaluation.evaluationCardType, 128)
    && isBoundedNonEmptyString(evaluation.evaluationCardVersion, 128)
    && isBoundedNonEmptyString(evaluation.evaluationReportType, 128)
    && inputs.metricBasis === "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK"
    && isBoundedInteger(inputs.minimumDiagnosticEvidenceRecords, 1, MAX_DIAGNOSTIC_RECORDS)
    && isBoundedInteger(inputs.recordsEvaluated, 0, MAX_DIAGNOSTIC_RECORDS)
    && isPlainObject(metrics)
    && hasExactKeys(metrics, [
      "alertRecommendedPrecision",
      "alertRecommendedRecall",
      "falsePositiveRate",
      "falseNegativeRate"
    ])
    && isMetric(metrics.alertRecommendedPrecision)
    && isMetric(metrics.alertRecommendedRecall)
    && isMetric(metrics.falsePositiveRate)
    && isMetric(metrics.falseNegativeRate);
}

function inputsMatchCheckInputs(inputs, checkInputs) {
  return inputs.shadowPerformanceSummary.present === checkInputs.shadowPerformanceSummary.present
    && inputs.shadowPerformanceSummary.reportType === checkInputs.shadowPerformanceSummary.reportType
    && inputs.shadowPerformanceSummary.summaryVersion === checkInputs.shadowPerformanceSummary.summaryVersion
    && inputs.shadowPerformanceSummary.generatedAt === checkInputs.shadowPerformanceSummary.generatedAt
    && inputs.minimumDiagnosticEvidenceRecords === checkInputs.minimumDiagnosticEvidenceRecords
    && inputs.recordsEvaluated === checkInputs.recordsEvaluated;
}

function isValidChecks(checks) {
  if (!Array.isArray(checks) || checks.length !== REQUIRED_CHECK_NAMES.length || checks.length > MAX_CHECKS) {
    return false;
  }
  const names = new Set();
  for (const check of checks) {
    if (!isPlainObject(check)
        || !hasExactKeys(check, ["name", "status", "severity"])
        || !isBoundedNonEmptyString(check.name, MAX_CHECK_NAME_LENGTH)
        || !REQUIRED_CHECK_NAME_SET.has(check.name)
        || !CHECK_STATUSES.has(check.status)
        || (check.status === "INCONCLUSIVE" && !check.name.endsWith("_AVAILABLE"))
        || !CHECK_SEVERITY_VALUES.has(check.severity)
        || CHECK_SEVERITIES.get(check.name) !== check.severity) {
      return false;
    }
    if (names.has(check.name)) {
      return false;
    }
    names.add(check.name);
  }
  return REQUIRED_CHECK_NAMES.every((name) => names.has(name));
}

function isValidMachineCodeList(values) {
  return Array.isArray(values)
    && values.length <= MAX_MACHINE_CODE_ITEMS
    && new Set(values).size === values.length
    && values.every((value) => isMachineCode(value) && !containsForbiddenTerm(value));
}

function isMachineCode(value) {
  return isBoundedNonEmptyString(value, MAX_MACHINE_CODE_LENGTH) && MACHINE_CODE_PATTERN.test(value);
}

function isBoundedNonEmptyString(value, maxLength) {
  return typeof value === "string" && value.trim().length > 0 && value.length <= maxLength;
}

function isBoundedString(value, maxLength) {
  return typeof value === "string" && value.length <= maxLength;
}

function isBoundedInteger(value, min, max) {
  return Number.isInteger(value) && value >= min && value <= max;
}

function isStrictRfc3339Timestamp(value) {
  if (!isBoundedNonEmptyString(value, 128) || !RFC3339_TIMESTAMP_PATTERN.test(value)) {
    return false;
  }
  if (!hasValidCalendarDate(value)) {
    return false;
  }
  const time = Date.parse(value);
  return Number.isFinite(time) && new Date(time).toISOString() === new Date(value).toISOString();
}

function checksFromInputs(inputs) {
  return [
    check("CURRENT_SUMMARY_PRESENT", passFail(inputs.shadowPerformanceSummary.present === true)),
    check("CURRENT_SUMMARY_VERSION_SUPPORTED", passFail(inputs.shadowPerformanceSummary.summaryVersion === "shadow-performance-summary-v2")),
    check("EVALUATION_CARD_PRESENT", passFail(inputs.evaluation.evaluationCardType === "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1")),
    check("EVALUATION_CARD_VERSION_SUPPORTED", passFail(inputs.evaluation.evaluationCardVersion === "platform-recommendation-evaluation-card-v1")),
    check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY", passFail(inputs.governance.governanceStatus === PROMOTION_REVIEW_READINESS_GOVERNANCE_STATUS)),
    check("NOT_PRODUCTION_APPROVAL_TRUE", passFail(inputs.governance.notProductionApproval === true)),
    check("NOT_PROMOTION_APPROVAL_TRUE", passFail(inputs.governance.notPromotionApproval === true)),
    check("NOT_THRESHOLD_RECOMMENDATION_TRUE", passFail(inputs.governance.notThresholdRecommendation === true)),
    check("NOT_PAYMENT_AUTHORIZATION_TRUE", passFail(inputs.governance.notPaymentAuthorization === true)),
    check("NOT_AUTOMATIC_DECISIONING_TRUE", passFail(inputs.governance.notAutomaticDecisioning === true)),
    check("EVALUATION_REPORT_TYPE_SUPPORTED", passFail(inputs.evaluation.evaluationReportType === "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1")),
    check("METRIC_BASIS_SUPPORTED", passFail(inputs.metricBasis === "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK")),
    check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", passFail(inputs.recordsEvaluated >= inputs.minimumDiagnosticEvidenceRecords), "HIGH"),
    metricCheck("ALERT_RECOMMENDED_PRECISION_AVAILABLE", inputs.metrics.alertRecommendedPrecision),
    metricCheck("ALERT_RECOMMENDED_RECALL_AVAILABLE", inputs.metrics.alertRecommendedRecall),
    metricCheck("FALSE_POSITIVE_RATE_AVAILABLE", inputs.metrics.falsePositiveRate),
    metricCheck("FALSE_NEGATIVE_RATE_AVAILABLE", inputs.metrics.falseNegativeRate)
  ];
}

function isSameCheckList(actual, expected) {
  return Array.isArray(actual)
    && actual.length === expected.length
    && actual.every((check, index) => (
      check.name === expected[index].name
      && check.status === expected[index].status
      && check.severity === expected[index].severity
    ));
}

function check(name, status, severity = "INFO") {
  return { name, status, severity };
}

function metricCheck(name, metric) {
  return check(name, metric.available === true ? "PASS" : "INCONCLUSIVE", "MEDIUM");
}

function passFail(condition) {
  return condition ? "PASS" : "FAIL";
}

function isMetric(metric) {
  if (!isPlainObject(metric) || !hasExactKeys(metric, ["available", "value", "reason"]) || typeof metric.available !== "boolean") {
    return false;
  }
  if (metric.available) {
    return typeof metric.value === "number"
      && Number.isFinite(metric.value)
      && metric.value >= 0
      && metric.value <= 1
      && metric.reason === null;
  }
  return metric.value === null && isMachineCode(metric.reason);
}

function isOrderedTimestamp(earlier, later) {
  if (!isStrictRfc3339Timestamp(earlier) || !isStrictRfc3339Timestamp(later)) {
    return false;
  }
  return Date.parse(earlier) <= Date.parse(later);
}

function containsForbiddenTerm(value) {
  const lower = String(value || "").toLowerCase();
  const compact = compactText(value);
  return FORBIDDEN_PATH_TERMS.some((term) => lower.includes(term))
    || FORBIDDEN_RAW_COMPACT_TERMS.some((term) => compact.includes(term))
    || FORBIDDEN_DECISIONING_COMPACT_TERMS.some((term) => compact.includes(term));
}

function compactText(value) {
  return String(value || "").replace(/[^A-Za-z0-9]/g, "").toLowerCase();
}

function hasExactKeys(value, expectedKeys) {
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}

function deriveReadinessStatus(checks) {
  const failedChecks = checks.filter((check) => check.status === "FAIL");
  if (failedChecks.some((check) => check.name !== "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS")) {
    return "NOT_REVIEWABLE";
  }
  if (failedChecks.some((check) => check.name === "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS")) {
    return "INSUFFICIENT_DATA";
  }
  return checks.some((check) => check.status === "INCONCLUSIVE") ? "INCONCLUSIVE" : "REVIEWABLE";
}

function derivedReasonCodes(checks) {
  return checks
    .flatMap((check) => {
      if (check.status === "FAIL") {
        return [`${check.name}_FAILED`];
      }
      if (check.status === "INCONCLUSIVE") {
        return [`${check.name}_INCONCLUSIVE`];
      }
      return [];
    })
    .sort();
}

function isExactReasonCodes(reasonCodes, checks) {
  if (!Array.isArray(reasonCodes)
      || reasonCodes.length > MAX_MACHINE_CODE_ITEMS
      || new Set(reasonCodes).size !== reasonCodes.length
      || !reasonCodes.every(isMachineCode)) {
    return false;
  }
  const expected = derivedReasonCodes(checks);
  return reasonCodes.length === expected.length && reasonCodes.every((code, index) => code === expected[index]);
}

function containsRequiredLimitations(limitations) {
  return Array.isArray(limitations) && [...REQUIRED_LIMITATIONS].every((limitation) => limitations.includes(limitation));
}

function hasValidCalendarDate(value) {
  const [, year, month, day] = /^(\d{4})-(\d{2})-(\d{2})T/.exec(value) || [];
  if (!year) {
    return false;
  }
  const parsed = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  return parsed.getUTCFullYear() === Number(year)
    && parsed.getUTCMonth() === Number(month) - 1
    && parsed.getUTCDate() === Number(day);
}
