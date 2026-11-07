import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";

const listAlerts = vi.fn();
const getFraudCaseWorkQueueSummary = vi.fn();
const getSuspiciousTransactionSummary = vi.fn();
const listScoredTransactions = vi.fn();
const COUNTER_TEST_API_CLIENT = {
  listAlerts,
  getFraudCaseWorkQueueSummary,
  getSuspiciousTransactionSummary,
  listScoredTransactions
};
const MISSING_COUNTER_VALUE = Symbol("missing counter value");
const WORKSPACE_COUNTER_NAMES = ["alerts", "fraudCases", "suspiciousTransactions", "transactions"];
const INVALID_COUNTER_VALUES = [
  ["null", null],
  ["missing property", MISSING_COUNTER_VALUE],
  ["negative integer", -7],
  ["fractional number", 3.14],
  ["numeric string", "42"],
  ["object", { value: 42 }],
  ["array", []],
  ["NaN", Number.NaN],
  ["infinity", Number.POSITIVE_INFINITY],
  ["unsafe integer", Number.MAX_SAFE_INTEGER + 1],
  ["large exponent", 1e30]
];
const VALID_COUNTER_VALUES = [
  ["zero", 0],
  ["positive integer", 42],
  ["largest safe integer", Number.MAX_SAFE_INTEGER]
];
const INVALID_COUNTER_CASES = WORKSPACE_COUNTER_NAMES.flatMap((counterName) => INVALID_COUNTER_VALUES.map(
  ([description, value]) => [counterName, description, value]
));
const VALID_COUNTER_CASES = WORKSPACE_COUNTER_NAMES.flatMap((counterName) => VALID_COUNTER_VALUES.map(
  ([description, value]) => [counterName, description, value]
));

