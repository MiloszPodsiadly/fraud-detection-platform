import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAlertQueue } from "./useAlertQueue.js";
import { useGovernanceAnalytics } from "./useGovernanceAnalytics.js";
import { useGovernanceQueue } from "./useGovernanceQueue.js";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";
import { useSuspiciousTransactionReadView } from "./useSuspiciousTransactionReadView.js";

const getGovernanceAdvisoryAnalytics = vi.fn();
const getGovernanceAdvisoryAudit = vi.fn();
const listAlerts = vi.fn();
const listGovernanceAdvisories = vi.fn();
const listScoredTransactions = vi.fn();
const listSuspiciousTransactions = vi.fn();
const getSuspiciousTransaction = vi.fn();

describe("workspace data hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listAlerts.mockResolvedValue(page([], { size: 10 }));
    listScoredTransactions.mockResolvedValue(page([]));
    listSuspiciousTransactions.mockResolvedValue(suspiciousSlice([]));
    getSuspiciousTransaction.mockResolvedValue(suspiciousTransaction({ suspiciousTransactionId: "suspicious-1" }));
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
    listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }], { totalElements: 1, size: 10 }));

    const { result } = renderHook(() => useAlertQueue({ enabled: true, apiClient: workspaceApiClient }));

    await waitFor(() => expect(result.current.page.content).toEqual([{ alertId: "alert-1" }]));
    expect(result.current.page.totalElements).toBe(1);
  });

  it("does not let a saved alert refresh cross an owner transition", async () => {
    const ownerAClient = { listAlerts: vi.fn().mockResolvedValue(page([{ alertId: "alert-a" }], { size: 10 })) };
    const ownerBClient = { listAlerts: vi.fn().mockResolvedValue(page([{ alertId: "alert-b" }], { size: 10 })) };
    const ownerA = {
      enabled: true,
      apiClient: ownerAClient,
      session: { userId: "same-analyst", issuer: "issuer-a", subject: "subject-a", authorities: ["ALERT_READ"] },
      authProvider: { kind: "oidc", issuer: "issuer-a" }
    };
    const ownerB = {
      ...ownerA,
      apiClient: ownerBClient,
      session: { userId: "same-analyst", issuer: "issuer-b", subject: "subject-b", authorities: [] },
      authProvider: { kind: "oidc", issuer: "issuer-b" }
    };
    const { result, rerender } = renderHook((props) => useAlertQueue(props), { initialProps: ownerA });
    await waitFor(() => expect(result.current.page.content[0]?.alertId).toBe("alert-a"));
    const savedRefreshA = result.current.refresh;

    rerender(ownerB);
    await waitFor(() => expect(result.current.page.content[0]?.alertId).toBe("alert-b"));
    const savedRefreshB = result.current.refresh;

    let staleBResult;
    await act(async () => { staleBResult = await savedRefreshA(); });
    expect(staleBResult).toBeNull();
    expect(ownerBClient.listAlerts).toHaveBeenCalledTimes(1);

    let freshBResult;
    await act(async () => { freshBResult = await savedRefreshB(); });
    expect(freshBResult.content[0].alertId).toBe("alert-b");
    expect(ownerBClient.listAlerts).toHaveBeenCalledTimes(2);

    rerender(ownerA);
    await waitFor(() => expect(result.current.page.content[0]?.alertId).toBe("alert-a"));
    const freshRefreshA = result.current.refresh;
    let staleAResult;
    await act(async () => { staleAResult = await savedRefreshA(); });
    expect(staleAResult).toBeNull();
    expect(ownerAClient.listAlerts).toHaveBeenCalledTimes(2);

    let currentAResult;
    await act(async () => { currentAResult = await freshRefreshA(); });
    expect(currentAResult.content[0].alertId).toBe("alert-a");
    expect(ownerAClient.listAlerts).toHaveBeenCalledTimes(3);
  });

  it.each([
    ["null response", null],
    ["missing page envelope", {}],
    ["null content", { content: null, totalElements: 0, totalPages: 0, page: 0, size: 10 }],
    ["non-array content", { content: {}, totalElements: 0, totalPages: 0, page: 0, size: 10 }],
    ["null alert row", { content: [null], totalElements: 1, totalPages: 1, page: 0, size: 10 }],
    ["negative total", { content: [], totalElements: -1, totalPages: 0, page: 0, size: 10 }],
    ["fractional page index", { content: [], totalElements: 0, totalPages: 0, page: 0.5, size: 10 }],
    ["unexpected response page", { content: [], totalElements: 0, totalPages: 0, page: 1, size: 10 }],
    ["unexpected response size", { content: [], totalElements: 0, totalPages: 0, page: 0, size: 25 }],
    ["page total mismatch", { content: [], totalElements: 11, totalPages: 1, page: 0, size: 10 }],
    ["content exceeds requested size", { content: Array.from({ length: 11 }, (_, index) => ({ alertId: `alert-${index}` })), totalElements: 11, totalPages: 2, page: 0, size: 10 }]
  ])("rejects malformed alert page before publication: %s", async (_caseName, response) => {
    const apiClient = { listAlerts: vi.fn().mockResolvedValue(response) };
    const { result } = renderHook(() => useAlertQueue({ enabled: true, apiClient }));

    await waitFor(() => expect(result.current.error).not.toBeNull());

    expect(result.current.hasLoaded).toBe(false);
    expect(result.current.page).toMatchObject({
      content: [],
      totalElements: 0,
      totalPages: 0,
      page: 0,
      size: 10
    });
    expect(result.current.error.message).toBe("Alerts are temporarily unavailable. Please retry.");
  });

  it("does not fetch workspace data without an explicit apiClient", async () => {
    renderHook(() => useAlertQueue({ enabled: true, apiClient: null }));
    renderHook(() => useScoredTransactionStream({ enabled: true, apiClient: null }));
    renderHook(() => useSuspiciousTransactionReadView({ enabled: true, apiClient: null }));
    renderHook(() => useGovernanceQueue({ enabled: true, apiClient: null }));
    renderHook(() => useGovernanceAnalytics({ enabled: true, apiClient: null }));

    expect(listAlerts).not.toHaveBeenCalled();
    expect(listScoredTransactions).not.toHaveBeenCalled();
    expect(listSuspiciousTransactions).not.toHaveBeenCalled();
    expect(getSuspiciousTransaction).not.toHaveBeenCalled();
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
    listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }], { totalElements: 1, size: 10 }));
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

  it("loads suspicious transaction cursor slices and appends the next slice", async () => {
    listSuspiciousTransactions
      .mockResolvedValueOnce(suspiciousSlice([suspiciousTransaction({ suspiciousTransactionId: "suspicious-1" })], {
        hasNext: true,
        nextCursor: "cursor-2"
      }))
      .mockResolvedValueOnce(suspiciousSlice([suspiciousTransaction({ suspiciousTransactionId: "suspicious-2" })]));

    const { result } = renderHook(() => useSuspiciousTransactionReadView({ enabled: true, apiClient: workspaceApiClient }));

    await waitFor(() => expect(result.current.items.map((item) => item.suspiciousTransactionId)).toEqual(["suspicious-1"]));
    await act(async () => {
      await result.current.loadNext();
    });

    expect(listSuspiciousTransactions).toHaveBeenNthCalledWith(1, { size: 20, cursor: null }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(listSuspiciousTransactions).toHaveBeenNthCalledWith(2, { size: 20, cursor: "cursor-2" }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(result.current.items.map((item) => item.suspiciousTransactionId)).toEqual(["suspicious-1", "suspicious-2"]);
  });

  it("suspiciousTransactionListDoesNotUseSummaryAsPaginationTotal", async () => {
    listSuspiciousTransactions.mockResolvedValueOnce(suspiciousSlice([
      suspiciousTransaction({ suspiciousTransactionId: "suspicious-1" })
    ], {
      hasNext: false,
      nextCursor: null
    }));
    const apiClient = {
      ...workspaceApiClient,
      getSuspiciousTransactionSummary: vi.fn().mockRejectedValue(new Error("summary unavailable"))
    };

    const { result } = renderHook(() => useSuspiciousTransactionReadView({ enabled: true, apiClient }));

    await waitFor(() => expect(result.current.items).toHaveLength(1));
    expect(apiClient.getSuspiciousTransactionSummary).not.toHaveBeenCalled();
    expect(result.current.slice).toEqual({
      content: [expect.objectContaining({ suspiciousTransactionId: "suspicious-1" })],
      size: 20,
      hasNext: false,
      nextCursor: null
    });
  });

  it("loads suspicious transaction detail only when an id is selected", async () => {
    const { result, rerender } = renderHook(
      ({ selectedSuspiciousTransactionId }) => useSuspiciousTransactionReadView({
        enabled: true,
        apiClient: workspaceApiClient,
        selectedSuspiciousTransactionId
      }),
      { initialProps: { selectedSuspiciousTransactionId: null } }
    );
    await waitFor(() => expect(listSuspiciousTransactions).toHaveBeenCalledTimes(1));
    expect(getSuspiciousTransaction).not.toHaveBeenCalled();

    rerender({ selectedSuspiciousTransactionId: "suspicious-1" });

    await waitFor(() => expect(result.current.detail?.suspiciousTransactionId).toBe("suspicious-1"));
    expect(getSuspiciousTransaction).toHaveBeenCalledWith("suspicious-1", expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it("clears suspicious transaction state when disabled after data was loaded", async () => {
    listSuspiciousTransactions.mockResolvedValue(suspiciousSlice([suspiciousTransaction({ suspiciousTransactionId: "suspicious-1" })]));
    const { result, rerender } = renderHook(({ enabled }) => useSuspiciousTransactionReadView({ enabled, apiClient: workspaceApiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.items).toEqual([]));
    expect(result.current.listError).toBeNull();
  });

  it("keeps scored transaction data isolated across apiClient session switch", async () => {
    const authProvider = { kind: "demo" };
    const first = deferred();
    const second = deferred();
    const apiClientA = { ...workspaceApiClient, listScoredTransactions: vi.fn().mockReturnValue(first.promise) };
    const apiClientB = { ...workspaceApiClient, listScoredTransactions: vi.fn().mockReturnValue(second.promise) };
    const { result, rerender } = renderHook(
      ({ session, apiClient }) => useScoredTransactionStream({ enabled: true, session, authProvider, apiClient }),
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
  listScoredTransactions,
  listSuspiciousTransactions,
  getSuspiciousTransaction
};

function suspiciousSlice(content, overrides = {}) {
  return {
    content,
    size: 20,
    hasNext: false,
    nextCursor: null,
    ...overrides
  };
}

function suspiciousTransaction(overrides = {}) {
  return {
    suspiciousTransactionId: "suspicious-1",
    transactionId: "txn-1",
    riskScore: 0.94,
    riskLevel: "CRITICAL",
    detectionSource: "SCORING",
    status: "NEW",
    detectedAt: "2026-05-16T12:00:00Z",
    reasonCodes: ["HIGH_AMOUNT_ACTIVITY"],
    evidenceStatus: "AVAILABLE",
    evidenceSnapshotItemCount: 1,
    evidenceProjectionState: "PROJECTED",
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
