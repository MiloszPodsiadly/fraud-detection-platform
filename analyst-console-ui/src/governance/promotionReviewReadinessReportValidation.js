const PROMOTION_REVIEW_READINESS_REPORT_TYPE = "PROMOTION_REVIEW_READINESS_REPORT_V1";
const PROMOTION_REVIEW_READINESS_REPORT_VERSION = "1.0";
const PROMOTION_REVIEW_READINESS_GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY";
const PROMOTION_REVIEW_READINESS_STATUSES = new Set([
  "INSUFFICIENT_DATA",
  "NOT_REVIEWABLE",
  "REVIEWABLE"
]);

export function isValidPromotionReviewReadinessReport(report) {
  return isPlainObject(report)
    && report.reportType === PROMOTION_REVIEW_READINESS_REPORT_TYPE
    && report.reportVersion === PROMOTION_REVIEW_READINESS_REPORT_VERSION
    && report.governanceStatus === PROMOTION_REVIEW_READINESS_GOVERNANCE_STATUS
    && PROMOTION_REVIEW_READINESS_STATUSES.has(report.readinessStatus)
    && report.diagnosticOnly === true
    && report.notPromotionApproval === true
    && report.notThresholdRecommendation === true
    && report.notProductionDecisioning === true
    && report.notPaymentAuthorization === true
    && report.notAutomaticDecisioning === true
    && report.notAnalystRecommendation === true
    && typeof report.banner === "string"
    && report.banner.trim().length > 0
    && Array.isArray(report.checks);
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
