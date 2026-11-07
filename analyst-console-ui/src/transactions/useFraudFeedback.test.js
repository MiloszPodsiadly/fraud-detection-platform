import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/apiError.js";
import { useFraudFeedback } from "./useFraudFeedback.js";

describe("useFraudFeedback conflict reasons", () => {
  it.each([
    ["FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED", "stale-occurrence"],
    ["FRAUD_FEEDBACK_ALREADY_RECORDED", "duplicate"],
    ["CONFLICT", "error"]
  ])("classifies %s as %s", async (reasonCode, expectedState) => {
    const apiClient = {
      getFraudFeedback: vi.fn().mockResolvedValue(null),
      createFraudFeedback: vi.fn().mockRejectedValue(new ApiError({
        status: 409,
        error: "Conflict",
        message: "Request conflict.",
        details: [`reason:${reasonCode}`]
      }))
    };
    const scoringOccurrenceToken = "a".repeat(64);
    const { result } = renderHook(() => useFraudFeedback({
      transactionId: "txn-1",
      scoringOccurrenceToken,
      apiClient
    }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await act(async () => {
      await result.current.submit({
        analystDecision: "MARKED_FRAUD",
        feedbackLabel: "CONFIRMED_FRAUD",
        decisionReasonCodes: ["ANALYST_CONFIRMED_FRAUD"],
        notes: null,
        scoringOccurrenceToken
      });
    });

    expect(result.current.submitState).toBe(expectedState);
  });
});
