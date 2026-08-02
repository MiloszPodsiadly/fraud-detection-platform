export const ENGINE_INTELLIGENCE_CONTRACT_VERSION = 1;
export const MAX_ENGINE_INTELLIGENCE_ENGINES = 3;
export const MAX_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNALS = 5;
export const MAX_ENGINE_INTELLIGENCE_WARNINGS = 10;
export const MAX_ENGINE_INTELLIGENCE_REASON_CODES = 5;
export const MAX_PUBLIC_STRING_LENGTH = 128;
export const MAX_RECOMMENDATION_VERSION_LENGTH = 64;

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
export const RESPONSE_STATUSES = new Set(["AVAILABLE", "ABSENT", "UNAVAILABLE", "DEGRADED"]);

export const ENGINE_TYPE_BY_ID = Object.freeze({
  "rules.primary": "RULES",
  "ml.python.primary": "ML_MODEL",
  "velocity.primary": "VELOCITY"
});
export const ENGINE_ORDER = Object.freeze(Object.keys(ENGINE_TYPE_BY_ID));

export const CANONICAL_UTC_TIMESTAMP_PATTERN = "^(?:(?:(?!0000)(?:(?:[02468][048]|[13579][26])00|[0-9]{2}(?:0[48]|[2468][048]|[13579][26])))-02-29|(?:[0-9]{3}[1-9]|[0-9]{2}[1-9][0-9]|[0-9][1-9][0-9]{2}|[1-9][0-9]{3})-(?:(?:01|03|05|07|08|10|12)-(?:0[1-9]|[12][0-9]|3[01])|(?:04|06|09|11)-(?:0[1-9]|[12][0-9]|30)|02-(?:0[1-9]|1[0-9]|2[0-8])))T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z$";

const UTC_INSTANT_PATTERN = new RegExp(CANONICAL_UTC_TIMESTAMP_PATTERN);

