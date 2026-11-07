import { act, render, renderHook, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/apiError.js";
import { normalizeSession } from "../auth/session.js";
import { WorkspaceRuntimeProvider } from "../workspace/WorkspaceRuntimeProvider.jsx";
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

  it.each([408, 429, 500, 502, 504])("locks feedback after an ambiguous HTTP %s response", async (status) => {
    const apiClient = apiClientWith({
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(status, "REMOTE_FAILURE"))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);

    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.submitError).toBeNull();
    expect(result.current.canCreateFeedback).toBe(false);
    await submitFeedback(result);
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("keeps local and server 400 validation failures definite and retryable", async () => {
    const apiClient = apiClientWith({
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(400, "INVALID_FEEDBACK"))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await act(async () => result.current.submit({ ...feedbackRequest(), feedbackLabel: "UNKNOWN" }));
    expect(result.current.submitState).toBe("validation-error");
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();

    await submitFeedback(result);
    expect(result.current.submitState).toBe("error");
    expect(result.current.canCreateFeedback).toBe(true);
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
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

  it("fails closed when the initial GET returns an invalid response", async () => {
    const apiClient = apiClientWith({
      getFraudFeedback: vi.fn().mockResolvedValue(recordedFeedback({ feedbackStatus: "NOT_RECORDED" }))
    });
    const { result } = renderFeedbackHook(apiClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.feedback).toBeNull();
    expect(result.current.error?.error).toBe("INVALID_FEEDBACK_RESPONSE");
    expect(result.current.canCreateFeedback).toBe(false);
    await submitFeedback(result);
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();
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

  it("isolates A and B on the same transaction and retains A uncertainty when returning", async () => {
    const pending = deferred();
    const apiClientA = apiClientWith({
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce(apiError(404, "NOT_FOUND"))
        .mockReturnValueOnce(pending.promise),
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const apiClientB = apiClientWith({
      getFraudFeedback: vi.fn().mockRejectedValue(apiError(404, "NOT_FOUND")),
      createFraudFeedback: vi.fn()
    });
    let session = feedbackSession("analyst-a");
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session,
      authProvider: { kind: "oidc" }
    }, children);
    const { result, rerender } = renderHook(
      ({ client }) => useFraudFeedback({
        transactionId: TRANSACTION_ID,
        scoringOccurrenceToken: SCORING_TOKEN,
        apiClient: client
      }),
      { initialProps: { client: apiClientA }, wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    let reconciliation;
    act(() => {
      reconciliation = result.current.checkRecordedFeedback();
    });
    await waitFor(() => expect(result.current.reconciliationState).toBe("checking"));

    session = feedbackSession("analyst-b");
    rerender({ client: apiClientB });
    await waitFor(() => expect(apiClientB.getFraudFeedback).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.submitState).toBe("idle");
    expect(result.current.feedback).toBeNull();
    expect(result.current.canCreateFeedback).toBe(true);

    await act(async () => {
      pending.resolve(recordedFeedback({ feedbackId: "owner-a-record" }));
      await reconciliation;
    });
    expect(result.current.feedback).toBeNull();
    expect(result.current.submitState).toBe("idle");

    await submitFeedback(result);
    expect(apiClientB.createFraudFeedback).toHaveBeenCalledTimes(1);

    session = feedbackSession("analyst-a");
    rerender({ client: apiClientA });
    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.feedback).toBeNull();
    expect(result.current.canCreateFeedback).toBe(false);
    expect(apiClientA.getFraudFeedback).toHaveBeenCalledTimes(2);
    await submitFeedback(result);
    expect(apiClientA.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("retains an uncertain write across same-owner remount and scoring-token refresh", async () => {
    const apiClient = apiClientWith({
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const session = feedbackSession("analyst-a");
    const authProvider = { kind: "oidc" };
    let latest;
    const Probe = ({ visible, token = SCORING_TOKEN }) => visible
      ? createElement(FeedbackProbe, {
        apiClient,
        token,
        onResult: (value) => { latest = value; }
      })
      : null;
    const renderWorkspace = (visible, token = SCORING_TOKEN) => render(
      createElement(WorkspaceRuntimeProvider, { session, authProvider },
        createElement(Probe, { visible, token })
      )
    );

    const view = renderWorkspace(true);
    await waitFor(() => expect(latest?.isLoading).toBe(false));
    await submitFeedback({ current: latest });
    expect(latest.submitState).toBe("outcome-unknown");
    const initialReads = apiClient.getFraudFeedback.mock.calls.length;

    view.rerender(createElement(WorkspaceRuntimeProvider, { session, authProvider },
      createElement(Probe, { visible: false })
    ));
    view.rerender(createElement(WorkspaceRuntimeProvider, { session, authProvider },
      createElement(Probe, { visible: true, token: "b".repeat(64) })
    ));

    await waitFor(() => expect(latest?.submitState).toBe("outcome-unknown"));
    expect(latest.canCreateFeedback).toBe(false);
    expect(latest.isLoading).toBe(false);
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(initialReads);
    await submitFeedback({ current: latest });
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["200", recordedFeedback({ feedbackId: "owner-b-record" }), null],
    ["404", null, apiError(404, "NOT_FOUND")],
    ["403", null, apiError(403, "FORBIDDEN")]
  ])("keeps owner B's GET %s result local when A has an uncertain write", async (_status, response, failure) => {
    const apiClientA = apiClientWith({
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const apiClientB = apiClientWith({
      getFraudFeedback: failure
        ? vi.fn().mockRejectedValue(failure)
        : vi.fn().mockResolvedValue(response)
    });
    let session = feedbackSession("analyst-a");
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session,
      authProvider: { kind: "oidc" }
    }, children);
    const { result, rerender } = renderHook(
      ({ client }) => useFraudFeedback({
        transactionId: TRANSACTION_ID,
        scoringOccurrenceToken: SCORING_TOKEN,
        apiClient: client
      }),
      { initialProps: { client: apiClientA }, wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    session = feedbackSession("analyst-b");
    rerender({ client: apiClientB });
    await waitFor(() => expect(apiClientB.getFraudFeedback).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.submitState).toBe("idle");
    expect(result.current.feedback).toEqual(response);
    expect(result.current.error?.status ?? null).toBe(failure?.status === 404 ? null : failure?.status ?? null);
    expect(result.current.canCreateFeedback).toBe(failure?.status === 403 ? false : true);
    expect(apiClientB.createFraudFeedback).not.toHaveBeenCalled();
  });

  it("does not let a late A POST response clear uncertainty while B is active", async () => {
    const pendingPost = deferred();
    const apiClientA = apiClientWith({ createFraudFeedback: vi.fn().mockReturnValue(pendingPost.promise) });
    const apiClientB = apiClientWith();
    let session = feedbackSession("analyst-a");
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session,
      authProvider: { kind: "oidc" }
    }, children);
    const { result, rerender } = renderHook(
      ({ client }) => useFraudFeedback({
        transactionId: TRANSACTION_ID,
        scoringOccurrenceToken: SCORING_TOKEN,
        apiClient: client
      }),
      { initialProps: { client: apiClientA }, wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    let post;
    act(() => { post = result.current.submit(feedbackRequest()); });
    expect(apiClientA.createFraudFeedback).toHaveBeenCalledTimes(1);

    session = feedbackSession("analyst-b");
    rerender({ client: apiClientB });
    await waitFor(() => expect(apiClientB.getFraudFeedback).toHaveBeenCalledTimes(1));
    expect(result.current.submitState).toBe("idle");
    expect(result.current.feedback).toBeNull();

    await act(async () => {
      pendingPost.resolve(recordedFeedback({ feedbackId: "late-owner-a-record" }));
      await post;
    });
    expect(result.current.submitState).toBe("idle");
    expect(result.current.feedback).toBeNull();

    session = feedbackSession("analyst-a");
    rerender({ client: apiClientA });
    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.canCreateFeedback).toBe(false);
    expect(apiClientA.getFraudFeedback).toHaveBeenCalledTimes(1);
    expect(apiClientA.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("keeps uncertainty through permission revocation and restoration", async () => {
    const apiClient = apiClientWith({
      createFraudFeedback: vi.fn().mockRejectedValue(apiError(503, "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"))
    });
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: feedbackSession("analyst-a"),
      authProvider: { kind: "oidc" }
    }, children);
    const { result, rerender } = renderHook(
      ({ enabled }) => useFraudFeedback({
        transactionId: TRANSACTION_ID,
        scoringOccurrenceToken: SCORING_TOKEN,
        enabled,
        apiClient
      }),
      { initialProps: { enabled: true }, wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await submitFeedback(result);
    const readsBeforeRevocation = apiClient.getFraudFeedback.mock.calls.length;

    rerender({ enabled: false });
    expect(result.current.canReadFeedback).toBe(false);
    expect(result.current.feedback).toBeNull();
    expect(result.current.canCreateFeedback).toBe(false);

    rerender({ enabled: true });
    expect(result.current.submitState).toBe("outcome-unknown");
    expect(result.current.canCreateFeedback).toBe(false);
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(readsBeforeRevocation);
    await submitFeedback(result);
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("reserves one POST across two hooks sharing the same workspace owner", async () => {
    const pending = deferred();
    const apiClient = apiClientWith({ createFraudFeedback: vi.fn().mockReturnValue(pending.promise) });
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: feedbackSession("analyst-a"),
      authProvider: { kind: "oidc" }
    }, children);
    const { result } = renderHook(() => ({
      first: useFraudFeedback({ transactionId: TRANSACTION_ID, scoringOccurrenceToken: SCORING_TOKEN, apiClient }),
      second: useFraudFeedback({ transactionId: TRANSACTION_ID, scoringOccurrenceToken: SCORING_TOKEN, apiClient })
    }), { wrapper });

    await waitFor(() => expect(result.current.first.isLoading).toBe(false));
    await waitFor(() => expect(result.current.second.isLoading).toBe(false));
    let firstSubmit;
    let secondSubmit;
    act(() => {
      firstSubmit = result.current.first.submit(feedbackRequest());
      secondSubmit = result.current.second.submit(feedbackRequest());
    });

    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
    expect(result.current.first.canCreateFeedback).toBe(false);
    expect(result.current.second.canCreateFeedback).toBe(false);
    await act(async () => {
      pending.resolve(recordedFeedback());
      await Promise.all([firstSubmit, secondSubmit]);
    });
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("fails closed when the runtime has no stable principal identity", async () => {
    const apiClient = apiClientWith();
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: { userId: "", roles: [], authorities: [] },
      authProvider: { kind: "oidc" }
    }, children);
    const { result } = renderHook(() => useFraudFeedback({
      transactionId: TRANSACTION_ID,
      scoringOccurrenceToken: SCORING_TOKEN,
      apiClient
    }), { wrapper });

    expect(result.current.canReadFeedback).toBe(false);
    expect(result.current.canCreateFeedback).toBe(false);
    expect(apiClient.getFraudFeedback).not.toHaveBeenCalled();
    await submitFeedback(result);
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();
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
    expect(result.current.feedback.feedbackId).toBe("ffb-550e8400-e29b-41d4-a716-446655440000");
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });
});

function renderFeedbackHook(apiClient) {
  return renderHook(() => useFraudFeedback({
    transactionId: TRANSACTION_ID,
    scoringOccurrenceToken: SCORING_TOKEN,
    apiClient
  }), {
    wrapper: ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: feedbackSession("analyst-a"),
      authProvider: { kind: "oidc" }
    }, children)
  });
}

async function submitFeedback(result) {
  await act(async () => {
    await result.current.submit(feedbackRequest());
  });
}

function feedbackRequest() {
  return {
    analystDecision: "MARKED_FRAUD",
    feedbackLabel: "CONFIRMED_FRAUD",
    decisionReasonCodes: ["ANALYST_CONFIRMED_FRAUD"],
    notes: null,
    scoringOccurrenceToken: SCORING_TOKEN
  };
}

function feedbackSession(userId) {
  return normalizeSession({
    userId,
    actorId: `actor-${userId}`,
    issuer: "issuer-1",
    subject: `subject-${userId}`,
    roles: ["ANALYST"]
  });
}

function FeedbackProbe({ apiClient, token, onResult }) {
  const result = useFraudFeedback({
    transactionId: TRANSACTION_ID,
    scoringOccurrenceToken: token,
    apiClient
  });
  onResult(result);
  return null;
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
    feedbackId: "ffb-550e8400-e29b-41d4-a716-446655440000",
    transactionId: TRANSACTION_ID,
    feedbackLabel: "CONFIRMED_FRAUD",
    analystDecision: "MARKED_FRAUD",
    labelSource: "ANALYST_REVIEW",
    feedbackStatus: "RECORDED",
    createdAt: "2026-11-07T10:00:00Z",
    createdBy: "analyst-a",
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
