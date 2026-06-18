import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { usePromotionReviewReadinessReport } from "./usePromotionReviewReadinessReport.js";

describe("usePromotionReviewReadinessReport", () => {
  it("loads report when enabled", async () => {
    const apiClient = client();

    const { result } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));

    await waitFor(() => expect(result.current.report?.reportType).toBe("PROMOTION_REVIEW_READINESS_REPORT_V1"));
    expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledWith(expect.objectContaining({
      signal: expect.any(AbortSignal)
    }));
  });

  it("does not request when disabled", () => {
    const apiClient = client();

    renderHook(() => usePromotionReviewReadinessReport({ enabled: false, apiClient }));

    expect(apiClient.getCurrentPromotionReviewReadinessReport).not.toHaveBeenCalled();
  });

  it("clears state when disabled", async () => {
    const apiClient = client();
    const { result, rerender } = renderHook(({ enabled }) => usePromotionReviewReadinessReport({ enabled, apiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.report).toMatchObject({ readinessStatus: "REVIEWABLE" }));

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.report).toBeNull());
    expect(result.current.error).toBeNull();
  });

  it("does not request without apiClient", () => {
    renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient: null }));

    expect(true).toBe(true);
  });

  it("aborts previous request", async () => {
    const first = deferred();
    const apiClient = client();
    apiClient.getCurrentPromotionReviewReadinessReport
      .mockReturnValueOnce(first.promise)
      .mockResolvedValue(report());
    const { result } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));
    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(1));
    const firstSignal = apiClient.getCurrentPromotionReviewReadinessReport.mock.calls[0][0].signal;

    act(() => {
      result.current.refresh();
    });
    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(2));

    expect(firstSignal.aborted).toBe(true);
    await waitFor(() => expect(result.current.report?.readinessStatus).toBe("REVIEWABLE"));
    await act(async () => {
      first.resolve(report({ readinessStatus: "INSUFFICIENT_DATA" }));
    });
  });

  it("ignores stale responses", async () => {
    const first = deferred();
    const second = deferred();
    const apiClient = client();
    apiClient.getCurrentPromotionReviewReadinessReport
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));
    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(1));

    act(() => {
      result.current.refresh();
    });
    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(2));
    await act(async () => {
      second.resolve(report({ readinessStatus: "NOT_REVIEWABLE" }));
    });
    await waitFor(() => expect(result.current.report?.readinessStatus).toBe("NOT_REVIEWABLE"));
    await act(async () => {
      first.resolve(report({ readinessStatus: "REVIEWABLE" }));
    });

    expect(result.current.report?.readinessStatus).toBe("NOT_REVIEWABLE");
  });

  it("aborts on unmount", async () => {
    const apiClient = client();
    apiClient.getCurrentPromotionReviewReadinessReport.mockReturnValue(new Promise(() => {}));
    const { unmount } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));
    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(1));
    const signal = apiClient.getCurrentPromotionReviewReadinessReport.mock.calls[0][0].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("refresh calls the read-only endpoint again", async () => {
    const apiClient = client();
    const { result } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));
    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(1));

    await act(async () => {
      await result.current.refresh();
    });

    expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(2);
  });

  it("reloads on session auth identity change", async () => {
    const apiClient = client();
    const { rerender } = renderHook(
      ({ session }) => usePromotionReviewReadinessReport({
        enabled: true,
        apiClient,
        authProvider: { kind: "demo" },
        session
      }),
      { initialProps: { session: { userId: "analyst-1", authorities: ["promotion-readiness:read"] } } }
    );
    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(1));

    rerender({ session: { userId: "analyst-2", authorities: ["promotion-readiness:read"] } });

    await waitFor(() => expect(apiClient.getCurrentPromotionReviewReadinessReport).toHaveBeenCalledTimes(2));
  });

  it.each([401, 403, 404, 503])("handles %s", async (status) => {
    const apiClient = client();
    apiClient.getCurrentPromotionReviewReadinessReport.mockRejectedValue({ status });
    const { result } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));

    await waitFor(() => expect(result.current.error).toMatchObject({ status }));
    expect(result.current.report).toBeNull();
  });

  it("handles network error", async () => {
    const apiClient = client();
    apiClient.getCurrentPromotionReviewReadinessReport.mockRejectedValue(new Error("raw endpoint token stacktrace"));
    const { result } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));

    await waitFor(() => expect(result.current.error).toBeInstanceOf(Error));
    expect(result.current.report).toBeNull();
  });

  it("handles invalid response", async () => {
    const apiClient = client();
    apiClient.getCurrentPromotionReviewReadinessReport.mockResolvedValue({ state: "invalid-response" });
    const { result } = renderHook(() => usePromotionReviewReadinessReport({ enabled: true, apiClient }));

    await waitFor(() => expect(result.current.error).toEqual({ state: "invalid-response" }));
    expect(result.current.report).toBeNull();
  });
});

function client() {
  return {
    getCurrentPromotionReviewReadinessReport: vi.fn().mockResolvedValue(report())
  };
}

function report(overrides = {}) {
  return {
    reportType: "PROMOTION_REVIEW_READINESS_REPORT_V1",
    readinessStatus: "REVIEWABLE",
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