export function isCanonicalUtcTimestamp(value) {
  if (typeof value !== "string") {
    return false;
  }
  const match = UTC_INSTANT_PATTERN.exec(value);
  if (!match) {
    return false;
  }
  const [datePart, timePart] = value.slice(0, -1).split("T");
  const [yearText, monthText, dayText] = datePart.split("-");
  const [hourText, minuteText, secondWithFraction] = timePart.split(":");
  const [secondText] = secondWithFraction.split(".");
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

export function isEngineIntelligenceResponseShape(value) {
  if (!isPlainObject(value)
    || !hasOnlyKeys(value, ["status", "contractVersion", "generatedAt", "comparison", "engines", "diagnosticSignals", "warnings"])
    || !oneOf(value.status, RESPONSE_STATUSES)) {
    return false;
  }
  if (value.status === "ABSENT" || value.status === "UNAVAILABLE") {
    return value.contractVersion === null
      && value.generatedAt === null
      && value.comparison === null
      && Array.isArray(value.engines)
      && value.engines.length === 0
      && Array.isArray(value.diagnosticSignals)
      && value.diagnosticSignals.length === 0
      && Array.isArray(value.warnings)
      && value.warnings.length === 0;
  }
  return value.contractVersion === ENGINE_INTELLIGENCE_CONTRACT_VERSION
    && isCanonicalUtcTimestamp(value.generatedAt)
    && isComparisonShape(value.comparison)
    && isBoundedArray(value.engines, MAX_ENGINE_INTELLIGENCE_ENGINES)
    && value.engines.length >= 2
    && value.engines.every(isEngineShape)
    && hasRequiredRulesMlEngineSet(value.engines)
    && isComparisonCoherent(value.comparison, value.engines)
    && isBoundedArray(value.diagnosticSignals, MAX_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNALS)
    && value.diagnosticSignals.every(isDiagnosticSignalShape)
    && areDiagnosticSignalsCoherent(value.diagnosticSignals, value.engines)
    && isBoundedArray(value.warnings, MAX_ENGINE_INTELLIGENCE_WARNINGS)
    && value.warnings.every((warning) => isWarningShape(warning))
    && value.status === deriveEngineIntelligenceResponseStatus(value.engines, value.warnings);
}

export function isEngineShape(engine) {
  return isPlainObject(engine)
    && hasOnlyKeys(engine, ["engineId", "engineType", "status", "riskLevel", "scoreBucket", "reasonCodes"])
    && safeString(engine.engineId, MAX_PUBLIC_STRING_LENGTH)
    && oneOf(engine.engineType, ENGINE_TYPES)
    && isExpectedEngineType(engine.engineId, engine.engineType)
    && oneOf(engine.status, ENGINE_STATUSES)
    && optionalOneOf(engine.riskLevel, RISK_LEVELS)
    && oneOf(engine.scoreBucket, SCORE_BUCKETS)
    && safeStringArray(engine.reasonCodes, MAX_ENGINE_INTELLIGENCE_REASON_CODES, MAX_PUBLIC_STRING_LENGTH)
    && isEngineResultOperationallyConsistent(engine.status, engine.scoreBucket, engine.riskLevel);
}

export function isDiagnosticSignalShape(signal) {
  return isPlainObject(signal)
    && hasOnlyKeys(signal, ["engineId", "engineType", "engineStatus", "signalCategory", "riskLevel", "scoreBucket", "reasonCode"])
    && safeString(signal.engineId, MAX_PUBLIC_STRING_LENGTH)
    && oneOf(signal.engineType, ENGINE_TYPES)
    && isExpectedEngineType(signal.engineId, signal.engineType)
    && oneOf(signal.engineStatus, ENGINE_STATUSES)
    && oneOf(signal.signalCategory, SIGNAL_CATEGORIES)
    && optionalOneOf(signal.riskLevel, RISK_LEVELS)
    && oneOf(signal.scoreBucket, SCORE_BUCKETS)
    && safeString(signal.reasonCode, MAX_PUBLIC_STRING_LENGTH)
    && isDiagnosticSignalOperationallyConsistent(signal.signalCategory, signal.engineStatus, signal.scoreBucket, signal.riskLevel);
}

export function isWarningShape(warning, allowedWarningCodes = null) {
  return isPlainObject(warning)
    && hasOnlyKeys(warning, ["warningCode", "count"])
    && safeString(warning.warningCode, MAX_PUBLIC_STRING_LENGTH)
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

export function hasRequiredRulesMlEngineSet(values) {
  return hasUniqueCanonicalEngineOrder(values)
    && values.length >= 2
    && values[0]?.engineId === "rules.primary"
    && values[1]?.engineId === "ml.python.primary"
    && (values.length === 2 || values[2]?.engineId === "velocity.primary");
}

export function isComparisonCoherent(comparison, engines) {
  if (!isComparisonShape(comparison)) {
    return false;
  }
  const byEngineId = new Map(engines.map((engine) => [engine.engineId, engine]));
  const rules = byEngineId.get("rules.primary");
  const ml = byEngineId.get("ml.python.primary");
  if (!rules || !ml) {
    return false;
  }
  if (rules.status !== "AVAILABLE" || ml.status !== "AVAILABLE") {
    return isOperationalComparisonCoherent(comparison, rules, ml);
  }
  const expectedRiskMismatchStatus = riskMismatchStatus(rules.riskLevel, ml.riskLevel);
  return comparison.riskMismatchStatus === expectedRiskMismatchStatus
    && comparison.agreementStatus === agreementStatus(expectedRiskMismatchStatus)
    && comparison.scoreDeltaBucket !== "UNAVAILABLE"
    && deltaBucketCanDescribe(rules.scoreBucket, ml.scoreBucket, comparison.scoreDeltaBucket);
}

export function areDiagnosticSignalsCoherent(signals, engines) {
  const byEngineId = new Map(engines.map((engine) => [engine.engineId, engine]));
  return signals.every((signal) => {
    const engine = byEngineId.get(signal.engineId);
    if (!engine || signal.engineType !== engine.engineType || signal.engineStatus !== engine.status) {
      return false;
    }
    if (engine.status === "AVAILABLE") {
      return signal.signalCategory === "FRAUD_SIGNAL"
        && signal.riskLevel === engine.riskLevel
        && signal.scoreBucket === engine.scoreBucket
        && engine.reasonCodes.includes(signal.reasonCode);
    }
    return signal.signalCategory === "OPERATIONAL_SIGNAL"
      && (signal.riskLevel === null || signal.riskLevel === undefined)
      && signal.scoreBucket === "UNAVAILABLE"
      && engine.reasonCodes.includes(signal.reasonCode);
  });
}

export function deriveEngineIntelligenceResponseStatus(engines, warnings) {
  if (!Array.isArray(engines) || !Array.isArray(warnings)) {
    return "DEGRADED";
  }
  if (warnings.length > 0) {
    return "DEGRADED";
  }
  const byEngineId = new Map(engines.map((engine) => [engine.engineId, engine]));
  const rules = byEngineId.get("rules.primary");
  const ml = byEngineId.get("ml.python.primary");
  if (rules?.status !== "AVAILABLE" || ml?.status !== "AVAILABLE") {
    return "DEGRADED";
  }
  const velocity = byEngineId.get("velocity.primary");
  if (!velocity || velocity.status === "AVAILABLE" || velocity.status === "NOT_APPLICABLE") {
    return "AVAILABLE";
  }
  return "DEGRADED";
}

export function isBoundedArray(value, maxLength) {
  return Array.isArray(value) && value.length <= maxLength;
}

export function safeString(value, maxLength = MAX_PUBLIC_STRING_LENGTH) {
  return typeof value === "string"
    && value.length > 0
    && value.length <= maxLength
    && value.trim().length === value.length
    && !hasControlCharacter(value);
}

export function safeStringArray(value, maxLength, maxStringLength = MAX_PUBLIC_STRING_LENGTH) {
  return isBoundedArray(value, maxLength)
    && value.every((item) => safeString(item, maxStringLength));
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

export function hasOnlyKnownKeys(value, allowedKeys) {
  return isPlainObject(value)
    && Object.keys(value).every((key) => allowedKeys.includes(key));
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
    return riskLevel !== null && riskLevel !== undefined && isUsableAvailableScoreBucket(scoreBucket);
  }
  return scoreBucket === "UNAVAILABLE" && (riskLevel === null || riskLevel === undefined);
}

function isDiagnosticSignalOperationallyConsistent(signalCategory, engineStatus, scoreBucket, riskLevel) {
  if (engineStatus !== "AVAILABLE") {
    return signalCategory === "OPERATIONAL_SIGNAL"
      && scoreBucket === "UNAVAILABLE"
      && (riskLevel === null || riskLevel === undefined);
  }
  if (signalCategory === "OPERATIONAL_SIGNAL") {
    return scoreBucket === "UNAVAILABLE" && (riskLevel === null || riskLevel === undefined);
  }
  return riskLevel !== null && riskLevel !== undefined && isUsableAvailableScoreBucket(scoreBucket);
}

function isUsableAvailableScoreBucket(scoreBucket) {
  return scoreBucket === "LOW" || scoreBucket === "MEDIUM" || scoreBucket === "HIGH" || scoreBucket === "VERY_HIGH";
}

function isOperationalComparisonCoherent(comparison, rules, ml) {
  const expectedAgreementStatus = rules.status === "AVAILABLE"
    ? "PARTIAL"
    : "REQUIRED_ENGINE_NOT_COMPARABLE";
  return comparison.riskMismatchStatus === "NOT_COMPARABLE"
    && comparison.scoreDeltaBucket === "UNAVAILABLE"
    && comparison.agreementStatus === expectedAgreementStatus
    && (ml.status !== "AVAILABLE" || comparison.agreementStatus === "REQUIRED_ENGINE_NOT_COMPARABLE");
}

function riskMismatchStatus(rulesRiskLevel, mlRiskLevel) {
  const distance = Math.abs(riskSeverity(rulesRiskLevel) - riskSeverity(mlRiskLevel));
  if (distance === 0) {
    return "SAME_RISK_LEVEL";
  }
  if (distance === 1) {
    return "ADJACENT_RISK_LEVEL";
  }
  return "MATERIAL_RISK_MISMATCH";
}

function agreementStatus(riskMismatch) {
  if (riskMismatch === "SAME_RISK_LEVEL") {
    return "AGREEMENT";
  }
  if (riskMismatch === "ADJACENT_RISK_LEVEL") {
    return "ADJACENT_RISK_VARIANCE";
  }
  return "DISAGREEMENT";
}

function riskSeverity(riskLevel) {
  return ["LOW", "MEDIUM", "HIGH", "CRITICAL"].indexOf(riskLevel);
}

function deltaBucketCanDescribe(rulesScoreBucket, mlScoreBucket, scoreDeltaBucket) {
  if (scoreDeltaBucket === "UNAVAILABLE") {
    return false;
  }
  const distance = Math.abs(scoreBucketSeverity(rulesScoreBucket) - scoreBucketSeverity(mlScoreBucket));
  if (distance === 0) {
    return scoreDeltaBucket !== "LARGE";
  }
  if (distance === 1) {
    return scoreDeltaBucket !== "NONE";
  }
  if (distance === 2) {
    return scoreDeltaBucket === "MEDIUM" || scoreDeltaBucket === "LARGE";
  }
  if (distance === 3) {
    return scoreDeltaBucket === "LARGE";
  }
  return false;
}

function scoreBucketSeverity(scoreBucket) {
  return ["LOW", "MEDIUM", "HIGH", "VERY_HIGH"].indexOf(scoreBucket);
}

function hasControlCharacter(value) {
  return /[\u0000-\u001F\u007F]/.test(value);
}
