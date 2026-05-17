import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";

const listAlerts = vi.fn();
const getFraudCaseWorkQueueSummary = vi.fn();
const listScoredTransactions = vi.fn();

describe("useWorkspaceCounters", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listAlerts.mockResolvedValue(page(2));
    getFraudCaseWorkQueueSummary.mockResolvedValue({ totalFraudCases: 7 });
    listScoredTransactions.mockResolvedValue(page(4));
  });

  it("loads available workspace counters through the explicit client", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts: true,
      canReadTransactions: true
    }));

    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, fraudCases: null, transactions: 4 }));
    expect(listAlerts).toHaveBeenCalledWith({ page: 0, size: 1 }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(listScoredTransactions).toHaveBeenCalledWith({
      page: 0,
      size: 1,
      query: "",
      riskLevel: "ALL",
      status: "ALL"
    }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(result.current.degraded).toBe(false);
  });

  it("loads the global fraud case counter without mounting the fraud case work queue", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      includeAlerts: false,
      includeFraudCases: true,
      includeTransactions: false,
      canReadFraudCases: true
    }));

    await waitFor(() => expect(result.current.counters).toEqual({ alerts: null, fraudCases: 7, transactions: null }));
    expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledWith(expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it("surfaces partial counter failure without converting missing authority to zero", async () => {
    listAlerts.mockRejectedValue(new Error("alerts unavailable"));
    const client = apiClient();

    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts: true,
      canReadTransactions: true
    }));

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters).toEqual({ alerts: null, fraudCases: null, transactions: 4 });
    expect(result.current.errorByCounter.alerts).toBe("alerts unavailable");
    expect(result.current.stale).toBe(false);
  });

  it("marks retained values stale when a refresh fails after a prior success", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts: true,
      canReadTransactions: true
    }));
    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, fraudCases: null, transactions: 4 }));

    listAlerts.mockRejectedValue(new Error("alerts unavailable"));
    listScoredTransactions.mockResolvedValue(page(5));
    await act(async () => {
      await result.current.refresh();
    });

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters).toEqual({ alerts: 2, fraudCases: null, transactions: 5 });
    expect(result.current.stale).toBe(true);
  });

  it("clears counters when disabled", async () => {
    const client = apiClient();
    const { result, rerender } = renderHook(({ enabled }) => useWorkspaceCounters({
      enabled,
      apiClient: client,
      canReadAlerts: true,
      canReadTransactions: true
    }), { initialProps: { enabled: true } });
    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, fraudCases: null, transactions: 4 }));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.counters).toEqual({ alerts: null, fraudCases: null, transactions: null }));
    expect(result.current.degraded).toBe(false);
  });

  it("does not fetch counters while capabilities are unknown", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client
    }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(listAlerts).not.toHaveBeenCalled();
    expect(listScoredTransactions).not.toHaveBeenCalled();
    expect(result.current.counters).toEqual({ alerts: null, fraudCases: null, transactions: null });
  });

  it("clears a counter when its capability becomes false", async () => {
    const client = apiClient();
    const { result, rerender } = renderHook(({ canReadAlerts }) => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts,
      canReadTransactions: true
    }), { initialProps: { canReadAlerts: true } });
    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, fraudCases: null, transactions: 4 }));

    rerender({ canReadAlerts: false });

    await waitFor(() => expect(result.current.counters.alerts).toBeNull());
    expect(result.current.counters.transactions).toBe(4);
  });

  it("aborts in-flight counter requests on unmount", async () => {
    const alerts = deferred();
    const transactions = deferred();
    listAlerts.mockReturnValue(alerts.promise);
    listScoredTransactions.mockReturnValue(transactions.promise);
    const client = apiClient();
    const { unmount } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts: true,
      canReadTransactions: true
    }));
    await waitFor(() => expect(listAlerts).toHaveBeenCalledTimes(1));
    const alertSignal = listAlerts.mock.calls[0][1].signal;
    const transactionSignal = listScoredTransactions.mock.calls[0][1].signal;

    unmount();

    expect(alertSignal.aborted).toBe(true);
    expect(transactionSignal.aborted).toBe(true);
    await act(async () => {
      alerts.resolve(page(1));
      transactions.resolve(page(2));
      await Promise.all([alerts.promise, transactions.promise]);
    });
  });

  it("ignores stale counter response after workspace session reset key changes", async () => {
    const firstAlerts = deferred();
    const firstTransactions = deferred();
    const secondAlerts = deferred();
    const secondTransactions = deferred();
    const firstClient = {
      listAlerts: vi.fn().mockReturnValue(firstAlerts.promise),
      listScoredTransactions: vi.fn().mockReturnValue(firstTransactions.promise)
    };
    const secondClient = {
      listAlerts: vi.fn().mockReturnValue(secondAlerts.promise),
      listScoredTransactions: vi.fn().mockReturnValue(secondTransactions.promise)
    };
    const { result, rerender } = renderHook(({ client, resetKey }) => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      workspaceSessionResetKey: resetKey,
      canReadAlerts: true,
      canReadTransactions: true
    }), { initialProps: { client: firstClient, resetKey: "session-a" } });
    await waitFor(() => expect(firstClient.listAlerts).toHaveBeenCalledTimes(1));
    const firstAlertSignal = firstClient.listAlerts.mock.calls[0][1].signal;
    const firstTransactionSignal = firstClient.listScoredTransactions.mock.calls[0][1].signal;

    rerender({ client: secondClient, resetKey: "session-b" });
    await waitFor(() => expect(secondClient.listAlerts).toHaveBeenCalledTimes(1));
    expect(firstAlertSignal.aborted).toBe(true);
    expect(firstTransactionSignal.aborted).toBe(true);
    await act(async () => {
      secondAlerts.resolve(page(10));
      secondTransactions.resolve(page(20));
      await Promise.all([secondAlerts.promise, secondTransactions.promise]);
    });
    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 10, fraudCases: null, transactions: 20 }));

    await act(async () => {
      firstAlerts.resolve(page(1));
      firstTransactions.resolve(page(2));
      await Promise.all([firstAlerts.promise, firstTransactions.promise]);
    });

    expect(result.current.counters).toEqual({ alerts: 10, fraudCases: null, transactions: 20 });
  });

  it("does not mark aborted counter requests fresh", async () => {
    listAlerts.mockRejectedValue(new DOMException("aborted", "AbortError"));
    listScoredTransactions.mockRejectedValue(new DOMException("aborted", "AbortError"));
    const client = apiClient();

    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts: true,
      canReadTransactions: true
    }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.lastRefreshedAt).toBeNull();
    expect(result.current.degraded).toBe(false);
    expect(result.current.stale).toBe(false);
  });

  it("keeps degraded stale state when a follow-up counter request is aborted", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts: true,
      canReadTransactions: true
    }));
    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, fraudCases: null, transactions: 4 }));
    listAlerts.mockRejectedValue(new Error("alerts unavailable"));
    listScoredTransactions.mockResolvedValue(page(5));
    await act(async () => {
      await result.current.refresh();
    });
    await waitFor(() => expect(result.current.stale).toBe(true));

    listAlerts.mockRejectedValue(new DOMException("aborted", "AbortError"));
    listScoredTransactions.mockRejectedValue(new DOMException("aborted", "AbortError"));
    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.degraded).toBe(true);
    expect(result.current.stale).toBe(true);
    expect(result.current.errorByCounter.alerts).toBe("alerts unavailable");
  });
});

function apiClient() {
  return {
    listAlerts,
    getFraudCaseWorkQueueSummary,
    listScoredTransactions
  };
}

function page(totalElements) {
  return {
    content: [],
    totalElements,
    totalPages: 1,
    page: 0,
    size: 1
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
