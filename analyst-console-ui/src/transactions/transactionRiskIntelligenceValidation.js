import * as engineIntelligenceContract from "../engineIntelligence/engineIntelligenceContractValidation.js";

const DETAIL_STATUSES = new Set(["AVAILABLE", "ABSENT", "UNAVAILABLE", "DEGRADED"]);
const ANALYST_RECOMMENDATION_STATUSES = new Set([
  "AVAILABLE",
  "ABSENT",
  "NOT_APPLICABLE",
  "INSUFFICIENT_DATA",
  "UNAVAILABLE",
  "DEGRADED"
]);
const ANALYST_RECOMMENDATION_REQUIRED_STATUSES = new Set(["AVAILABLE", "DEGRADED"]);
const ANALYST_RECOMMENDATIONS = new Set([
  "RECOMMEND_REVIEW",
  "RECOMMEND_CASE_CREATION",
  "RECOMMEND_STEP_UP_REVIEW",
  "RECOMMEND_MONITOR",
  "RECOMMEND_NO_ACTION"
]);
const ANALYST_RECOMMENDATION_CONFIDENCES = new Set(["UNKNOWN", "LOW", "MEDIUM"]);
const ANALYST_RECOMMENDATION_SOURCES = new Set([
  "RULES_RISK",
  "ENGINE_COMPARISON",
  "RISK_MISMATCH",
  "ENGINE_INTELLIGENCE_ABSENT",
  "ENGINE_INTELLIGENCE_DEGRADED",
  "ENGINE_INTELLIGENCE_UNAVAILABLE",
  "NOT_APPLICABLE"
]);
const NON_DECISIONING_FLAGS = [
  "notPaymentAuthorization",
  "notAutomaticDecisioning",
  "notCaseAction",
  "notWorkflowAction",
  "notModelPromotion",
  "notThresholdRecommendation"
];
const MAX_WARNINGS = 10;
const MAX_REASON_CODES = 5;

const UNSAFE_FIELD_NAMES = [
  ["raw", "ml", "request"].join(""),
  ["raw", "ml", "response"].join(""),
  ["raw", "feature", "vector"].join(""),
  ["fraud", "engine", "result"].join(""),
  ["raw", "evidence"].join(""),
  ["raw", "payload"].join(""),
  ["ground", "truth"].join(""),
  ["training", "label"].join(""),
  ["final", "decision"].join(""),
  ["payment", "authorization"].join(""),
  ["stack", "trace"].join(""),
  ["exception", "message"].join(""),
  "token",
  "secret"
];
export function validateTransactionRiskIntelligenceDetail(detail) {
  if (!detail || typeof detail !== "object" || Array.isArray(detail)) {
    return invalid("INVALID_DETAIL_RESPONSE");
  }
  if (containsUnsafeFieldName(detail)) {
    return invalid("UNSAFE_DETAIL_RESPONSE");
  }
  if (!safeString(detail.transactionId)) {
    return invalid("MISSING_TRANSACTION_ID");
  }
  if (!detail.engineIntelligence || typeof detail.engineIntelligence !== "object" || Array.isArray(detail.engineIntelligence)) {
    return invalid("MISSING_ENGINE_INTELLIGENCE");
  }
  const engineIntelligence = detail.engineIntelligence;
  if (!DETAIL_STATUSES.has(engineIntelligence.status)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_STATUS");
  }
  if (!isNumberOrNull(engineIntelligence.contractVersion) || !isStringOrNull(engineIntelligence.generatedAt)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_METADATA");
  }
  if (engineIntelligence.status === "AVAILABLE" || engineIntelligence.status === "DEGRADED") {
    if (
      engineIntelligence.contractVersion !== engineIntelligenceContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION
      || !parseableDateString(engineIntelligence.generatedAt)
    ) {
      return invalid("INVALID_ENGINE_INTELLIGENCE_METADATA");
    }
  }
  if (engineIntelligence.comparison !== null && !isComparisonShape(engineIntelligence.comparison)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_COMPARISON");
  }
  if (!isBoundedArray(engineIntelligence.engines, engineIntelligenceContract.MAX_ENGINE_INTELLIGENCE_ENGINES)) {
    return invalid("ENGINE_LIMIT_EXCEEDED");
  }
  if (!engineIntelligence.engines.every(isEngineShape)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_ENGINE");
  }
  if (!hasUniqueCanonicalEngineOrder(engineIntelligence.engines)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_ENGINE_ORDER");
  }
  if (!isBoundedArray(engineIntelligence.diagnosticSignals, engineIntelligenceContract.MAX_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNALS)) {
    return invalid("DIAGNOSTIC_SIGNAL_LIMIT_EXCEEDED");
  }
  if (!engineIntelligence.diagnosticSignals.every(isDiagnosticSignalShape)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNAL");
  }
  if (!isBoundedArray(engineIntelligence.warnings, MAX_WARNINGS)) {
    return invalid("WARNING_LIMIT_EXCEEDED");
  }
  if (!engineIntelligence.warnings.every(isWarningShape)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_WARNING");
  }
  const analystRecommendationValidation = validateAnalystRecommendation(detail.analystRecommendation);
  if (!analystRecommendationValidation.valid) {
    return analystRecommendationValidation;
  }
  return Object.freeze({ valid: true, detail });
}

