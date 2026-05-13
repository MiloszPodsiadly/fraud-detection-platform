import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getFraudCaseWorkQueueSummary, setApiSession } from "../api/alertsApi.js";
import { useFraudCaseWorkQueueSummary } from "./useFraudCaseWorkQueueSummary.js";

vi.mock("../api/alertsApi.js", () => ({
  getFraudCaseWorkQueueSummary: vi.fn(),
  setApiSession: vi.fn()
}));

describe("useFraudCaseWorkQueueSummary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getFraudCaseWorkQueueSummary.mockResolvedValue(summary(46));
  });

  it("loads the global summary only when enabled", async () => {
    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({
      enabled: true,
      session: { userId: "u1" },
      authProvider: { kind: "oidc" }
    }));

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(setApiSession).toHaveBeenCalledWith({ userId: "u1" }, { kind: "oidc" });
    expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1);
    expect(result.current.summary.totalFraudCases).toBe(46);
  });

  it("does not call the summary endpoint while disabled", () => {
    renderHook(() => useFraudCaseWorkQueueSummary({ enabled: false }));

    expect(getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
  });

  it("isolates failures in local hook state", async () => {
    const apiError = { status: 403, message: "forbidden" };
    getFraudCaseWorkQueueSummary.mockRejectedValue(apiError);

    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({ enabled: true }));

    await waitFor(() => expect(result.current.error).toBe(apiError));
    expect(result.current.summary.totalFraudCases).toBe(0);
  });

  it("retries on demand without changing external session state", async () => {
    getFraudCaseWorkQueueSummary
      .mockRejectedValueOnce({ status: 503, message: "down" })
      .mockResolvedValueOnce(summary(50));
    const { result } = renderHook(() => useFraudCaseWorkQueueSummary({ enabled: true }));
    await waitFor(() => expect(result.current.error?.status).toBe(503));

    await act(async () => {
      await result.current.retry();
    });

    await waitFor(() => expect(result.current.summary.totalFraudCases).toBe(50));
    expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(2);
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
