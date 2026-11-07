import { act, renderHook, waitFor } from "@testing-library/react";
import { useLayoutEffect } from "react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/apiError.js";
import { availableDetail } from "./transactionRiskIntelligenceFixtures.js";
import { useScoredTransactionDetail } from "./useScoredTransactionDetail.js";

describe("useScoredTransactionDetail", () => {
  it("normalizes the requested transaction ID and fetches its detail when enabled", async () => {
    const apiClient = apiClientWith(detail());

    const { result } = renderHook(() => useScoredTransactionDetail({
      transactionId: "  txn-1  ",
      enabled: true,
      apiClient
    }));

    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-1"));
    expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledWith("txn-1", expect.objectContaining({
      signal: expect.any(AbortSignal)
    }));
  });

  it("fences detail by the scoring occurrence reported by the list row", async () => {
    const firstToken = "a".repeat(64);
    const nextToken = "b".repeat(64);
    const refreshed = deferred();
    const apiClient = {
      getScoredTransactionDetail: vi.fn()
        .mockResolvedValueOnce(detail({ scoringOccurrenceToken: firstToken }))
        .mockReturnValueOnce(refreshed.promise)
    };
    const { result, rerender } = renderHook(
      ({ expectedScoringOccurrenceToken }) => useScoredTransactionDetail({
        transactionId: "txn-1", enabled: true, apiClient, expectedScoringOccurrenceToken
      }),
      { initialProps: { expectedScoringOccurrenceToken: firstToken } }
    );

    await waitFor(() => expect(result.current.detail?.scoringOccurrenceToken).toBe(firstToken));
    rerender({ expectedScoringOccurrenceToken: nextToken });
    expect(result.current.detail).toBeNull();
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(2));
    await act(async () => refreshed.resolve(detail({ scoringOccurrenceToken: nextToken })));
    await waitFor(() => expect(result.current.detail?.scoringOccurrenceToken).toBe(nextToken));
  });

  it("rejects detail that does not match the list row occurrence", async () => {
    const apiClient = apiClientWith(detail({ scoringOccurrenceToken: "b".repeat(64) }));
    const { result } = renderHook(() => useScoredTransactionDetail({
      transactionId: "txn-1", enabled: true, apiClient, expectedScoringOccurrenceToken: "a".repeat(64)
    }));

    await waitFor(() => expect(result.current.error?.message).toBe("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE"));
    expect(result.current.detail).toBeNull();
  });

  it("does not fetch when disabled or transactionId is missing", () => {
    const apiClient = apiClientWith(detail());

    renderHook(() => useScoredTransactionDetail({ transactionId: "txn-1", enabled: false, apiClient }));
    renderHook(() => useScoredTransactionDetail({ transactionId: "", enabled: true, apiClient }));

    expect(apiClient.getScoredTransactionDetail).not.toHaveBeenCalled();
  });

  it("clears stale detail when disabled", async () => {
    const apiClient = apiClientWith(detail());
    const committedFrames = [];
    const { result, rerender } = renderHook(
      ({ enabled }) => useDetailWithCommitProbe({ transactionId: "txn-1", enabled, apiClient }, committedFrames),
      { initialProps: { enabled: true } }
    );
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-1"));

    committedFrames.length = 0;
    rerender({ enabled: false });

    expect(result.current.detail).toBeNull();
    expectNoDetailInCommittedFrames(committedFrames);
  });

  it("aborts and hides a pending detail immediately when disabled", async () => {
    const pending = deferred();
    const apiClient = { getScoredTransactionDetail: vi.fn().mockReturnValue(pending.promise) };
    const { result, rerender } = renderHook(
      ({ enabled }) => useScoredTransactionDetail({ transactionId: "txn-1", enabled, apiClient }),
      { initialProps: { enabled: true } }
    );
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));
    const signal = apiClient.getScoredTransactionDetail.mock.calls[0][1].signal;

    rerender({ enabled: false });

    expect(result.current.detail).toBeNull();
    expect(result.current.isLoading).toBe(false);
    expect(signal.aborted).toBe(true);
    await act(async () => pending.resolve(detail()));
    expect(result.current.detail).toBeNull();
  });

  it("clears stale detail before passive effects when transactionId changes", async () => {
    const committedFrames = [];
    const first = deferred();
    const second = deferred();
    const apiClient = {
      getScoredTransactionDetail: vi.fn()
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise)
    };
    const { result, rerender } = renderHook(
      ({ transactionId }) => useDetailWithCommitProbe({ transactionId, enabled: true, apiClient }, committedFrames),
      { initialProps: { transactionId: "txn-old" } }
    );

    await act(async () => first.resolve(detail({ transactionId: "txn-old" })));
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-old"));
    committedFrames.length = 0;
    rerender({ transactionId: "txn-new" });

    expect(result.current.detail).toBeNull();
    expectNoDetailInCommittedFrames(committedFrames);
    await act(async () => second.resolve(detail({ transactionId: "txn-new" })));
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-new"));
  });

  it.each(["api client", "session", "auth provider"])("hides the prior detail immediately when the %s changes", async (changedIdentity) => {
    const committedFrames = [];
    const second = deferred();
    const apiClient = apiClientWith(detail({ transactionId: "txn-1" }));
    const nextApiClient = changedIdentity === "api client"
      ? { getScoredTransactionDetail: vi.fn().mockReturnValue(second.promise) }
      : apiClient;
    const initialProps = {
      transactionId: "txn-1",
      enabled: true,
      apiClient,
      session: { userId: "analyst-1", issuer: "issuer-1" },
      authProvider: { kind: "oidc" }
    };
    const { result, rerender } = renderHook(
      ({ props }) => useDetailWithCommitProbe(props, committedFrames),
      { initialProps: { props: initialProps } }
    );
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-1"));

    const nextProps = { ...initialProps, apiClient: nextApiClient };
    if (changedIdentity === "session") {
      nextProps.session = { ...initialProps.session, issuer: "issuer-2" };
    } else if (changedIdentity === "auth provider") {
      nextProps.authProvider = { kind: "oidc" };
    }
    if (nextApiClient === apiClient) {
      apiClient.getScoredTransactionDetail.mockReturnValue(second.promise);
    }

    committedFrames.length = 0;
    rerender({ props: nextProps });

    expect(result.current.detail).toBeNull();
    expect(result.current.isLoading).toBe(true);
    expectNoDetailInCommittedFrames(committedFrames);
    await waitFor(() => expect(nextApiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(changedIdentity === "api client" ? 1 : 2));
    await act(async () => second.resolve(detail({ transactionId: "txn-1" })));
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-1"));
  });

  it("ignores an old response that resolves after the session and API client change", async () => {
    const oldRequest = deferred();
    const newRequest = deferred();
    const oldApiClient = { getScoredTransactionDetail: vi.fn().mockReturnValue(oldRequest.promise) };
    const newApiClient = { getScoredTransactionDetail: vi.fn().mockReturnValue(newRequest.promise) };
    const { result, rerender } = renderHook(
      ({ props }) => useScoredTransactionDetail(props),
      {
        initialProps: {
          props: {
            transactionId: "txn-1",
            enabled: true,
            apiClient: oldApiClient,
            session: { userId: "analyst-1", issuer: "issuer-1" },
            authProvider: { kind: "oidc" }
          }
        }
      }
    );
    await waitFor(() => expect(oldApiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));
    const oldSignal = oldApiClient.getScoredTransactionDetail.mock.calls[0][1].signal;

    rerender({
      props: {
        transactionId: "txn-1",
        enabled: true,
        apiClient: newApiClient,
        session: { userId: "analyst-2", issuer: "issuer-2" },
        authProvider: { kind: "oidc" }
      }
    });
    await waitFor(() => expect(newApiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));
    expect(oldSignal.aborted).toBe(true);
    expect(result.current.detail).toBeNull();

    await act(async () => oldRequest.resolve(detail({ transactionId: "txn-1" })));
    expect(result.current.detail).toBeNull();

    await act(async () => newRequest.resolve(detail({ transactionId: "txn-1" })));
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-1"));
  });

  it("aborts in-flight request on unmount", async () => {
    const pending = deferred();
    const apiClient = { getScoredTransactionDetail: vi.fn().mockReturnValue(pending.promise) };

    const { unmount } = renderHook(() => useScoredTransactionDetail({ transactionId: "txn-1", enabled: true, apiClient }));
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));
    const signal = apiClient.getScoredTransactionDetail.mock.calls[0][1].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("aborts previous request on transactionId change", async () => {
    const apiClient = { getScoredTransactionDetail: vi.fn().mockReturnValue(new Promise(() => {})) };
    const { rerender } = renderHook(
      ({ transactionId }) => useScoredTransactionDetail({ transactionId, enabled: true, apiClient }),
      { initialProps: { transactionId: "txn-old" } }
    );
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));
    const firstSignal = apiClient.getScoredTransactionDetail.mock.calls[0][1].signal;

    rerender({ transactionId: "txn-new" });
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(2));

    expect(firstSignal.aborted).toBe(true);
  });

  it("stale response cannot overwrite newer response", async () => {
    const first = deferred();
    const second = deferred();
    const apiClient = {
      getScoredTransactionDetail: vi.fn()
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise)
    };
    const { result, rerender } = renderHook(
      ({ transactionId }) => useScoredTransactionDetail({ transactionId, enabled: true, apiClient }),
      { initialProps: { transactionId: "txn-old" } }
    );
    rerender({ transactionId: "txn-new" });

    await act(async () => second.resolve(detail({ transactionId: "txn-new" })));
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-new"));
    await act(async () => first.resolve(detail({ transactionId: "txn-old" })));

    expect(result.current.detail.transactionId).toBe("txn-new");
  });

  it("refresh calls the same read-only endpoint again", async () => {
    const apiClient = apiClientWith(detail());
    const { result } = renderHook(() => useScoredTransactionDetail({ transactionId: "txn-1", enabled: true, apiClient }));
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));

    await act(async () => {
      await result.current.refresh();
    });

    expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(2);
    expect(apiClient.getScoredTransactionDetail.mock.calls[1][0]).toBe("txn-1");
  });

  it("invalid response becomes safe error state", async () => {
    const apiClient = apiClientWith({ transactionId: "txn-1" });
    const { result } = renderHook(() => useScoredTransactionDetail({ transactionId: "txn-1", enabled: true, apiClient }));

    await waitFor(() => expect(result.current.error?.message).toBe("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE"));
    expect(result.current.detail).toBeNull();
  });

  it("fails closed on malformed top-level scalars without exposing the response", async () => {
    const rawMarker = "PRIVATE_RAW_DETAIL_CANARY";
    const apiClient = apiClientWith(detail({ correlationId: { rawMarker } }));
    const { result } = renderHook(() => useScoredTransactionDetail({
      transactionId: "txn-1",
      enabled: true,
      apiClient
    }));

    await waitFor(() => expect(result.current.error?.message).toBe("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE"));
    expect(result.current.detail).toBeNull();
    expect(result.current.error?.code).toBe("INVALID_DETAIL_CORRELATION_ID");
    expect(JSON.stringify({ detail: result.current.detail, error: result.current.error })).not.toContain(rawMarker);
  });

  it("rejects a response that belongs to a different transaction", async () => {
    const apiClient = apiClientWith(detail({ transactionId: "txn-other" }));
    const { result } = renderHook(() => useScoredTransactionDetail({
      transactionId: "txn-1",
      enabled: true,
      apiClient
    }));

    await waitFor(() => expect(result.current.error?.code).toBe("TRANSACTION_ID_MISMATCH"));
    expect(result.current.error?.message).toBe("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE");
    expect(result.current.detail).toBeNull();
  });

  it.each([400, 401, 403, 404, 503])("surfaces %s safely", async (status) => {
    const apiError = new ApiError({ status, message: `status ${status}` });
    const apiClient = { getScoredTransactionDetail: vi.fn().mockRejectedValue(apiError) };

    const { result } = renderHook(() => useScoredTransactionDetail({ transactionId: "txn-1", enabled: true, apiClient }));

    await waitFor(() => expect(result.current.error).toBe(apiError));
  });
});

function useDetailWithCommitProbe(props, committedFrames) {
  const result = useScoredTransactionDetail(props);
  useLayoutEffect(() => {
    committedFrames.push({ detail: result.detail });
  });
  return result;
}

function expectNoDetailInCommittedFrames(committedFrames) {
  expect(committedFrames.length).toBeGreaterThan(0);
  for (const frame of committedFrames) {
    expect(frame.detail).toBeNull();
  }
}

function apiClientWith(response) {
  return {
    getScoredTransactionDetail: vi.fn().mockResolvedValue(response)
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function detail(overrides = {}) {
  return availableDetail({
    transactionId: "txn-1",
    ...overrides
  });
}
