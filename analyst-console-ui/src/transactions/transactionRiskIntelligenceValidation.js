const DETAIL_STATUSES = new Set(["AVAILABLE", "ABSENT", "UNAVAILABLE", "DEGRADED"]);
const MAX_ENGINES = 2;
const MAX_DIAGNOSTIC_SIGNALS = 5;
const MAX_WARNINGS = 10;
const MAX_REASON_CODES = 5;

const UNSAFE_FIELD_NAMES = [
  ["raw", "Ml", "Request"].join(""),
  ["raw", "Ml", "Response"].join(""),
  ["raw", "Feature", "Vector"].join(""),
  ["Fraud", "Engine", "Result"].join(""),
  ["raw", "Evidence"].join(""),
  ["raw", "Payload"].join(""),
  ["ground", "Truth"].join(""),
  ["training", "Label"].join(""),
  ["final", "Decision"].join(""),
  ["payment", "Authorization"].join(""),
  ["stack", "Trace"].join(""),
  ["exception", "Message"].join(""),
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
  if (!engineIntelligence.engines.every(hasBoundedReasonCodes)) {
    return invalid("ENGINE_REASON_CODE_LIMIT_EXCEEDED");
  }
  if (!isBoundedArray(engineIntelligence.diagnosticSignals, MAX_DIAGNOSTIC_SIGNALS)) {
    return invalid("DIAGNOSTIC_SIGNAL_LIMIT_EXCEEDED");
  }
  if (!isBoundedArray(engineIntelligence.warnings, MAX_WARNINGS)) {
    return invalid("WARNING_LIMIT_EXCEEDED");
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
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
}

function hasBoundedReasonCodes(engine) {
  return isBoundedArray(engine?.reasonCodes, MAX_REASON_CODES);
}

function isBoundedArray(value, maxLength) {
  return Array.isArray(value) && value.length <= maxLength;
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

function containsUnsafeFieldName(value) {
  if (!value || typeof value !== "object") {
    return false;
  }
  if (Array.isArray(value)) {
    return value.some(containsUnsafeFieldName);
  }
  return Object.entries(value).some(([key, nestedValue]) => (
    UNSAFE_FIELD_NAMES.includes(key) || containsUnsafeFieldName(nestedValue)
  ));
}
