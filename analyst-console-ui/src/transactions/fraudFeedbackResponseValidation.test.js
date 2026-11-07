import { describe, expect, it } from "vitest";
import { isValidFraudFeedbackResponse } from "./fraudFeedbackResponseValidation.js";

const TRANSACTION_ID = "txn-1";

describe("fraud feedback response validation", () => {
  it.each([
    ["unknown label", { feedbackLabel: "UNKNOWN_LABEL" }],
    ["unknown decision", { analystDecision: "UNKNOWN_DECISION" }],
    ["unknown source", { labelSource: "ANALYST" }],
    ["unknown status", { feedbackStatus: "NOT_RECORDED" }],
    ["unknown relation", { scoringOccurrenceRelation: "CURRENTLY" }],
    ["mismatched decision and label", { analystDecision: "MARKED_LEGITIMATE" }],
    ["wrong transaction", { transactionId: "txn-other" }],
    ["invalid feedback id", { feedbackId: " contains spaces " }],
    ["invalid transaction id", { transactionId: "txn/1" }],
    ["invalid timestamp", { createdAt: "2026-02-30T10:00:00Z" }],
    ["wrong notes indicator type", { notesPresent: "false" }],
    ["unknown reason code", { decisionReasonCodes: ["UNKNOWN_REASON"] }],
    ["mismatched reason code", { decisionReasonCodes: ["ANALYST_CONFIRMED_LEGITIMATE"] }],
    ["excessive reason codes", { decisionReasonCodes: Array(11).fill("ANALYST_CONFIRMED_FRAUD") }],
    ["empty actor", { createdBy: "" }],
    ["whitespace-only actor", { createdBy: "   " }],
    ["non-string actor", { createdBy: 42 }],
    ["actor with a control character", { createdBy: "analyst\u0085id" }],
    ["actor with a bidi override", { createdBy: "analyst\u202e-id" }],
    ["missing required field", { feedbackStatus: undefined }],
    ["unrecognized response property", { notes: "private note" }]
  ])("rejects %s", (_name, overrides) => {
    expect(isValidFraudFeedbackResponse({ ...validFeedback(), ...overrides }, TRANSACTION_ID)).toBe(false);
  });

  it("accepts the canonical backend response", () => {
    expect(isValidFraudFeedbackResponse(validFeedback(), TRANSACTION_ID)).toBe(true);
  });

  it.each([
    "analyst-token-42",
    "analyst-password-42",
    "analyst-secret-42",
    "<img src=x onerror=alert(1)>"
  ])("accepts a bounded backend actor identifier as text: %s", (createdBy) => {
    expect(isValidFraudFeedbackResponse({ ...validFeedback(), createdBy }, TRANSACTION_ID)).toBe(true);
  });

  it.each(["PRIOR", "UNKNOWN"])("accepts legacy %s feedback with nullable optional snapshots", (relation) => {
    const response = {
      ...validFeedback(),
      scoringOccurrenceRelation: relation,
      createdBy: null,
      customerId: null,
      correlationId: null,
      fraudScore: null,
      riskLevel: null,
      alertRecommended: null,
      scoredAt: null,
      transactionTimestamp: null,
      engineIntelligenceStatus: null,
      comparisonType: null,
      comparedEngineIds: [],
      agreementStatus: null,
      riskMismatchStatus: null,
      scoreDeltaBucket: null,
      analystRecommendationStatus: null,
      analystRecommendation: null,
      analystRecommendationVersion: null,
      analystRecommendationGeneratedAt: null,
      analystRecommendationReasonCodes: []
    };

    expect(isValidFraudFeedbackResponse(response, TRANSACTION_ID)).toBe(true);
  });

  it("does not accept a missing required reason array", () => {
    const response = validFeedback();
    delete response.decisionReasonCodes;

    expect(isValidFraudFeedbackResponse(response, TRANSACTION_ID)).toBe(false);
  });
});

function validFeedback() {
  return {
    feedbackId: "ffb-550e8400-e29b-41d4-a716-446655440000",
    transactionId: TRANSACTION_ID,
    analystDecision: "MARKED_FRAUD",
    feedbackLabel: "CONFIRMED_FRAUD",
    labelSource: "ANALYST_REVIEW",
    feedbackStatus: "RECORDED",
    createdAt: "2026-11-07T10:00:00Z",
    createdBy: "analyst-a",
    decisionReasonCodes: ["ANALYST_CONFIRMED_FRAUD"],
    notesPresent: false,
    scoringOccurrenceRelation: "CURRENT"
  };
}
