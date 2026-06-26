export const FRAUD_FEEDBACK_LABELS = Object.freeze([
  "CONFIRMED_FRAUD",
  "CONFIRMED_LEGITIMATE",
  "INCONCLUSIVE",
  "NEEDS_MORE_INFO"
]);

export const ANALYST_DECISIONS = Object.freeze([
  "MARKED_FRAUD",
  "MARKED_LEGITIMATE",
  "MARKED_INCONCLUSIVE",
  "REQUESTED_MORE_INFO"
]);

export const FRAUD_FEEDBACK_REASON_CODES = Object.freeze([
  "CUSTOMER_CONFIRMED_FRAUD",
  "CUSTOMER_CONFIRMED_LEGITIMATE",
  "DOCUMENTATION_CONFIRMED_FRAUD",
  "DOCUMENTATION_CONFIRMED_LEGITIMATE",
  "MERCHANT_CONFIRMED",
  "CHARGEBACK_SIGNAL",
  "ACCOUNT_TAKEOVER_INDICATOR",
  "FALSE_POSITIVE_PATTERN",
  "INSUFFICIENT_EVIDENCE",
  "NEEDS_CUSTOMER_CONTACT",
  "ANALYST_CONFIRMED_FRAUD",
  "ANALYST_CONFIRMED_LEGITIMATE",
  "ANALYST_INCONCLUSIVE",
  "ANALYST_NEEDS_MORE_INFO"
]);

const LABEL_SET = new Set(FRAUD_FEEDBACK_LABELS);
const DECISION_SET = new Set(ANALYST_DECISIONS);
const REASON_CODE_SET = new Set(FRAUD_FEEDBACK_REASON_CODES);
const DECISION_LABEL_MAP = Object.freeze({
  MARKED_FRAUD: "CONFIRMED_FRAUD",
  MARKED_LEGITIMATE: "CONFIRMED_LEGITIMATE",
  MARKED_INCONCLUSIVE: "INCONCLUSIVE",
  REQUESTED_MORE_INFO: "NEEDS_MORE_INFO"
});
const LABEL_REASON_CODE_MAP = Object.freeze({
  CONFIRMED_FRAUD: new Set([
    "CUSTOMER_CONFIRMED_FRAUD",
    "DOCUMENTATION_CONFIRMED_FRAUD",
    "CHARGEBACK_SIGNAL",
    "ACCOUNT_TAKEOVER_INDICATOR",
    "ANALYST_CONFIRMED_FRAUD"
  ]),
  CONFIRMED_LEGITIMATE: new Set([
    "CUSTOMER_CONFIRMED_LEGITIMATE",
    "DOCUMENTATION_CONFIRMED_LEGITIMATE",
    "MERCHANT_CONFIRMED",
    "FALSE_POSITIVE_PATTERN",
    "ANALYST_CONFIRMED_LEGITIMATE"
  ]),
  INCONCLUSIVE: new Set([
    "INSUFFICIENT_EVIDENCE",
    "ANALYST_INCONCLUSIVE"
  ]),
  NEEDS_MORE_INFO: new Set([
    "NEEDS_CUSTOMER_CONTACT",
    "INSUFFICIENT_EVIDENCE",
    "ANALYST_NEEDS_MORE_INFO"
  ])
});
const REASON_CODE_PATTERN = /^[A-Z0-9_]+$/;
const MAX_REASON_CODES = 10;
const MAX_REASON_CODE_LENGTH = 128;
const MAX_NOTES_LENGTH = 500;
const UNSAFE_TERMS = [
  "token",
  "secret",
  "password",
  ["raw", "payload"].join(""),
  ["raw", "ml", "request"].join(""),
  ["raw", "ml", "response"].join(""),
  ["raw", "feature", "vector"].join(""),
  ["raw", "evidence"].join(""),
  ["stack", "trace"].join(""),
  ["exception", "message"].join(""),
  ["final", "decision"].join(""),
  ["payment", "decision"].join(""),
  ["payment", "authorization"].join(""),
  ["approve", "payment"].join(""),
  ["decline", "payment"].join(""),
  ["block", "transaction"].join(""),
  ["authorize", "payment"].join("")
];

export function validateFraudFeedbackRequest(request) {
  if (!request || typeof request !== "object" || Array.isArray(request)) {
    return invalid("INVALID_FEEDBACK_REQUEST");
  }
  if (!DECISION_SET.has(request.analystDecision)) {
    return invalid("INVALID_ANALYST_DECISION");
  }
  if (!LABEL_SET.has(request.feedbackLabel)) {
    return invalid("INVALID_FEEDBACK_LABEL");
  }
  if (DECISION_LABEL_MAP[request.analystDecision] !== request.feedbackLabel) {
    return invalid("FEEDBACK_DECISION_LABEL_MISMATCH");
  }
  const reasonCodes = request.decisionReasonCodes;
  if (reasonCodes === null || reasonCodes === undefined) {
    return invalid("REASON_CODES_REQUIRED");
  }
  if (!Array.isArray(reasonCodes)) {
    return invalid("INVALID_REASON_CODES");
  }
  if (reasonCodes.length === 0) {
    return invalid("REASON_CODES_REQUIRED");
  }
  if (reasonCodes.length > MAX_REASON_CODES) {
    return invalid("INVALID_REASON_CODES");
  }
  if (!reasonCodes.every(isReasonCode)) {
    return invalid("INVALID_REASON_CODE");
  }
  if (reasonCodes.some(containsUnsafeTerm)) {
    return invalid("UNSAFE_REASON_CODE");
  }
  if (!reasonCodes.every((code) => REASON_CODE_SET.has(code.trim()))) {
    return invalid("UNKNOWN_REASON_CODE");
  }
  if (!reasonCodes.every((code) => LABEL_REASON_CODE_MAP[request.feedbackLabel].has(code.trim()))) {
    return invalid("REASON_CODE_LABEL_MISMATCH");
  }
  if (request.notes !== null && request.notes !== undefined) {
    if (typeof request.notes !== "string" || request.notes.length > MAX_NOTES_LENGTH) {
      return invalid("INVALID_NOTES");
    }
    if (containsUnsafeTerm(request.notes)) {
      return invalid("UNSAFE_NOTES");
    }
  }
  return Object.freeze({
    valid: true,
    request: Object.freeze({
      analystDecision: request.analystDecision,
      feedbackLabel: request.feedbackLabel,
      decisionReasonCodes: reasonCodes.map((code) => code.trim()),
      notes: normalizeNotes(request.notes)
    })
  });
}

function isReasonCode(value) {
  return typeof value === "string"
    && value.trim().length > 0
    && value.trim().length <= MAX_REASON_CODE_LENGTH
    && REASON_CODE_PATTERN.test(value.trim());
}

function normalizeNotes(value) {
  if (value === null || value === undefined || String(value).trim() === "") {
    return null;
  }
  return String(value).trim();
}

function containsUnsafeTerm(value) {
  const compact = String(value).toLowerCase().replace(/[^a-z0-9]+/g, "");
  return UNSAFE_TERMS.some((term) => compact.includes(term));
}

function invalid(reason) {
  return Object.freeze({ valid: false, reason });
}
