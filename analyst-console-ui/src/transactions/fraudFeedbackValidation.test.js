import { describe, expect, it } from "vitest";
import { validateFraudFeedbackRequest } from "./fraudFeedbackValidation.js";

describe("fraudFeedbackValidation", () => {
  it("accepts bounded analyst feedback request", () => {
    expect(validateFraudFeedbackRequest({
      analystDecision: "MARKED_FRAUD",
      feedbackLabel: "CONFIRMED_FRAUD",
      decisionReasonCodes: ["CUSTOMER_CONFIRMED_FRAUD"],
      notes: "Customer confirmed fraud"
    })).toMatchObject({ valid: true });
  });

  it.each([
    ["invalid decision", { analystDecision: ["APPROVE", "PAYMENT"].join("_"), feedbackLabel: "CONFIRMED_FRAUD" }, "INVALID_ANALYST_DECISION"],
    ["invalid label", { analystDecision: "MARKED_FRAUD", feedbackLabel: "SAFE" }, "INVALID_FEEDBACK_LABEL"],
    ["too many reason codes", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", decisionReasonCodes: Array(11).fill("CODE") }, "INVALID_REASON_CODES"],
    ["unknown reason code", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", decisionReasonCodes: ["UNKNOWN_REASON"] }, "UNKNOWN_REASON_CODE"],
    ["random reason code", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", decisionReasonCodes: ["RANDOM_REASON"] }, "UNKNOWN_REASON_CODE"],
    ["invalid reason code", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", decisionReasonCodes: ["bad-code"] }, "INVALID_REASON_CODE"],
    ["unsafe reason code", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", decisionReasonCodes: [["raw", "Ml", "Request"].join("")] }, "INVALID_REASON_CODE"],
    ["unsafe note", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", notes: ["raw", "Ml", "Request"].join("") + " pasted" }, "UNSAFE_NOTES"],
    ["long notes", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", notes: "x".repeat(501) }, "INVALID_NOTES"],
    ["fraud decision with legitimate label", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_LEGITIMATE" }, "FEEDBACK_DECISION_LABEL_MISMATCH"],
    ["legitimate decision with fraud label", { analystDecision: "MARKED_LEGITIMATE", feedbackLabel: "CONFIRMED_FRAUD" }, "FEEDBACK_DECISION_LABEL_MISMATCH"],
    ["inconclusive decision with legitimate label", { analystDecision: "MARKED_INCONCLUSIVE", feedbackLabel: "CONFIRMED_LEGITIMATE" }, "FEEDBACK_DECISION_LABEL_MISMATCH"],
    ["more info decision with fraud label", { analystDecision: "REQUESTED_MORE_INFO", feedbackLabel: "CONFIRMED_FRAUD" }, "FEEDBACK_DECISION_LABEL_MISMATCH"]
  ])("rejects %s", (_name, request, reason) => {
    expect(validateFraudFeedbackRequest(request)).toEqual({ valid: false, reason });
  });

  it.each([
    ["MARKED_FRAUD", "CONFIRMED_FRAUD", "ANALYST_CONFIRMED_FRAUD"],
    ["MARKED_LEGITIMATE", "CONFIRMED_LEGITIMATE", "ANALYST_CONFIRMED_LEGITIMATE"],
    ["MARKED_INCONCLUSIVE", "INCONCLUSIVE", "ANALYST_INCONCLUSIVE"],
    ["REQUESTED_MORE_INFO", "NEEDS_MORE_INFO", "ANALYST_NEEDS_MORE_INFO"]
  ])("accepts valid %s and %s pair", (analystDecision, feedbackLabel, reasonCode) => {
    expect(validateFraudFeedbackRequest({
      analystDecision,
      feedbackLabel,
      decisionReasonCodes: [reasonCode]
    })).toMatchObject({ valid: true });
  });

  it("accepts allowlisted customer and analyst reason codes", () => {
    expect(validateFraudFeedbackRequest({
      analystDecision: "MARKED_FRAUD",
      feedbackLabel: "CONFIRMED_FRAUD",
      decisionReasonCodes: ["CUSTOMER_CONFIRMED_FRAUD", "ANALYST_CONFIRMED_FRAUD"]
    })).toMatchObject({ valid: true });
  });

  it.each([
    ["MARKED_FRAUD", "CONFIRMED_FRAUD", "CUSTOMER_CONFIRMED_FRAUD"],
    ["MARKED_LEGITIMATE", "CONFIRMED_LEGITIMATE", "CUSTOMER_CONFIRMED_LEGITIMATE"],
    ["MARKED_INCONCLUSIVE", "INCONCLUSIVE", "INSUFFICIENT_EVIDENCE"],
    ["REQUESTED_MORE_INFO", "NEEDS_MORE_INFO", "NEEDS_CUSTOMER_CONTACT"]
  ])("accepts %s and %s with compatible %s reason code", (analystDecision, feedbackLabel, reasonCode) => {
    expect(validateFraudFeedbackRequest({
      analystDecision,
      feedbackLabel,
      decisionReasonCodes: [reasonCode]
    })).toMatchObject({ valid: true });
  });

  it.each([
    ["MARKED_FRAUD", "CONFIRMED_FRAUD", "CUSTOMER_CONFIRMED_LEGITIMATE"],
    ["MARKED_LEGITIMATE", "CONFIRMED_LEGITIMATE", "CUSTOMER_CONFIRMED_FRAUD"],
    ["MARKED_INCONCLUSIVE", "INCONCLUSIVE", "CUSTOMER_CONFIRMED_FRAUD"],
    ["REQUESTED_MORE_INFO", "NEEDS_MORE_INFO", "CHARGEBACK_SIGNAL"]
  ])("rejects %s and %s with incompatible %s reason code", (analystDecision, feedbackLabel, reasonCode) => {
    expect(validateFraudFeedbackRequest({
      analystDecision,
      feedbackLabel,
      decisionReasonCodes: [reasonCode]
    })).toEqual({ valid: false, reason: "REASON_CODE_LABEL_MISMATCH" });
  });
});
