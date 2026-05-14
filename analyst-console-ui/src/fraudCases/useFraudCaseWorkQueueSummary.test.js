import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useFraudCaseWorkQueueSummary } from "./useFraudCaseWorkQueueSummary.js";

const getFraudCaseWorkQueueSummary = vi.fn();

describe("useFraudCaseWorkQueueSummary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getFraudCaseWorkQueueSummary.mockResolvedValue(summary(46));
  });

  it("loads the global summary only when enabled", async () => {
    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({
      enabled: true,
      session: { userId: "u1" },
      authProvider: { kind: "oidc" },
      apiClient: summaryApiClient
    }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1);
    expect(result.current.summary.totalFraudCases).toBe(46);
  });

  it("does not call the summary endpoint while disabled", () => {
    renderHook(() => useFraudCaseWorkQueueSummary({ enabled: false, apiClient: summaryApiClient }));

    expect(getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
  });

  it("isolates failures in local hook state", async () => {
    const apiError = { status: 403, message: "forbidden" };
    getFraudCaseWorkQueueSummary.mockRejectedValue(apiError);

    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({ enabled: true, apiClient: summaryApiClient }));

    await waitFor(() => expect(result.current.error).toBe(apiError));
    expect(result.current.summary.totalFraudCases).toBe(0);
  });

  it("retries on demand without changing external session state", async () => {
    getFraudCaseWorkQueueSummary
      .mockRejectedValueOnce({ status: 503, message: "down" })
      .mockResolvedValueOnce(summary(50));
    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({ enabled: true, apiClient: summaryApiClient }));
    await waitFor(() => expect(result.current.error?.status).toBe(503));

    await act(async () => {
      await result.current.retry();
    });

    await waitFor(() => expect(result.current.summary.totalFraudCases).toBe(50));
    expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(2);
  });

  it("does not call summary endpoint when authority gate is false", () => {
    renderHook(() => useFraudCaseWorkQueueSummary({ enabled: true, canReadFraudCases: false, apiClient: summaryApiClient }));

    expect(getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
  });

  it("aborts previous summary request when retry supersedes it", async () => {
    const first = deferred();
    getFraudCaseWorkQueueSummary
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(summary(51));
    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({ enabled: true, canReadFraudCases: true, apiClient: summaryApiClient }));
    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    const firstSignal = getFraudCaseWorkQueueSummary.mock.calls[0][0].signal;

    await act(async () => {
      await result.current.retry();
    });

    expect(firstSignal.aborted).toBe(true);
    await waitFor(() => expect(result.current.summary.totalFraudCases).toBe(51));
  });

  it("ignores AbortError without exposing a local error", async () => {
    getFraudCaseWorkQueueSummary.mockRejectedValue(new DOMException("aborted", "AbortError"));

    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({ enabled: true, apiClient: summaryApiClient }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBeNull();
  });

  it("clears summary when authority gate is lost after data was loaded", async () => {
    const { result, rerender } = renderHook(
      ({ canReadFraudCases }) => useFraudCaseWorkQueueSummary({
        enabled: true,
        canReadFraudCases,
        session: { userId: "u1" },
        authProvider: { kind: "bff" },
        apiClient: summaryApiClient
      }),
      { initialProps: { canReadFraudCases: true } }
    );
    await waitFor(() => expect(result.current.summary.totalFraudCases).toBe(46));

    rerender({ canReadFraudCases: false });

    await waitFor(() => expect(result.current.summary.totalFraudCases).toBe(0));
    expect(result.current.error).toBeNull();
  });
});

function summary(totalFraudCases) {
  return {
    totalFraudCases,
    generatedAt: "2026-05-12T10:00:00Z",
    scope: "GLOBAL_FRAUD_CASES",
    snapshotConsistentWithWorkQueue: false
  };
}

const summaryApiClient = {
  getFraudCaseWorkQueueSummary
};

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}
