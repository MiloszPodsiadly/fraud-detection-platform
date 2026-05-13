import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { listFraudCaseWorkQueue } from "../api/alertsApi.js";
import { useFraudCaseWorkQueue } from "./useFraudCaseWorkQueue.js";

vi.mock("../api/alertsApi.js", () => ({
  listFraudCaseWorkQueue: vi.fn(),
  isAbortError: (error) => error?.name === "AbortError"
}));

describe("useFraudCaseWorkQueue", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listFraudCaseWorkQueue.mockResolvedValue(slice([{ caseId: "case-1" }], { nextCursor: "cursor-2", hasNext: true }));
  });

  it("loads the first slice when enabled", async () => {
    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, session: { userId: "u1" }, authProvider: { kind: "demo" }, apiClient: workQueueApiClient }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(listFraudCaseWorkQueue).toHaveBeenCalledWith(
      expect.objectContaining({ cursor: null, size: 20 }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    );
    expect(result.current.queue.content).toEqual([{ caseId: "case-1" }]);
    expect(result.current.lastRefreshedAt).toBeTruthy();
  });

  it("load more appends the next slice", async () => {
    listFraudCaseWorkQueue
      .mockResolvedValueOnce(slice([{ caseId: "case-1" }], { nextCursor: "cursor-2", hasNext: true }))
      .mockResolvedValueOnce(slice([{ caseId: "case-2" }], { nextCursor: null, hasNext: false }));
    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));
    await waitFor(() => expect(result.current.queue.content).toHaveLength(1));

    act(() => result.current.loadMore());

    await waitFor(() => expect(result.current.queue.content).toHaveLength(2));
    expect(result.current.queue.content.map((item) => item.caseId)).toEqual(["case-1", "case-2"]);
  });

  it("apply filters resets cursor and content", async () => {
    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    listFraudCaseWorkQueue.mockResolvedValueOnce(slice([{ caseId: "case-filtered" }]));

    act(() => result.current.updateDraftFilter("status", "OPEN"));
    act(() => result.current.applyFilters());

    await waitFor(() => expect(result.current.queue.content).toEqual([{ caseId: "case-filtered" }]));
    expect(listFraudCaseWorkQueue).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: "OPEN", cursor: null }),
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    );
  });

  it("reset filters clears filters and cursor", async () => {
    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    listFraudCaseWorkQueue.mockResolvedValueOnce(slice([]));

    act(() => result.current.updateDraftFilter("riskLevel", "CRITICAL"));
    act(() => result.current.resetFilters());

    await waitFor(() => expect(result.current.committedFilters.riskLevel).toBe("ALL"));
    expect(result.current.committedFilters.cursor).toBeNull();
  });

  it("handles invalid cursor by clearing cursor and exposing the error", async () => {
    const invalidCursor = { status: 400, error: "INVALID_CURSOR" };
    listFraudCaseWorkQueue
      .mockResolvedValueOnce(slice([{ caseId: "case-1" }], { nextCursor: "bad-cursor", hasNext: true }))
      .mockRejectedValueOnce(invalidCursor);
    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));
    await waitFor(() => expect(result.current.queue.nextCursor).toBe("bad-cursor"));

    act(() => result.current.loadMore());

    await waitFor(() => expect(result.current.error).toBe(invalidCursor));
    expect(result.current.committedFilters.cursor).toBeNull();
  });

  it("ignores stale responses", async () => {
    let resolveFirst;
    const first = new Promise((resolve) => {
      resolveFirst = resolve;
    });
    listFraudCaseWorkQueue
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce(slice([{ caseId: "new" }]));
    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));

    act(() => result.current.refreshFirstSlice());
    resolveFirst(slice([{ caseId: "old" }]));

    await waitFor(() => expect(result.current.queue.content).toEqual([{ caseId: "new" }]));
  });

  it("aborts the previous request when filters are applied", async () => {
    const first = deferred();
    listFraudCaseWorkQueue
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(slice([{ caseId: "filtered" }]));
    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));
    await waitFor(() => expect(listFraudCaseWorkQueue).toHaveBeenCalledTimes(1));
    const firstSignal = listFraudCaseWorkQueue.mock.calls[0][1].signal;

    act(() => result.current.updateDraftFilter("status", "OPEN"));
    act(() => result.current.applyFilters());

    expect(firstSignal.aborted).toBe(true);
    await waitFor(() => expect(result.current.queue.content).toEqual([{ caseId: "filtered" }]));
  });

  it("aborts in-flight request on unmount and ignores AbortError", async () => {
    const pending = deferred();
    listFraudCaseWorkQueue.mockReturnValue(pending.promise);

    const { result, unmount } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));
    await waitFor(() => expect(listFraudCaseWorkQueue).toHaveBeenCalledTimes(1));
    const signal = listFraudCaseWorkQueue.mock.calls[0][1].signal;

    unmount();

    expect(signal.aborted).toBe(true);
    expect(result.current.error).toBeNull();
  });

  it("ignores AbortError without exposing a network error", async () => {
    listFraudCaseWorkQueue.mockRejectedValue(new DOMException("aborted", "AbortError"));

    const { result } = renderHook(() => useFraudCaseWorkQueue({ enabled: true, apiClient: workQueueApiClient }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBeNull();
  });

  it("clears loaded queue state when disabled", async () => {
    const { result, rerender } = renderHook(
      ({ enabled }) => useFraudCaseWorkQueue({ enabled, session: { userId: "u1" }, authProvider: { kind: "bff" }, apiClient: workQueueApiClient }),
      { initialProps: { enabled: true } }
    );
    await waitFor(() => expect(result.current.queue.content).toEqual([{ caseId: "case-1" }]));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.queue.content).toEqual([]));
    expect(result.current.error).toBeNull();
    expect(result.current.warning).toBeNull();
    expect(result.current.lastRefreshedAt).toBeNull();
  });
});

function slice(content, overrides = {}) {
  return {
    content,
    size: 20,
    hasNext: false,
    nextCursor: null,
    sort: "createdAt,desc",
    ...overrides
  };
}

const workQueueApiClient = {
  listFraudCaseWorkQueue
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

