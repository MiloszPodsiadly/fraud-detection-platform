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
    ["invalid reason code", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", decisionReasonCodes: ["bad-code"] }, "INVALID_REASON_CODE"],
    ["unsafe note", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", notes: ["raw", "Ml", "Request"].join("") + " pasted" }, "UNSAFE_NOTES"],
    ["long notes", { analystDecision: "MARKED_FRAUD", feedbackLabel: "CONFIRMED_FRAUD", notes: "x".repeat(501) }, "INVALID_NOTES"]
  ])("rejects %s", (_name, request, reason) => {
    expect(validateFraudFeedbackRequest(request)).toEqual({ valid: false, reason });
  });
});
