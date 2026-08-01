export const ENGINE_INTELLIGENCE_CONTRACT_VERSION = 1;
export const MAX_ENGINE_INTELLIGENCE_ENGINES = 3;
export const MAX_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNALS = 5;
export const MAX_ENGINE_INTELLIGENCE_WARNINGS = 10;
export const MAX_ENGINE_INTELLIGENCE_REASON_CODES = 5;

export const COMPARISON_TYPE = "RULES_VS_ML";
export const COMPARED_ENGINE_IDS = Object.freeze(["rules.primary", "ml.python.primary"]);

export const AGREEMENT_STATUSES = new Set([
  "AGREEMENT",
  "ADJACENT_RISK_VARIANCE",
  "DISAGREEMENT",
  "PARTIAL",
  "INSUFFICIENT_DATA",
  "REQUIRED_ENGINE_NOT_COMPARABLE"
]);
export const RISK_MISMATCH_STATUSES = new Set([
  "SAME_RISK_LEVEL",
  "ADJACENT_RISK_LEVEL",
  "MATERIAL_RISK_MISMATCH",
  "NOT_COMPARABLE"
]);
export const SCORE_DELTA_BUCKETS = new Set(["NONE", "SMALL", "MEDIUM", "LARGE", "UNAVAILABLE"]);
export const ENGINE_TYPES = new Set(["RULES", "ML_MODEL", "VELOCITY"]);
export const ENGINE_STATUSES = new Set(["AVAILABLE", "UNAVAILABLE", "TIMEOUT", "DEGRADED", "NOT_APPLICABLE"]);
export const RISK_LEVELS = new Set(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
export const SCORE_BUCKETS = new Set(["NONE", "LOW", "MEDIUM", "HIGH", "VERY_HIGH", "UNAVAILABLE"]);
export const SIGNAL_CATEGORIES = new Set(["FRAUD_SIGNAL", "OPERATIONAL_SIGNAL"]);

export const ENGINE_TYPE_BY_ID = Object.freeze({
  "rules.primary": "RULES",
  "ml.python.primary": "ML_MODEL",
  "velocity.primary": "VELOCITY"
});
export const ENGINE_ORDER = Object.freeze(Object.keys(ENGINE_TYPE_BY_ID));

const UTC_INSTANT_PATTERN = /^([1-9][0-9]{3,})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):([0-5][0-9]):([0-5][0-9])(?:\.[0-9]{1,9})?Z$/;

export function isCanonicalUtcTimestamp(value) {
  if (typeof value !== "string") {
    return false;
  }
  const match = UTC_INSTANT_PATTERN.exec(value);
  if (!match) {
    return false;
  }
  const [, yearText, monthText, dayText, hourText, minuteText, secondText] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const instant = new Date(Date.UTC(year, month - 1, day, hour, minute, second));
  return instant.getUTCFullYear() === year
    && instant.getUTCMonth() === month - 1
    && instant.getUTCDate() === day
    && instant.getUTCHours() === hour
    && instant.getUTCMinutes() === minute
    && instant.getUTCSeconds() === second;
}

export function isComparisonShape(value) {
  return isPlainObject(value)
    && hasOnlyKeys(value, [
      "comparisonType",
      "comparedEngineIds",
      "agreementStatus",
      "riskMismatchStatus",
      "scoreDeltaBucket"
    ])
    && value.comparisonType === COMPARISON_TYPE
    && hasExactComparedEngineIds(value.comparedEngineIds)
    && oneOf(value.agreementStatus, AGREEMENT_STATUSES)
    && oneOf(value.riskMismatchStatus, RISK_MISMATCH_STATUSES)
    && oneOf(value.scoreDeltaBucket, SCORE_DELTA_BUCKETS);
}

export function normalizeComparison(value) {
  if (!isComparisonShape(value)) {
    return null;
  }
  return Object.freeze({
    comparisonType: value.comparisonType,
    comparedEngineIds: Object.freeze([...value.comparedEngineIds]),
    agreementStatus: value.agreementStatus,
    riskMismatchStatus: value.riskMismatchStatus,
    scoreDeltaBucket: value.scoreDeltaBucket
  });
}

