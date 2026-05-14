import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";

const listAlerts = vi.fn();
const listScoredTransactions = vi.fn();

describe("useWorkspaceCounters", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listAlerts.mockResolvedValue(page(2));
    listScoredTransactions.mockResolvedValue(page(4));
  });

  it("loads available workspace counters through the explicit client", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({ enabled: true, apiClient: client }));

    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, transactions: 4 }));
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
    expect(result.current.counters).toEqual({ alerts: null, transactions: 4 });
    expect(result.current.errorByCounter.alerts).toBe("alerts unavailable");
    expect(result.current.stale).toBe(false);
  });

  it("marks retained values stale when a refresh fails after a prior success", async () => {
    const client = apiClient();
    const { result } = renderHook(() => useWorkspaceCounters({ enabled: true, apiClient: client }));
    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, transactions: 4 }));

    listAlerts.mockRejectedValue(new Error("alerts unavailable"));
    listScoredTransactions.mockResolvedValue(page(5));
    await result.current.refresh();

    await waitFor(() => expect(result.current.degraded).toBe(true));
    expect(result.current.counters).toEqual({ alerts: 2, transactions: 5 });
    expect(result.current.stale).toBe(true);
  });

  it("clears counters when disabled", async () => {
    const client = apiClient();
    const { result, rerender } = renderHook(({ enabled }) => useWorkspaceCounters({
      enabled,
      apiClient: client
    }), { initialProps: { enabled: true } });
    await waitFor(() => expect(result.current.counters).toEqual({ alerts: 2, transactions: 4 }));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.counters).toEqual({ alerts: null, transactions: null }));
    expect(result.current.degraded).toBe(false);
  });
});

function apiClient() {
  return {
    listAlerts,
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
