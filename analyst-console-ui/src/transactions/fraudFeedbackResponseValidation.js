import { isCanonicalUtcTimestamp } from "../engineIntelligence/engineIntelligenceContractValidation.js";
import {
  ANALYST_DECISIONS,
  FRAUD_FEEDBACK_LABELS,
  isValidFraudFeedbackDecisionLabelPair,
  isValidFraudFeedbackReasonCodes
} from "./fraudFeedbackValidation.js";

const RESPONSE_FIELDS = new Set([
  "feedbackId",
  "transactionId",
  "customerId",
  "correlationId",
  "analystDecision",
  "feedbackLabel",
  "labelSource",
  "feedbackStatus",
  "createdAt",
  "createdBy",
  "decisionReasonCodes",
  "notesPresent",
  "fraudScore",
  "riskLevel",
  "alertRecommended",
  "scoredAt",
  "transactionTimestamp",
  "engineIntelligenceStatus",
  "comparisonType",
  "comparedEngineIds",
  "agreementStatus",
  "riskMismatchStatus",
  "scoreDeltaBucket",
  "analystRecommendationStatus",
  "analystRecommendation",
  "analystRecommendationVersion",
  "analystRecommendationGeneratedAt",
  "analystRecommendationReasonCodes",
  "scoringOccurrenceRelation"
]);
const REQUIRED_RESPONSE_FIELDS = [
  "feedbackId",
  "transactionId",
  "analystDecision",
  "feedbackLabel",
  "labelSource",
  "feedbackStatus",
  "createdAt",
  "notesPresent",
  "scoringOccurrenceRelation"
];
const TRANSACTION_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const FEEDBACK_ID_PATTERN = /^[A-Za-z0-9._:-]{1,160}$/;
const LABELS = new Set(FRAUD_FEEDBACK_LABELS);
const DECISIONS = new Set(ANALYST_DECISIONS);
const OCCURRENCE_RELATIONS = new Set(["CURRENT", "PRIOR", "UNKNOWN"]);
const RISK_LEVELS = new Set(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
const ENGINE_STATUSES = new Set(["AVAILABLE", "ABSENT", "UNAVAILABLE", "DEGRADED"]);
const AGREEMENT_STATUSES = new Set([
  "AGREEMENT", "ADJACENT_RISK_VARIANCE", "DISAGREEMENT", "PARTIAL", "INSUFFICIENT_DATA",
  "REQUIRED_ENGINE_NOT_COMPARABLE"
]);
const RISK_MISMATCH_STATUSES = new Set([
  "SAME_RISK_LEVEL", "ADJACENT_RISK_LEVEL", "MATERIAL_RISK_MISMATCH", "NOT_COMPARABLE"
]);
const SCORE_DELTA_BUCKETS = new Set(["NONE", "SMALL", "MEDIUM", "LARGE", "UNAVAILABLE"]);
const RECOMMENDATION_STATUSES = new Set([
  "AVAILABLE", "ABSENT", "NOT_APPLICABLE", "INSUFFICIENT_DATA", "UNAVAILABLE", "DEGRADED"
]);
const RECOMMENDATIONS = new Set([
  "RECOMMEND_REVIEW", "RECOMMEND_CASE_CREATION", "RECOMMEND_STEP_UP_REVIEW",
  "RECOMMEND_MONITOR", "RECOMMEND_NO_ACTION"
]);
const ENGINE_IDS = ["rules.primary", "ml.python.primary"];

export function isValidFraudFeedbackResponse(response, expectedTransactionId) {
  if (!isPlainObject(response)
      || !isValidTransactionId(expectedTransactionId)
      || Object.keys(response).some((key) => !RESPONSE_FIELDS.has(key))
      || REQUIRED_RESPONSE_FIELDS.some((key) => !Object.hasOwn(response, key))) {
    return false;
  }

  return isValidFeedbackId(response.feedbackId)
    && response.transactionId === expectedTransactionId
    && isValidTransactionId(response.transactionId)
    && DECISIONS.has(response.analystDecision)
    && LABELS.has(response.feedbackLabel)
    && isValidFraudFeedbackDecisionLabelPair(response.analystDecision, response.feedbackLabel)
    && response.labelSource === "ANALYST_REVIEW"
    && response.feedbackStatus === "RECORDED"
    && isCanonicalUtcTimestamp(response.createdAt)
    && typeof response.notesPresent === "boolean"
    && OCCURRENCE_RELATIONS.has(response.scoringOccurrenceRelation)
    && Object.hasOwn(response, "decisionReasonCodes")
    && isValidFraudFeedbackReasonCodes(response.feedbackLabel, response.decisionReasonCodes)
    && isOptionalNullable(response.createdBy, isValidActor)
    && isOptionalNullable(response.customerId, isBoundedText(512))
    && isOptionalNullable(response.correlationId, isBoundedText(512))
    && isOptionalNullable(response.fraudScore, Number.isFinite)
    && isOptionalNullable(response.riskLevel, (value) => RISK_LEVELS.has(value))
    && isOptionalNullable(response.alertRecommended, (value) => typeof value === "boolean")
    && isOptionalNullable(response.scoredAt, isCanonicalUtcTimestamp)
    && isOptionalNullable(response.transactionTimestamp, isCanonicalUtcTimestamp)
    && isOptionalNullable(response.engineIntelligenceStatus, (value) => ENGINE_STATUSES.has(value))
    && isOptionalNullable(response.comparisonType, (value) => value === "RULES_VS_ML")
    && isOptionalNullable(response.comparedEngineIds, isComparedEngineIds)
    && isOptionalNullable(response.agreementStatus, (value) => AGREEMENT_STATUSES.has(value))
    && isOptionalNullable(response.riskMismatchStatus, (value) => RISK_MISMATCH_STATUSES.has(value))
    && isOptionalNullable(response.scoreDeltaBucket, (value) => SCORE_DELTA_BUCKETS.has(value))
    && isOptionalNullable(response.analystRecommendationStatus, (value) => RECOMMENDATION_STATUSES.has(value))
    && isOptionalNullable(response.analystRecommendation, (value) => RECOMMENDATIONS.has(value))
    && isOptionalNullable(response.analystRecommendationVersion, isBoundedText(256))
    && isOptionalNullable(response.analystRecommendationGeneratedAt, isCanonicalUtcTimestamp)
    && isOptionalNullable(response.analystRecommendationReasonCodes, isRecommendationReasonCodes);
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isValidTransactionId(value) {
  return typeof value === "string" && TRANSACTION_ID_PATTERN.test(value);
}

function isValidFeedbackId(value) {
  return typeof value === "string" && FEEDBACK_ID_PATTERN.test(value);
}

function isValidActor(value) {
  return isBoundedText(512)(value)
    && !/[\u0000-\u001f\u007f-\u009f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/.test(value);
}

function isBoundedText(maxLength) {
  return (value) => typeof value === "string"
    && value.trim() === value
    && value.length > 0
    && value.length <= maxLength;
}

function isOptionalNullable(value, validator) {
  return value === undefined || value === null || validator(value);
}

function isComparedEngineIds(value) {
  return Array.isArray(value)
    && (value.length === 0 || (value.length === ENGINE_IDS.length
      && value.every((engineId, index) => engineId === ENGINE_IDS[index])));
}

function isRecommendationReasonCodes(value) {
  return Array.isArray(value)
    && value.length <= 5
    && value.every((code) => isBoundedText(128)(code) && /^[A-Z0-9_]+$/.test(code));
}