export function isEngineShape(engine) {
  return isPlainObject(engine)
    && hasOnlyKeys(engine, ["engineId", "engineType", "status", "riskLevel", "scoreBucket", "reasonCodes"])
    && safeString(engine.engineId)
    && oneOf(engine.engineType, ENGINE_TYPES)
    && isExpectedEngineType(engine.engineId, engine.engineType)
    && oneOf(engine.status, ENGINE_STATUSES)
    && optionalOneOf(engine.riskLevel, RISK_LEVELS)
    && oneOf(engine.scoreBucket, SCORE_BUCKETS)
    && safeStringArray(engine.reasonCodes, MAX_ENGINE_INTELLIGENCE_REASON_CODES)
    && isEngineResultOperationallyConsistent(engine.status, engine.scoreBucket, engine.riskLevel);
}

export function isDiagnosticSignalShape(signal) {
  return isPlainObject(signal)
    && hasOnlyKeys(signal, ["engineId", "engineType", "engineStatus", "signalCategory", "riskLevel", "scoreBucket", "reasonCode"])
    && safeString(signal.engineId)
    && oneOf(signal.engineType, ENGINE_TYPES)
    && isExpectedEngineType(signal.engineId, signal.engineType)
    && oneOf(signal.engineStatus, ENGINE_STATUSES)
    && oneOf(signal.signalCategory, SIGNAL_CATEGORIES)
    && optionalOneOf(signal.riskLevel, RISK_LEVELS)
    && oneOf(signal.scoreBucket, SCORE_BUCKETS)
    && safeString(signal.reasonCode)
    && isDiagnosticSignalOperationallyConsistent(signal.signalCategory, signal.engineStatus, signal.scoreBucket, signal.riskLevel);
}

export function isWarningShape(warning, allowedWarningCodes = null) {
  return isPlainObject(warning)
    && hasOnlyKeys(warning, ["warningCode", "count"])
    && safeString(warning.warningCode)
    && (allowedWarningCodes === null || oneOf(warning.warningCode, allowedWarningCodes))
    && Number.isInteger(warning.count)
    && warning.count >= 0;
}

export function hasUniqueCanonicalEngineOrder(values) {
  const seen = new Set();
  let previousOrder = -1;
  for (const value of values) {
    const engineId = typeof value?.engineId === "string" ? value.engineId : "";
    const order = ENGINE_ORDER.indexOf(engineId);
    if (order < 0) {
      return false;
    }
    if (seen.has(engineId) || order <= previousOrder) {
      return false;
    }
    seen.add(engineId);
    previousOrder = order;
  }
  return true;
}

export function isBoundedArray(value, maxLength) {
  return Array.isArray(value) && value.length <= maxLength;
}

export function safeString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

export function safeStringArray(value, maxLength) {
  return isBoundedArray(value, maxLength) && value.every(safeString);
}

export function oneOf(value, allowedValues) {
  return typeof value === "string" && allowedValues.has(value);
}

export function optionalOneOf(value, allowedValues) {
  return value === null || value === undefined || oneOf(value, allowedValues);
}

export function hasOnlyKeys(value, allowedKeys) {
  const actualKeys = Object.keys(value);
  return actualKeys.length === allowedKeys.length
    && allowedKeys.every((key) => Object.prototype.hasOwnProperty.call(value, key));
}

export function isPlainObject(value) {
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
}

export function isExpectedEngineType(engineId, engineType) {
  return ENGINE_TYPE_BY_ID[engineId] === engineType;
}

function hasExactComparedEngineIds(value) {
  return Array.isArray(value)
    && value.length === COMPARED_ENGINE_IDS.length
    && COMPARED_ENGINE_IDS.every((engineId, index) => value[index] === engineId);
}

function isEngineResultOperationallyConsistent(status, scoreBucket, riskLevel) {
  if (status === "AVAILABLE") {
    return riskLevel !== null && riskLevel !== undefined && scoreBucket !== "UNAVAILABLE";
  }
  return scoreBucket === "UNAVAILABLE" && (riskLevel === null || riskLevel === undefined);
}

function isDiagnosticSignalOperationallyConsistent(signalCategory, engineStatus, scoreBucket, riskLevel) {
  if (engineStatus !== "AVAILABLE" || signalCategory === "OPERATIONAL_SIGNAL") {
    return scoreBucket === "UNAVAILABLE" && (riskLevel === null || riskLevel === undefined);
  }
  return riskLevel !== null && riskLevel !== undefined && scoreBucket !== "UNAVAILABLE";
}
