import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  getGovernanceAdvisoryAnalytics,
  getGovernanceAdvisoryAudit,
  listAlerts,
  listGovernanceAdvisories,
  listScoredTransactions
} from "../api/alertsApi.js";
import { useAlertQueue } from "./useAlertQueue.js";
import { useGovernanceAnalytics } from "./useGovernanceAnalytics.js";
import { useGovernanceQueue } from "./useGovernanceQueue.js";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";

vi.mock("../api/alertsApi.js", () => ({
  getGovernanceAdvisoryAnalytics: vi.fn(),
  getGovernanceAdvisoryAudit: vi.fn(),
  isAbortError: (error) => error?.name === "AbortError",
  listAlerts: vi.fn(),
  listGovernanceAdvisories: vi.fn(),
  listScoredTransactions: vi.fn(),
  setApiSession: vi.fn()
}));

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
    renderHook(() => useAlertQueue({ enabled: false }));

    expect(listAlerts).not.toHaveBeenCalled();
  });

  it("loads alert queue when enabled", async () => {
    listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }], { totalElements: 1 }));

    const { result } = renderHook(() => useAlertQueue({ enabled: true }));

    await waitFor(() => expect(result.current.page.content).toEqual([{ alertId: "alert-1" }]));
    expect(result.current.page.totalElements).toBe(1);
  });

  it("aborts scored transaction request on unmount", async () => {
    listScoredTransactions.mockReturnValue(new Promise(() => {}));
    const { unmount } = renderHook(() => useScoredTransactionStream({ enabled: true }));
    await waitFor(() => expect(listScoredTransactions).toHaveBeenCalledTimes(1));
    const signal = listScoredTransactions.mock.calls[0][1].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("ignores scored transaction AbortError", async () => {
    listScoredTransactions.mockRejectedValue(new DOMException("aborted", "AbortError"));
    const { result } = renderHook(() => useScoredTransactionStream({ enabled: true }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBeNull();
  });

  it("clears alert queue state when disabled after data was loaded", async () => {
    listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }], { totalElements: 1 }));
    const { result, rerender } = renderHook(({ enabled }) => useAlertQueue({ enabled }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.page.content).toEqual([{ alertId: "alert-1" }]));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.page.content).toEqual([]));
    expect(result.current.error).toBeNull();
  });

  it("clears scored transaction state when disabled after data was loaded", async () => {
    listScoredTransactions.mockResolvedValue(page([{ transactionId: "txn-1" }], { totalElements: 1 }));
    const { result, rerender } = renderHook(({ enabled }) => useScoredTransactionStream({ enabled }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.page.content).toEqual([{ transactionId: "txn-1" }]));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.page.content).toEqual([]));
    expect(result.current.error).toBeNull();
  });

  it("clears governance queue state when disabled after data was loaded", async () => {
    listGovernanceAdvisories.mockResolvedValue({
      status: "AVAILABLE",
      count: 1,
      advisory_events: [{ event_id: "event-1" }]
    });
    const { result, rerender } = renderHook(({ enabled }) => useGovernanceQueue({ enabled }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.queue.count).toBe(1));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.queue.count).toBe(0));
    expect(result.current.auditHistories).toEqual({});
    expect(result.current.error).toBeNull();
  });

  it("clears governance analytics state when disabled after data was loaded", async () => {
    const { result, rerender } = renderHook(({ enabled }) => useGovernanceAnalytics({ enabled }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.analytics.totals.advisories).toBe(1));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.analytics.totals.advisories).toBe(0));
    expect(result.current.error).toBeNull();
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