export function isValidTransactionRiskIntelligenceDetail(detail) {
  return validateTransactionRiskIntelligenceDetail(detail).valid;
}

function invalid(reason) {
  return Object.freeze({ valid: false, reason });
}

function isComparisonShape(value) {
  return engineIntelligenceContract.isComparisonShape(value);
}

function isEngineShape(engine) {
  return engineIntelligenceContract.isEngineShape(engine);
}

function isDiagnosticSignalShape(signal) {
  return engineIntelligenceContract.isDiagnosticSignalShape(signal);
}

function isWarningShape(warning) {
  return engineIntelligenceContract.isWarningShape(warning);
}

function validateAnalystRecommendation(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return invalid("MISSING_ANALYST_RECOMMENDATION");
  }
  if (!oneOf(value.status, ANALYST_RECOMMENDATION_STATUSES)) {
    return invalid("INVALID_ANALYST_RECOMMENDATION_STATUS");
  }
  if (!safeString(value.recommendationVersion)) {
    return invalid("INVALID_ANALYST_RECOMMENDATION_VERSION");
  }
  if (!oneOf(value.confidence, ANALYST_RECOMMENDATION_CONFIDENCES)) {
    return invalid("INVALID_ANALYST_RECOMMENDATION_CONFIDENCE");
  }
  if (!oneOf(value.source, ANALYST_RECOMMENDATION_SOURCES)) {
    return invalid("INVALID_ANALYST_RECOMMENDATION_SOURCE");
  }
  if (!safeStringArray(value.reasonCodes, MAX_REASON_CODES)) {
    return invalid("INVALID_ANALYST_RECOMMENDATION_REASON_CODES");
  }
  if (!isBoundedArray(value.warnings, MAX_WARNINGS)) {
    return invalid("ANALYST_RECOMMENDATION_WARNING_LIMIT_EXCEEDED");
  }
  if (!value.warnings.every(isWarningShape)) {
    return invalid("INVALID_ANALYST_RECOMMENDATION_WARNING");
  }
  if (!isNonDecisioningShape(value.nonDecisioning)) {
    return invalid("INVALID_ANALYST_RECOMMENDATION_NON_DECISIONING");
  }
  if (ANALYST_RECOMMENDATION_REQUIRED_STATUSES.has(value.status)) {
    if (!parseableDateString(value.generatedAt)) {
      return invalid("INVALID_ANALYST_RECOMMENDATION_GENERATED_AT");
    }
    if (!oneOf(value.recommendation, ANALYST_RECOMMENDATIONS)) {
      return invalid("INVALID_ANALYST_RECOMMENDATION_VALUE");
    }
    if (value.reasonCodes.length === 0) {
      return invalid("ANALYST_RECOMMENDATION_REASON_REQUIRED");
    }
  } else {
    if (value.generatedAt === undefined || (value.generatedAt !== null && !parseableDateString(value.generatedAt))) {
      return invalid("INVALID_ANALYST_RECOMMENDATION_GENERATED_AT");
    }
    if (value.recommendation !== null && value.recommendation !== undefined) {
      return invalid("INCONSISTENT_ANALYST_RECOMMENDATION_VALUE");
    }
  }
  return Object.freeze({ valid: true });
}

function isNonDecisioningShape(value) {
  return Boolean(value && typeof value === "object" && !Array.isArray(value))
    && NON_DECISIONING_FLAGS.every((flag) => value[flag] === true);
}

function isBoundedArray(value, maxLength) {
  return Array.isArray(value) && value.length <= maxLength;
}

function safeStringArray(value, maxLength) {
  return isBoundedArray(value, maxLength) && value.every(safeString);
}

function isNumberOrNull(value) {
  return value === null || Number.isFinite(value);
}

function isStringOrNull(value) {
  return value === null || typeof value === "string";
}

function safeString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function parseableDateString(value) {
  return engineIntelligenceContract.isCanonicalUtcTimestamp(value);
}

function oneOf(value, allowedValues) {
  return typeof value === "string" && allowedValues.has(value);
}

function hasUniqueCanonicalEngineOrder(values) {
  return engineIntelligenceContract.hasUniqueCanonicalEngineOrder(values);
}

function containsUnsafeFieldName(value) {
  if (!value || typeof value !== "object") {
    return false;
  }
  if (Array.isArray(value)) {
    return value.some(containsUnsafeFieldName);
  }
  return Object.entries(value).some(([key, nestedValue]) => (
    UNSAFE_FIELD_NAMES.includes(key.toLowerCase()) || containsUnsafeFieldName(nestedValue)
  ));
}
