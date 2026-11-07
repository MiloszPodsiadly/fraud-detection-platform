import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/apiError.js";
import { useFraudFeedback } from "./useFraudFeedback.js";

const TRANSACTION_ID = "txn-1";
const SCORING_TOKEN = "a".repeat(64);

describe("useFraudFeedback conflict reasons", () => {
  it.each([
    ["FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED", "stale-occurrence"],
    ["FRAUD_FEEDBACK_ALREADY_RECORDED", "duplicate"],
    ["CONFLICT", "error"]
  ])("classifies %s as %s", async (reasonCode, expectedState) => {
    const apiClient = apiClientWith({
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(409, reasonCode))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);

    expect(result.current.submitState).toBe(expectedState);
  });

  it("locks POST after an uncertain result and reconciles only through GET", async () => {
    const recorded = recordedFeedback({ scoringOccurrenceRelation: "PRIOR" });
    const apiClient = apiClientWith({
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockResolvedValueOnce(recorded),
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);

    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.canCreateFeedback).toBe(false);
    await submitFeedback(result);
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.checkRecordedFeedback();
    });

    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(2);
    expect(apiClient.getFraudFeedback.mock.calls[1][0]).toBe(TRANSACTION_ID);
    expect(result.current.feedback).toEqual(recorded);
    expect(result.current.feedback.scoringOccurrenceRelation).toBe("PRIOR");
    expect(result.current.submitState).toBe("reconciled");
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("keeps a 404 unresolved and permits only another explicit GET", async () => {
    const apiClient = apiClientWith({
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockRejectedValueOnce(apiError(500, "INTERNAL_SERVER_ERROR")),
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    await act(async () => result.current.checkRecordedFeedback());

    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.reconciliationState).toBe("not-found");
    expect(result.current.feedback).toBeNull();
    expect(result.current.canCreateFeedback).toBe(false);

    await act(async () => result.current.checkRecordedFeedback());
    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.reconciliationState).toBe("unresolved");
    expect(result.current.canCreateFeedback).toBe(false);
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(3);
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["wrong transaction", recordedFeedback({ transactionId: "txn-other" })],
    ["unknown relation", recordedFeedback({ scoringOccurrenceRelation: "CURRENTLY" })],
    ["malformed response", { transactionId: TRANSACTION_ID }]
  ])("does not trust a %s reconciliation response", async (_name, response) => {
    const apiClient = apiClientWith({
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockResolvedValueOnce(response),
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    await act(async () => result.current.checkRecordedFeedback());

    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.reconciliationState).toBe("unresolved");
    expect(result.current.feedback).toBeNull();
    expect(result.current.canCreateFeedback).toBe(false);
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("blocks reconciliation after an access denial without clearing the unresolved outcome", async () => {
    const apiClient = apiClientWith({
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockRejectedValueOnce(apiError(403, "FORBIDDEN")),
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    await act(async () => result.current.checkRecordedFeedback());

    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.reconciliationState).toBe("access-blocked");
    expect(result.current.feedback).toBeNull();
    expect(result.current.canCreateFeedback).toBe(false);
    await act(async () => result.current.checkRecordedFeedback());
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(2);
  });

  it("ignores an owner's delayed GET after switching API clients and prevents another POST", async () => {
    const pending = deferred();
    const apiClientA = apiClientWith({
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockReturnValueOnce(pending.promise),
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const apiClientB = apiClientWith({
      getFraudFeedback: vi.fn().mockResolvedValue(recordedFeedback({ scoringOccurrenceRelation: "UNKNOWN" })),
      createFraudFeedback: vi.fn()
    });
    const { result, rerender } = renderHook(
      ({ client }) => useFraudFeedback({
        transactionId: TRANSACTION_ID,
        scoringOccurrenceToken: SCORING_TOKEN,
        apiClient: client
      }),
      { initialProps: { client: apiClientA } }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    let reconciliation;
    act(() => {
      reconciliation = result.current.checkRecordedFeedback();
    });
    await waitFor(() => expect(result.current.reconciliationState).toBe("checking"));

    rerender({ client: apiClientB });
    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.feedback).toBeNull();
    expect(result.current.canCreateFeedback).toBe(false);
    expect(apiClientB.getFraudFeedback).not.toHaveBeenCalled();

    await act(async () => {
      pending.resolve(recordedFeedback({ feedbackId: "owner-a-record" }));
      await reconciliation;
    });
    expect(result.current.feedback).toBeNull();

    await act(async () => result.current.checkRecordedFeedback());
    expect(apiClientB.getFraudFeedback).toHaveBeenCalledTimes(1);
    expect(result.current.feedback.feedbackId).toBe("feedback-1");
    expect(apiClientB.createFraudFeedback).not.toHaveBeenCalled();
  });

  it("does not allow concurrent reconciliation clicks to race", async () => {
    const pending = deferred();
    const apiClient = apiClientWith({
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockReturnValueOnce(pending.promise),
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    let firstCheck;
    act(() => {
      firstCheck = result.current.checkRecordedFeedback();
      void result.current.checkRecordedFeedback();
    });
    await waitFor(() => expect(result.current.reconciliationState).toBe("checking"));
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(2);

    await act(async () => {
      pending.resolve(recordedFeedback());
      await firstCheck;
    });
    expect(result.current.feedback.feedbackId).toBe("feedback-1");
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });
});

function renderFeedbackHook(apiClient) {
  return renderHook(() => useFraudFeedback({
    transactionId: TRANSACTION_ID,
    scoringOccurrenceToken: SCORING_TOKEN,
    apiClient
  }));
}

async function submitFeedback(result) {
  await act(async () => {
    await result.current.submit({
      analystDecision: "MARKED_FRAUD",
      feedbackLabel: "CONFIRMED_FRAUD",
      decisionReasonCodes: ["ANALYST_CONFIRMED_FRAUD"],
      notes: null,
      scoringOccurrenceToken: SCORING_TOKEN
    });
  });
}

function apiClientWith(overrides = {}) {
  return {
    getFraudFeedback: vi.fn().mockRejectedValue(apiError(404, "NOT_FOUND")),
    createFraudFeedback: vi.fn(),
    ...overrides
  };
}

function apiError(status, reasonCode) {
  return new ApiError({
    status,
    error: status === 503 ? "Service Unavailable" : status === 404 ? "Not Found" : "Request failed",
    message: reasonCode,
    details: [`reason:${reasonCode}`]
  });
}

function recordedFeedback(overrides = {}) {
  return {
    feedbackId: "feedback-1",
    transactionId: TRANSACTION_ID,
    feedbackLabel: "CONFIRMED_FRAUD",
    analystDecision: "MARKED_FRAUD",
    labelSource: "ANALYST",
    feedbackStatus: "RECORDED",
    createdAt: "2026-11-07T10:00:00Z",
    decisionReasonCodes: ["ANALYST_CONFIRMED_FRAUD"],
    notesPresent: false,
    scoringOccurrenceRelation: "CURRENT",
    ...overrides
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
