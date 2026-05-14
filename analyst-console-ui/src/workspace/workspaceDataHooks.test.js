import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAlertQueue } from "./useAlertQueue.js";
import { useGovernanceAnalytics } from "./useGovernanceAnalytics.js";
import { useGovernanceQueue } from "./useGovernanceQueue.js";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";

const getGovernanceAdvisoryAnalytics = vi.fn();
const getGovernanceAdvisoryAudit = vi.fn();
const listAlerts = vi.fn();
const listGovernanceAdvisories = vi.fn();
const listScoredTransactions = vi.fn();

describe("workspace data hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listAlerts.mockResolvedValue(page([]));
    listScoredTransactions.mockResolvedValue(page([]));
    listGovernanceAdvisories.mockResolvedValue({ status: "AVAILABLE", count: 0, advisory_events: [] });
    getGovernanceAdvisoryAudit.mockResolvedValue({ status: "AVAILABLE", audit_events: [] });
    getGovernanceAdvisoryAnalytics.mockResolvedValue({
      status: "AVAILABLE",
      window: { days: 7 },
      totals: { advisories: 1, reviewed: 1, open: 0 },
      decision_distribution: {},
      lifecycle_distribution: {},
      review_timeliness: { status: "LOW_CONFIDENCE" }
    });
  });

  it("does not fetch alert queue while disabled", () => {
    renderHook(() => useAlertQueue({ enabled: false, apiClient: workspaceApiClient }));

    expect(listAlerts).not.toHaveBeenCalled();
  });

  it("loads alert queue when enabled", async () => {
    listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }], { totalElements: 1 }));

    const { result } = renderHook(() => useAlertQueue({ enabled: true, apiClient: workspaceApiClient }));

    await waitFor(() => expect(result.current.page.content).toEqual([{ alertId: "alert-1" }]));
    expect(result.current.page.totalElements).toBe(1);
  });

  it("does not fetch workspace data without an explicit apiClient", async () => {
    renderHook(() => useAlertQueue({ enabled: true, apiClient: null }));
    renderHook(() => useScoredTransactionStream({ enabled: true, apiClient: null }));
    renderHook(() => useGovernanceQueue({ enabled: true, apiClient: null }));
    renderHook(() => useGovernanceAnalytics({ enabled: true, apiClient: null }));

    expect(listAlerts).not.toHaveBeenCalled();
    expect(listScoredTransactions).not.toHaveBeenCalled();
    expect(listGovernanceAdvisories).not.toHaveBeenCalled();
    expect(getGovernanceAdvisoryAnalytics).not.toHaveBeenCalled();
  });

  it("aborts scored transaction request on unmount", async () => {
    listScoredTransactions.mockReturnValue(new Promise(() => {}));
    const { unmount } = renderHook(() => useScoredTransactionStream({ enabled: true, apiClient: workspaceApiClient }));
    await waitFor(() => expect(listScoredTransactions).toHaveBeenCalledTimes(1));
    const signal = listScoredTransactions.mock.calls[0][1].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("ignores scored transaction AbortError", async () => {
    listScoredTransactions.mockRejectedValue(new DOMException("aborted", "AbortError"));
    const { result } = renderHook(() => useScoredTransactionStream({ enabled: true, apiClient: workspaceApiClient }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBeNull();
  });

  it("clears alert queue state when disabled after data was loaded", async () => {
    listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }], { totalElements: 1 }));
    const apiClient = workspaceApiClient;
    const { result, rerender } = renderHook(({ enabled }) => useAlertQueue({ enabled, apiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.page.content).toEqual([{ alertId: "alert-1" }]));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.page.content).toEqual([]));
    expect(result.current.error).toBeNull();
  });

  it("clears scored transaction state when disabled after data was loaded", async () => {
    listScoredTransactions.mockResolvedValue(page([{ transactionId: "txn-1" }], { totalElements: 1 }));
    const apiClient = workspaceApiClient;
    const { result, rerender } = renderHook(({ enabled }) => useScoredTransactionStream({ enabled, apiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.page.content).toEqual([{ transactionId: "txn-1" }]));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.page.content).toEqual([]));
    expect(result.current.error).toBeNull();
  });

  it("keeps scored transaction data isolated across apiClient session switch", async () => {
    const first = deferred();
    const second = deferred();
    const apiClientA = { ...workspaceApiClient, listScoredTransactions: vi.fn().mockReturnValue(first.promise) };
    const apiClientB = { ...workspaceApiClient, listScoredTransactions: vi.fn().mockReturnValue(second.promise) };
    const { result, rerender } = renderHook(
      ({ session, apiClient }) => useScoredTransactionStream({ enabled: true, session, authProvider: { kind: "demo" }, apiClient }),
      { initialProps: { session: { userId: "user-a" }, apiClient: apiClientA } }
    );
    await waitFor(() => expect(apiClientA.listScoredTransactions).toHaveBeenCalledTimes(1));

    rerender({ session: { userId: "user-b" }, apiClient: apiClientB });
    await waitFor(() => expect(apiClientB.listScoredTransactions).toHaveBeenCalledTimes(1));

    await act(async () => {
      second.resolve(page([{ transactionId: "txn-b" }]));
    });
    await waitFor(() => expect(result.current.page.content).toEqual([{ transactionId: "txn-b" }]));

    await act(async () => {
      first.resolve(page([{ transactionId: "txn-a" }]));
    });

    expect(result.current.page.content).toEqual([{ transactionId: "txn-b" }]);
  });

  it("clears governance queue state when disabled after data was loaded", async () => {
    listGovernanceAdvisories.mockResolvedValue({
      status: "AVAILABLE",
      count: 1,
      advisory_events: [{ event_id: "event-1" }]
    });
    const apiClient = workspaceApiClient;
    const { result, rerender } = renderHook(({ enabled }) => useGovernanceQueue({ enabled, apiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.queue.count).toBe(1));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.queue.count).toBe(0));
    expect(result.current.auditHistories).toEqual({});
    expect(result.current.error).toBeNull();
  });

  it("clears governance queue state when apiClient is removed", async () => {
    listGovernanceAdvisories.mockResolvedValue({
      status: "AVAILABLE",
      count: 1,
      advisory_events: [{ event_id: "event-1" }]
    });
    const { result, rerender } = renderHook(({ apiClient }) => useGovernanceQueue({ enabled: true, apiClient }), {
      initialProps: { apiClient: workspaceApiClient }
    });
    await waitFor(() => expect(result.current.queue.count).toBe(1));

    rerender({ apiClient: null });

    await waitFor(() => expect(result.current.queue.count).toBe(0));
    expect(result.current.auditHistories).toEqual({});
  });

  it("clears governance analytics state when disabled after data was loaded", async () => {
    const apiClient = workspaceApiClient;
    const { result, rerender } = renderHook(({ enabled }) => useGovernanceAnalytics({ enabled, apiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.analytics.totals.advisories).toBe(1));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.analytics.totals.advisories).toBe(0));
    expect(result.current.error).toBeNull();
  });

  it("aborts governance queue requests on unmount", async () => {
    listGovernanceAdvisories.mockReturnValue(new Promise(() => {}));
    const { unmount } = renderHook(() => useGovernanceQueue({ enabled: true, apiClient: workspaceApiClient }));
    await waitFor(() => expect(listGovernanceAdvisories).toHaveBeenCalledTimes(1));
    const signal = listGovernanceAdvisories.mock.calls[0][1].signal;

    expect(signal).toBeInstanceOf(AbortSignal);
    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("aborts previous governance queue request on refresh", async () => {
    listGovernanceAdvisories.mockReturnValue(new Promise(() => {}));
    const { result } = renderHook(() => useGovernanceQueue({ enabled: true, apiClient: workspaceApiClient }));
    await waitFor(() => expect(listGovernanceAdvisories).toHaveBeenCalledTimes(1));
    const firstSignal = listGovernanceAdvisories.mock.calls[0][1].signal;

    act(() => {
      result.current.refresh();
    });
    await waitFor(() => expect(listGovernanceAdvisories).toHaveBeenCalledTimes(2));

    expect(firstSignal.aborted).toBe(true);
    expect(listGovernanceAdvisories.mock.calls[1][1].signal).toBeInstanceOf(AbortSignal);
  });

  it("ignores governance queue AbortError", async () => {
    listGovernanceAdvisories.mockRejectedValue(new DOMException("aborted", "AbortError"));
    const { result } = renderHook(() => useGovernanceQueue({ enabled: true, apiClient: workspaceApiClient }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBeNull();
    expect(result.current.queue.count).toBe(0);
  });

  it("prevents stale governance queue responses from overwriting latest state", async () => {
    const first = deferred();
    const second = deferred();
    listGovernanceAdvisories
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useGovernanceQueue({ enabled: true, apiClient: workspaceApiClient }));
    await waitFor(() => expect(listGovernanceAdvisories).toHaveBeenCalledTimes(1));

    act(() => result.current.setRequest((current) => ({ ...current, severity: "HIGH" })));
    await waitFor(() => expect(listGovernanceAdvisories).toHaveBeenCalledTimes(2));
    await act(async () => {
      second.resolve({ status: "AVAILABLE", count: 2, advisory_events: [{ event_id: "event-2" }] });
    });
    await waitFor(() => expect(result.current.queue.count).toBe(2));
    await act(async () => {
      first.resolve({ status: "AVAILABLE", count: 1, advisory_events: [{ event_id: "event-1" }] });
    });

    expect(result.current.queue.count).toBe(2);
    expect(result.current.queue.advisory_events).toEqual([{ event_id: "event-2" }]);
  });

  it("passes governance queue abort signal to advisory audit history requests", async () => {
    listGovernanceAdvisories.mockResolvedValue({
      status: "AVAILABLE",
      count: 1,
      advisory_events: [{ event_id: "event-1" }]
    });
    getGovernanceAdvisoryAudit.mockResolvedValue({ status: "AVAILABLE", audit_events: [] });

    renderHook(() => useGovernanceQueue({ enabled: true, apiClient: workspaceApiClient }));

    await waitFor(() => expect(getGovernanceAdvisoryAudit).toHaveBeenCalledWith(
      "event-1",
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    ));
  });

  it("aborts governance analytics requests on unmount", async () => {
    getGovernanceAdvisoryAnalytics.mockReturnValue(new Promise(() => {}));
    const { unmount } = renderHook(() => useGovernanceAnalytics({ enabled: true, apiClient: workspaceApiClient }));
    await waitFor(() => expect(getGovernanceAdvisoryAnalytics).toHaveBeenCalledTimes(1));
    const signal = getGovernanceAdvisoryAnalytics.mock.calls[0][1].signal;

    expect(signal).toBeInstanceOf(AbortSignal);
    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("aborts governance analytics on disable", async () => {
    getGovernanceAdvisoryAnalytics.mockReturnValue(new Promise(() => {}));
    const apiClient = workspaceApiClient;
    const { rerender } = renderHook(({ enabled }) => useGovernanceAnalytics({ enabled, apiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(getGovernanceAdvisoryAnalytics).toHaveBeenCalledTimes(1));
    const signal = getGovernanceAdvisoryAnalytics.mock.calls[0][1].signal;

    rerender({ enabled: false });

    expect(signal.aborted).toBe(true);
  });

  it("ignores governance analytics AbortError", async () => {
    getGovernanceAdvisoryAnalytics.mockRejectedValue(new DOMException("aborted", "AbortError"));
    const { result } = renderHook(() => useGovernanceAnalytics({ enabled: true, apiClient: workspaceApiClient }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBeNull();
    expect(result.current.analytics.totals.advisories).toBe(0);
  });

  it("prevents stale governance analytics responses from overwriting latest state", async () => {
    const first = deferred();
    const second = deferred();
    getGovernanceAdvisoryAnalytics
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useGovernanceAnalytics({ enabled: true, apiClient: workspaceApiClient }));
    await waitFor(() => expect(getGovernanceAdvisoryAnalytics).toHaveBeenCalledTimes(1));

    act(() => result.current.setWindowDays(14));
    await waitFor(() => expect(getGovernanceAdvisoryAnalytics).toHaveBeenCalledTimes(2));
    await act(async () => {
      second.resolve(analyticsWithTotal(2));
    });
    await waitFor(() => expect(result.current.analytics.totals.advisories).toBe(2));
    await act(async () => {
      first.resolve(analyticsWithTotal(1));
    });

    expect(result.current.analytics.totals.advisories).toBe(2);
  });
});

function page(content, overrides = {}) {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    page: 0,
    size: 25,
    ...overrides
  };
}

function analyticsWithTotal(advisories) {
  return {
    status: "AVAILABLE",
    window: { days: 7 },
    totals: { advisories, reviewed: advisories, open: 0 },
    decision_distribution: {},
    lifecycle_distribution: {},
    review_timeliness: { status: "LOW_CONFIDENCE" }
  };
}

const workspaceApiClient = {
  getGovernanceAdvisoryAnalytics,
  getGovernanceAdvisoryAudit,
  listAlerts,
  listGovernanceAdvisories,
  listScoredTransactions
};

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
