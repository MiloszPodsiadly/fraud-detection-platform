const PROMOTION_REVIEW_READINESS_REPORT_TYPE = "PROMOTION_REVIEW_READINESS_REPORT_V1";
const PROMOTION_REVIEW_READINESS_REPORT_VERSION = "1.0";
const PROMOTION_REVIEW_READINESS_GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY";
const PROMOTION_REVIEW_READINESS_STATUSES = new Set([
  "INSUFFICIENT_DATA",
  "NOT_REVIEWABLE",
  "REVIEWABLE"
]);
const CHECK_STATUSES = new Set(["PASS", "WARN", "FAIL", "NOT_APPLICABLE"]);
const CHECK_SEVERITIES = new Set(["INFO", "LOW", "MEDIUM", "HIGH"]);
const MAX_DIAGNOSTIC_RECORDS = 500;
const MAX_BANNER_LENGTH = 512;
const MAX_CHECKS = 50;
const MAX_MACHINE_CODE_ITEMS = 20;
const MAX_CHECK_NAME_LENGTH = 128;
const MAX_MACHINE_CODE_LENGTH = 128;
const MAX_SUMMARY_TYPE_LENGTH = 128;
const MAX_SUMMARY_VERSION_LENGTH = 32;
const MACHINE_CODE_PATTERN = /^[A-Z0-9_-]+$/;
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
    && report.reportType === PROMOTION_REVIEW_READINESS_REPORT_TYPE
    && report.reportVersion === PROMOTION_REVIEW_READINESS_REPORT_VERSION
    && isParseableDateString(report.generatedAt)
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
    && isValidInputs(report.inputs)
    && isValidChecks(report.checks, report.readinessStatus)
    && isValidMachineCodeList(report.reasonCodes)
    && isValidMachineCodeList(report.warnings)
    && isValidMachineCodeList(report.limitations);
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isValidInputs(inputs) {
  const shadowPerformanceSummary = inputs?.shadowPerformanceSummary;
  return isPlainObject(inputs)
    && isPlainObject(shadowPerformanceSummary)
    && typeof shadowPerformanceSummary.present === "boolean"
    && isBoundedString(shadowPerformanceSummary.summaryType, MAX_SUMMARY_TYPE_LENGTH)
    && isBoundedString(shadowPerformanceSummary.summaryVersion, MAX_SUMMARY_VERSION_LENGTH)
    && !containsForbiddenTerm(shadowPerformanceSummary.summaryType)
    && !containsForbiddenTerm(shadowPerformanceSummary.summaryVersion)
    && isOptionalParseableDateString(shadowPerformanceSummary.generatedAt)
    && isBoundedInteger(inputs.minimumDiagnosticEvidenceRecords, 1, MAX_DIAGNOSTIC_RECORDS)
    && isBoundedInteger(inputs.recordsAcceptedForEvaluation, 0, MAX_DIAGNOSTIC_RECORDS);
}

function isValidChecks(checks, readinessStatus) {
  if (!Array.isArray(checks) || checks.length < 1 || checks.length > MAX_CHECKS) {
    return false;
  }
  const names = new Set();
  let hasFail = false;
  for (const check of checks) {
    if (!isPlainObject(check)
        || !isBoundedNonEmptyString(check.name, MAX_CHECK_NAME_LENGTH)
        || !CHECK_STATUSES.has(check.status)
        || !CHECK_SEVERITIES.has(check.severity)
        || containsForbiddenTerm(check.name)) {
      return false;
    }
    if (names.has(check.name)) {
      return false;
    }
    names.add(check.name);
    if (check.status === "FAIL") {
      hasFail = true;
    }
  }
  return !(readinessStatus === "REVIEWABLE" && hasFail);
}

function isValidMachineCodeList(values) {
  return Array.isArray(values)
    && values.length <= MAX_MACHINE_CODE_ITEMS
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

function isParseableDateString(value) {
  return isBoundedNonEmptyString(value, 128) && !Number.isNaN(Date.parse(value));
}

function isOptionalParseableDateString(value) {
  return value === "" || value === null || value === undefined || isParseableDateString(value);
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
