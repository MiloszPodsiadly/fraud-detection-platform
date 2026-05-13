import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { listAlerts, listScoredTransactions } from "../api/alertsApi.js";
import { useAlertQueue } from "./useAlertQueue.js";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";

vi.mock("../api/alertsApi.js", () => ({
  isAbortError: (error) => error?.name === "AbortError",
  listAlerts: vi.fn(),
  listScoredTransactions: vi.fn(),
  setApiSession: vi.fn()
}));

describe("workspace data hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listAlerts.mockResolvedValue(page([]));
    listScoredTransactions.mockResolvedValue(page([]));
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
