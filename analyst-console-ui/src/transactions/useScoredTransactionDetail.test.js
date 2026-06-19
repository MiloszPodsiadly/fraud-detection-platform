import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/apiError.js";
import { useScoredTransactionDetail } from "./useScoredTransactionDetail.js";

describe("useScoredTransactionDetail", () => {
  it("fetches detail when enabled", async () => {
    const apiClient = apiClientWith(detail());

    const { result } = renderHook(() => useScoredTransactionDetail({
      transactionId: "txn-1",
      enabled: true,
      apiClient
    }));

    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-1"));
    expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledWith("txn-1", expect.objectContaining({
      signal: expect.any(AbortSignal)
    }));
  });

  it("does not fetch when disabled or transactionId is missing", () => {
    const apiClient = apiClientWith(detail());

    renderHook(() => useScoredTransactionDetail({ transactionId: "txn-1", enabled: false, apiClient }));
    renderHook(() => useScoredTransactionDetail({ transactionId: "", enabled: true, apiClient }));

    expect(apiClient.getScoredTransactionDetail).not.toHaveBeenCalled();
  });

  it("clears stale detail when disabled", async () => {
    const apiClient = apiClientWith(detail());
    const { result, rerender } = renderHook(
      ({ enabled }) => useScoredTransactionDetail({ transactionId: "txn-1", enabled, apiClient }),
      { initialProps: { enabled: true } }
    );
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-1"));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.detail).toBeNull());
  });

  it("clears stale detail when transactionId changes", async () => {
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

    await act(async () => first.resolve(detail({ transactionId: "txn-old" })));
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-old"));
    rerender({ transactionId: "txn-new" });

    await waitFor(() => expect(result.current.detail).toBeNull());
    await act(async () => second.resolve(detail({ transactionId: "txn-new" })));
    await waitFor(() => expect(result.current.detail?.transactionId).toBe("txn-new"));
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

  it.each([400, 401, 403, 404, 503])("surfaces %s safely", async (status) => {
    const apiError = new ApiError({ status, message: `status ${status}` });
    const apiClient = { getScoredTransactionDetail: vi.fn().mockRejectedValue(apiError) };

    const { result } = renderHook(() => useScoredTransactionDetail({ transactionId: "txn-1", enabled: true, apiClient }));

    await waitFor(() => expect(result.current.error).toBe(apiError));
  });
});

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
  return {
    transactionId: "txn-1",
    engineIntelligence: {
      status: "AVAILABLE",
      contractVersion: 1,
      generatedAt: "2026-06-18T10:00:00Z",
      comparison: {
        agreementStatus: "PARTIAL",
        riskMismatchStatus: "NOT_COMPARABLE",
        scoreDeltaBucket: "UNAVAILABLE"
      },
      engines: [],
      diagnosticSignals: [],
      warnings: []
    },
    ...overrides
  };
}
