const DETAIL_STATUSES = new Set(["AVAILABLE", "ABSENT", "UNAVAILABLE", "DEGRADED"]);
const AGREEMENT_STATUSES = new Set([
  "AGREEMENT",
  "ADJACENT_RISK_VARIANCE",
  "DISAGREEMENT",
  "PARTIAL",
  "INSUFFICIENT_DATA",
  "REQUIRED_ENGINE_NOT_COMPARABLE"
]);
const RISK_MISMATCH_STATUSES = new Set([
  "SAME_RISK_LEVEL",
  "ADJACENT_RISK_LEVEL",
  "MATERIAL_RISK_MISMATCH",
  "NOT_COMPARABLE"
]);
const SCORE_DELTA_BUCKETS = new Set(["NONE", "SMALL", "MEDIUM", "LARGE", "UNAVAILABLE"]);
const ENGINE_TYPES = new Set([
  "RULES",
  "ML_MODEL",
  "VELOCITY",
  "DEVICE_RISK",
  "MERCHANT_RISK",
  "GEO_RISK",
  "GRAPH_RISK",
  "BEHAVIORAL_PROFILE",
  "MANUAL_REVIEW_CONTEXT"
]);
const ENGINE_STATUSES = new Set(["AVAILABLE", "UNAVAILABLE", "TIMEOUT", "DEGRADED", "NOT_APPLICABLE"]);
const RISK_LEVELS = new Set(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
const SCORE_BUCKETS = new Set(["NONE", "LOW", "MEDIUM", "HIGH", "VERY_HIGH", "UNAVAILABLE"]);
const SIGNAL_CATEGORIES = new Set(["FRAUD_SIGNAL", "OPERATIONAL_SIGNAL"]);
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
const MAX_ENGINES = 2;
const MAX_DIAGNOSTIC_SIGNALS = 5;
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
  if (engineIntelligence.comparison !== null && !isComparisonShape(engineIntelligence.comparison)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_COMPARISON");
  }
  if (!isBoundedArray(engineIntelligence.engines, MAX_ENGINES)) {
    return invalid("ENGINE_LIMIT_EXCEEDED");
  }
  if (!engineIntelligence.engines.every(isEngineShape)) {
    return invalid("INVALID_ENGINE_INTELLIGENCE_ENGINE");
  }
  if (!isBoundedArray(engineIntelligence.diagnosticSignals, MAX_DIAGNOSTIC_SIGNALS)) {
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
  return Boolean(value && typeof value === "object" && !Array.isArray(value))
    && hasOnlyKeys(value, ["agreementStatus", "riskMismatchStatus", "scoreDeltaBucket"])
    && oneOf(value.agreementStatus, AGREEMENT_STATUSES)
    && oneOf(value.riskMismatchStatus, RISK_MISMATCH_STATUSES)
    && oneOf(value.scoreDeltaBucket, SCORE_DELTA_BUCKETS);
}

function isEngineShape(engine) {
  return Boolean(engine && typeof engine === "object" && !Array.isArray(engine))
    && safeString(engine.engineId)
    && oneOf(engine.engineType, ENGINE_TYPES)
    && oneOf(engine.status, ENGINE_STATUSES)
    && optionalOneOf(engine.riskLevel, RISK_LEVELS)
    && oneOf(engine.scoreBucket, SCORE_BUCKETS)
    && safeStringArray(engine.reasonCodes, MAX_REASON_CODES);
}

function isDiagnosticSignalShape(signal) {
  return Boolean(signal && typeof signal === "object" && !Array.isArray(signal))
    && safeString(signal.engineId)
    && oneOf(signal.engineType, ENGINE_TYPES)
    && oneOf(signal.engineStatus, ENGINE_STATUSES)
    && oneOf(signal.signalCategory, SIGNAL_CATEGORIES)
    && safeString(signal.reasonCode)
    && optionalOneOf(signal.riskLevel, RISK_LEVELS)
    && oneOf(signal.scoreBucket, SCORE_BUCKETS);
}

function isWarningShape(warning) {
  return Boolean(warning && typeof warning === "object" && !Array.isArray(warning))
    && safeString(warning.warningCode)
    && Number.isInteger(warning.count)
    && warning.count >= 0;
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
  return safeString(value) && !Number.isNaN(Date.parse(value));
}

function oneOf(value, allowedValues) {
  return typeof value === "string" && allowedValues.has(value);
}

function optionalOneOf(value, allowedValues) {
  return value === null || value === undefined || oneOf(value, allowedValues);
}

function hasOnlyKeys(value, allowedKeys) {
  const actualKeys = Object.keys(value);
  return actualKeys.length === allowedKeys.length
    && allowedKeys.every((key) => Object.prototype.hasOwnProperty.call(value, key));
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