describe("useWorkspaceCounters", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listAlerts.mockResolvedValue(page(2));
    getFraudCaseWorkQueueSummary.mockResolvedValue({ totalFraudCases: 7 });
    getSuspiciousTransactionSummary.mockResolvedValue({ totalSuspiciousTransactions: 98 });
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

    await waitFor(() => expect(result.current.counters).toEqual(counters({ alerts: 2, transactions: 4 })));
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

  it.each(INVALID_COUNTER_CASES)("rejects %s payloads with %s values", async (counterName, _description, value) => {
    counterReader(counterName).mockResolvedValueOnce(counterPayload(counterName, value));
    const { result } = renderHook(() => useWorkspaceCounters(counterOptions(counterName)));

    await waitFor(() => {
      expect(counterReader(counterName)).toHaveBeenCalledTimes(1);
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.counters[counterName]).toBeNull();
    expect(result.current.degraded).toBe(true);
    expect(result.current.stale).toBe(false);
    expect(result.current.errorByCounter).toEqual({ [counterName]: invalidCounterMessage(counterName) });
    expect(result.current.lastRefreshedAt).toBeNull();
  });

  it.each(VALID_COUNTER_CASES)("accepts %s payloads with valid %s values", async (counterName, _description, value) => {
    counterReader(counterName).mockResolvedValueOnce(counterPayload(counterName, value));
    const { result } = renderHook(() => useWorkspaceCounters(counterOptions(counterName)));

    await waitFor(() => expect(result.current.counters[counterName]).toBe(value));

    expect(result.current.degraded).toBe(false);
    expect(result.current.stale).toBe(false);
    expect(result.current.errorByCounter).toEqual({});
    expect(result.current.lastRefreshedAt).not.toBeNull();
  });

  it.each(WORKSPACE_COUNTER_NAMES)("rejects invalid public setter values for %s", async (counterName) => {
    counterReader(counterName).mockResolvedValueOnce(counterPayload(counterName, 42));
    const { result } = renderHook(() => useWorkspaceCounters(counterOptions(counterName)));
    await waitFor(() => expect(result.current.counters[counterName]).toBe(42));

    await act(async () => {
      for (const [, value] of INVALID_COUNTER_VALUES) {
        if (value !== MISSING_COUNTER_VALUE) result.current.setCounterValue(counterName, value);
      }
    });

    expect(result.current.counters[counterName]).toBe(42);
    expect(result.current.degraded).toBe(false);
    expect(result.current.stale).toBe(false);
    expect(result.current.errorByCounter).toEqual({});
  });

  it.each(WORKSPACE_COUNTER_NAMES)("retains only a valid stale %s value after malformed refresh data", async (counterName) => {
    const reader = counterReader(counterName);
    reader.mockResolvedValueOnce(counterPayload(counterName, 42));
    const { result } = renderHook(() => useWorkspaceCounters(counterOptions(counterName)));
    await waitFor(() => expect(result.current.counters[counterName]).toBe(42));
    const lastRefreshedAt = result.current.lastRefreshedAt;
    reader.mockResolvedValueOnce(counterPayload(counterName, { value: 42 }));

    await act(async () => { await result.current.refresh(); });

    expect(result.current.counters[counterName]).toBe(42);
    expect(result.current.degraded).toBe(true);
    expect(result.current.stale).toBe(true);
    expect(result.current.errorByCounter).toEqual({ [counterName]: invalidCounterMessage(counterName) });
    expect(result.current.lastRefreshedAt).toBe(lastRefreshedAt);
  });

  it("recovers from malformed counter data after a valid refresh", async () => {
    listAlerts.mockResolvedValueOnce(counterPayload("alerts", MISSING_COUNTER_VALUE));
    listAlerts.mockResolvedValueOnce(counterPayload("alerts", 42));
    const { result } = renderHook(() => useWorkspaceCounters(counterOptions("alerts")));

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters.alerts).toBeNull();
    expect(result.current.lastRefreshedAt).toBeNull();

    await act(async () => { await result.current.refresh(); });

    expect(result.current.counters.alerts).toBe(42);
    expect(result.current.degraded).toBe(false);
    expect(result.current.stale).toBe(false);
    expect(result.current.errorByCounter).toEqual({});
    expect(result.current.lastRefreshedAt).not.toBeNull();
  });

  it("does not revive a malformed counter value across an A-to-B-to-A owner transition", async () => {
    const malformedA = deferred();
    const validB = deferred();
    const validReturnA = deferred();
    listAlerts
      .mockReturnValueOnce(malformedA.promise)
      .mockReturnValueOnce(validB.promise)
      .mockReturnValueOnce(validReturnA.promise);
    const { result, rerender } = renderHook(({ resetKey }) => useWorkspaceCounters({
      ...counterOptions("alerts"),
      workspaceSessionResetKey: resetKey
    }), { initialProps: { resetKey: "owner-a" } });

    await waitFor(() => expect(listAlerts).toHaveBeenCalledTimes(1));
    await act(async () => malformedA.resolve(counterPayload("alerts", Number.MAX_SAFE_INTEGER + 1)));
    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters.alerts).toBeNull();
    expect(result.current.degraded).toBe(true);

    rerender({ resetKey: "owner-b" });
    await waitFor(() => expect(listAlerts).toHaveBeenCalledTimes(2));
    expect(result.current.counters.alerts).toBeNull();
    await act(async () => validB.resolve(counterPayload("alerts", 7)));
    await waitFor(() => expect(result.current.counters.alerts).toBe(7));

    rerender({ resetKey: "owner-a" });
    await waitFor(() => expect(listAlerts).toHaveBeenCalledTimes(3));
    expect(result.current.counters.alerts).toBeNull();
    await act(async () => validReturnA.resolve(counterPayload("alerts", 3)));
    await waitFor(() => expect(result.current.counters.alerts).toBe(3));
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

    await waitFor(() => expect(result.current.counters).toEqual(counters({ fraudCases: 7 })));
    expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledWith(expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it("loads the global suspicious transaction counter without mounting the cursor read view", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      includeAlerts: false,
      includeFraudCases: false,
      includeSuspiciousTransactions: true,
      includeTransactions: false,
      canReadSuspiciousTransactions: true
    }));

    await waitFor(() => expect(result.current.counters).toEqual(counters({ suspiciousTransactions: 98 })));
    expect(getSuspiciousTransactionSummary).toHaveBeenCalledWith(expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(listSuspiciousTransactionsNotAvailable()).toBe(true);
  });

  it("suspiciousTransactionWorkspaceCounterUsesSummaryEndpointOnly", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      includeAlerts: false,
      includeFraudCases: false,
      includeSuspiciousTransactions: true,
      includeTransactions: false,
      canReadSuspiciousTransactions: true
    }));

    await waitFor(() => expect(result.current.counters.suspiciousTransactions).toBe(98));
    expect(getSuspiciousTransactionSummary).toHaveBeenCalledTimes(1);
    expect(listAlerts).not.toHaveBeenCalled();
    expect(getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
    expect(listScoredTransactions).not.toHaveBeenCalled();
  });

  it("summaryCounterFailureDoesNotBreakListView", async () => {
    getSuspiciousTransactionSummary.mockRejectedValue(new Error("raw backend connection refused"));
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      includeAlerts: false,
      includeFraudCases: false,
      includeSuspiciousTransactions: true,
      includeTransactions: false,
      canReadSuspiciousTransactions: true
    }));

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters.suspiciousTransactions).toBeNull();
    expect(result.current.errorByCounter.suspiciousTransactions).toBe("Summary temporarily unavailable");
  });

  it("summaryCounterFailureDoesNotLogRawError", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const consoleWarn = vi.spyOn(console, "warn").mockImplementation(() => {});
    getSuspiciousTransactionSummary.mockRejectedValue(new Error("raw cursor token leaked"));
    const client = apiClient();

    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      includeAlerts: false,
      includeFraudCases: false,
      includeSuspiciousTransactions: true,
      includeTransactions: false,
      canReadSuspiciousTransactions: true
    }));

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(consoleError).not.toHaveBeenCalled();
    expect(consoleWarn).not.toHaveBeenCalled();
    consoleError.mockRestore();
    consoleWarn.mockRestore();
  });

  it("summaryCounterUnavailableResponseUsesSafeCounterState", async () => {
    getSuspiciousTransactionSummary.mockResolvedValue({
      totalSuspiciousTransactions: 0,
      freshness: "UNAVAILABLE",
      cachedAt: null,
      expiresAt: null
    });
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      includeAlerts: false,
      includeFraudCases: false,
      includeSuspiciousTransactions: true,
      includeTransactions: false,
      canReadSuspiciousTransactions: true
    }));

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters.suspiciousTransactions).toBeNull();
    expect(result.current.errorByCounter.suspiciousTransactions).toBe("Summary temporarily unavailable");
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
    expect(result.current.counters).toEqual(counters({ transactions: 4 }));
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
    await waitFor(() => expect(result.current.counters).toEqual(counters({ alerts: 2, transactions: 4 })));

    listAlerts.mockRejectedValue(new Error("alerts unavailable"));
    listScoredTransactions.mockResolvedValue(page(5));
    await act(async () => {
      await result.current.refresh();
    });

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters).toEqual(counters({ alerts: 2, transactions: 5 }));
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
    await waitFor(() => expect(result.current.counters).toEqual(counters({ alerts: 2, transactions: 4 })));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.counters).toEqual(counters()));
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
    expect(result.current.counters).toEqual(counters());
  });

  it("clears a counter when its capability becomes false", async () => {
    const client = apiClient();
    const { result, rerender } = renderHook(({ canReadAlerts }) => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      canReadAlerts,
      canReadTransactions: true
    }), { initialProps: { canReadAlerts: true } });
    await waitFor(() => expect(result.current.counters).toEqual(counters({ alerts: 2, transactions: 4 })));

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
    await waitFor(() => expect(result.current.counters).toEqual(counters({ alerts: 10, transactions: 20 })));

    await act(async () => {
      firstAlerts.resolve(page(1));
      firstTransactions.resolve(page(2));
      await Promise.all([firstAlerts.promise, firstTransactions.promise]);
    });

    expect(result.current.counters).toEqual(counters({ alerts: 10, transactions: 20 }));
  });

  it("fences saved callbacks and late results through a key-only A-B-A owner transition", async () => {
    const oldARefresh = deferred();
    const bRequest = deferred();
    const newARequest = deferred();
    const client = {
      listAlerts: vi.fn()
        .mockResolvedValueOnce(page(3))
        .mockReturnValueOnce(oldARefresh.promise)
        .mockReturnValueOnce(bRequest.promise)
        .mockReturnValueOnce(newARequest.promise)
    };
    const options = {
      enabled: true,
      apiClient: client,
      includeFraudCases: false,
      includeSuspiciousTransactions: false,
      includeTransactions: false,
      canReadAlerts: true
    };
    const { result, rerender } = renderHook(({ resetKey }) => useWorkspaceCounters({
      ...options,
      workspaceSessionResetKey: resetKey
    }), { initialProps: { resetKey: "owner-a" } });

    await waitFor(() => expect(result.current.counters.alerts).toBe(3));
    const savedARefresh = result.current.refresh;
    const savedASetter = result.current.setCounterValue;
    rerender({ resetKey: "owner-a" });
    expect(client.listAlerts).toHaveBeenCalledTimes(1);

    await act(async () => { void result.current.refresh(); });
    await waitFor(() => expect(client.listAlerts).toHaveBeenCalledTimes(2));
    const oldASignal = client.listAlerts.mock.calls[1][1].signal;

    rerender({ resetKey: "owner-b" });
    expect(result.current.counters).toEqual(counters());
    expect(result.current.degraded).toBe(false);
    expect(result.current.stale).toBe(false);
    expect(result.current.errorByCounter).toEqual({});
    expect(result.current.lastRefreshedAt).toBeNull();
    expect(oldASignal.aborted).toBe(true);
    await waitFor(() => expect(client.listAlerts).toHaveBeenCalledTimes(3));
    const savedBRefresh = result.current.refresh;
    const savedBSetter = result.current.setCounterValue;

    savedASetter("alerts", 9991);
    await act(async () => { await savedARefresh(); });
    expect(client.listAlerts).toHaveBeenCalledTimes(3);
    expect(result.current.counters.alerts).toBeNull();

    rerender({ resetKey: "owner-a" });
    await waitFor(() => expect(client.listAlerts).toHaveBeenCalledTimes(4));
    const newASignal = client.listAlerts.mock.calls[3][1].signal;
    expect(newASignal.aborted).toBe(false);
    savedBSetter("alerts", 9992);
    await act(async () => { await savedBRefresh(); });
    expect(client.listAlerts).toHaveBeenCalledTimes(4);

    await act(async () => {
      oldARefresh.resolve(page(1));
      bRequest.resolve(page(2));
      await Promise.all([oldARefresh.promise, bRequest.promise]);
    });
    expect(result.current.counters.alerts).toBeNull();

    await act(async () => {
      newARequest.resolve(page(7));
      await newARequest.promise;
    });
    await waitFor(() => expect(result.current.counters.alerts).toBe(7));
  });

  it("treats an explicit API client replacement as an owner change with a stable reset key", async () => {
    const next = deferred();
    const firstClient = { listAlerts: vi.fn().mockResolvedValue(page(4)) };
    const secondClient = { listAlerts: vi.fn().mockReturnValue(next.promise) };
    const { result, rerender } = renderHook(({ client }) => useWorkspaceCounters({
      enabled: true,
      apiClient: client,
      workspaceSessionResetKey: "stable-key",
      includeFraudCases: false,
      includeSuspiciousTransactions: false,
      includeTransactions: false,
      canReadAlerts: true
    }), { initialProps: { client: firstClient } });

    await waitFor(() => expect(result.current.counters.alerts).toBe(4));
    const savedRefresh = result.current.refresh;
    const savedSetter = result.current.setCounterValue;
    rerender({ client: secondClient });

    expect(result.current.counters).toEqual(counters());
    expect(result.current.lastRefreshedAt).toBeNull();
    await waitFor(() => expect(secondClient.listAlerts).toHaveBeenCalledTimes(1));
    savedSetter("alerts", 9993);
    await act(async () => { await savedRefresh(); });
    expect(firstClient.listAlerts).toHaveBeenCalledTimes(1);
    expect(result.current.counters.alerts).toBeNull();

    await act(async () => {
      next.resolve(page(8));
      await next.promise;
    });
    await waitFor(() => expect(result.current.counters.alerts).toBe(8));
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
    await waitFor(() => expect(result.current.counters).toEqual(counters({ alerts: 2, transactions: 4 })));
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

function counterOptions(counterName) {
  return {
    enabled: true,
    apiClient: COUNTER_TEST_API_CLIENT,
    includeAlerts: counterName === "alerts",
    includeFraudCases: counterName === "fraudCases",
    includeSuspiciousTransactions: counterName === "suspiciousTransactions",
    includeTransactions: counterName === "transactions",
    canReadAlerts: counterName === "alerts",
    canReadFraudCases: counterName === "fraudCases",
    canReadSuspiciousTransactions: counterName === "suspiciousTransactions",
    canReadTransactions: counterName === "transactions"
  };
}

function counterReader(counterName) {
  switch (counterName) {
    case "alerts": return listAlerts;
    case "fraudCases": return getFraudCaseWorkQueueSummary;
    case "suspiciousTransactions": return getSuspiciousTransactionSummary;
    case "transactions": return listScoredTransactions;
    default: throw new Error(`Unknown counter: ${counterName}`);
  }
}

function counterPayload(counterName, value) {
  if (value === MISSING_COUNTER_VALUE) return {};
  switch (counterName) {
    case "alerts":
    case "transactions": return page(value);
    case "fraudCases": return { totalFraudCases: value };
    case "suspiciousTransactions": return { totalSuspiciousTransactions: value };
    default: throw new Error(`Unknown counter: ${counterName}`);
  }
}

function invalidCounterMessage(counterName) {
  return counterName === "suspiciousTransactions"
    ? "Summary temporarily unavailable"
    : "Counter response is invalid";
}

function apiClient() {
  return {
    listAlerts,
    getFraudCaseWorkQueueSummary,
    getSuspiciousTransactionSummary,
    listScoredTransactions
  };
}

function counters(overrides = {}) {
  return {
    alerts: null,
    fraudCases: null,
    suspiciousTransactions: null,
    transactions: null,
    ...overrides
  };
}

function listSuspiciousTransactionsNotAvailable() {
  return !Object.hasOwn(apiClient(), "listSuspiciousTransactions");
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
