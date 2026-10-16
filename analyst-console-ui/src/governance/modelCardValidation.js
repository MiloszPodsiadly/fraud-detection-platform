import { isCanonicalUtcTimestamp } from "./canonicalUtcTimestamp.js";
import {
  isValidModelEvaluationSubject,
  isValidModelEvaluationSummary
} from "./modelEvaluationGovernanceValidation.js";

const ROOT_FIELDS = [
  "schemaVersion", "cardType", "generatedAt", "subject", "promotionState",
  "modelArtifactVerification", "evaluationEvidence", "evaluationSummary", "verifiedUsageBoundary"
];
const AVAILABLE_EVIDENCE_FIELDS = [
  "status", "reportType", "artifactSetVersion", "reportGeneratedAt", "manifestSha256",
  "integrityVerification", "sourceDatasetVerificationLevel"
];
const SHA256 = /^[a-f0-9]{64}$/;
const ALLOWED_MODES = ["COMPARE", "OFFLINE_EVALUATION", "SHADOW"];
const INTENDED_USE = [
  "EXACT_MODEL_OFFLINE_EVALUATION", "RULES_VS_ML_DIAGNOSTIC_COMPARISON", "SHADOW_MODEL_MONITORING"
];
const NOT_INTENDED_USE = [
  "AUTOMATIC_APPROVE_DECLINE_OR_BLOCK", "MODEL_PROMOTION_APPROVAL", "PAYMENT_AUTHORIZATION",
  "PRIMARY_PRODUCTION_FRAUD_DECISIONING", "SCORING_THRESHOLD_CHANGE", "WORKFLOW_OR_ANALYST_RECOMMENDATION"
];
const USE_LIMITATIONS = [
  "ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_GROUND_TRUTH", "BOUNDED_RETURNED_POPULATION_ONLY",
  "DOES_NOT_ESTABLISH_GLOBAL_DATASET_COMPLETENESS", "DOES_NOT_ESTABLISH_MODEL_CALIBRATION",
  "DOES_NOT_ESTABLISH_PRODUCTION_READINESS", "SOURCE_VERIFICATION_LEVEL_MUST_BE_INTERPRETED_EXPLICITLY"
];

export function isValidModelCard(card) {
  if (!isPlainObject(card)
      || !hasExactKeys(card, ROOT_FIELDS)
      || card.schemaVersion !== "ml-model-card-v1"
      || card.cardType !== "ML_MODEL_CARD_V1"
      || !isCanonicalUtcTimestamp(card.generatedAt)
      || card.promotionState !== "NOT_EVALUATED_FOR_PROMOTION"
      || !isValidModelEvaluationSubject(card.subject)
      || !isValidArtifactVerification(card.modelArtifactVerification, card.subject)
      || !isValidUsageBoundary(card.verifiedUsageBoundary)) {
    return false;
  }
  return isValidEvaluation(card.evaluationEvidence, card.evaluationSummary, card.subject, card.generatedAt);
}

function isValidEvaluation(evidence, summary, subject, cardGeneratedAt) {
  if (!isPlainObject(evidence)) return false;
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

function isValidArtifactVerification(verification, subject) {
  return isPlainObject(verification)
    && hasExactKeys(verification, ["status", "sha256"])
    && verification.status === "VERIFIED_AGAINST_ARTIFACT_BYTES"
    && verification.sha256 === subject.modelArtifactSha256;
}

function isValidUsageBoundary(boundary) {
  return isPlainObject(boundary)
    && hasExactKeys(boundary, [
      "allowedModes", "intendedUse", "notIntendedUse", "useLimitations", "runtimeDecisionAuthority",
      "promotionAuthority", "thresholdChangeAuthority", "paymentAuthorizationAuthority", "workflowAuthority"
    ])
    && sameOrderedValues(boundary.allowedModes, ALLOWED_MODES)
    && sameOrderedValues(boundary.intendedUse, INTENDED_USE)
    && sameOrderedValues(boundary.notIntendedUse, NOT_INTENDED_USE)
    && sameOrderedValues(boundary.useLimitations, USE_LIMITATIONS)
    && [
      boundary.runtimeDecisionAuthority, boundary.promotionAuthority, boundary.thresholdChangeAuthority,
      boundary.paymentAuthorizationAuthority, boundary.workflowAuthority
    ].every((authority) => authority === "NONE");
}

function sameOrderedValues(actual, expected) {
  return Array.isArray(actual)
    && actual.length === expected.length
    && actual.every((value, index) => value === expected[index]);
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function hasExactKeys(value, expectedKeys) {
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}
