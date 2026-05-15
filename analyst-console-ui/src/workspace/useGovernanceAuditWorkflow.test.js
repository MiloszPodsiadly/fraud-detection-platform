import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useGovernanceAuditWorkflow } from "./useGovernanceAuditWorkflow.js";

describe("useGovernanceAuditWorkflow", () => {
  it("records audit, updates history, updates lifecycle, and refreshes analytics", async () => {
    const apiClient = {
      recordGovernanceAdvisoryAudit: vi.fn().mockResolvedValue(undefined),
      getGovernanceAdvisoryAudit: vi.fn().mockResolvedValue({
        audit_events: [{ audit_id: "audit-1", decision: "ACKNOWLEDGED" }]
      })
    };
    let histories = {};
    let queue = {
      advisory_events: [
        { event_id: "event-1", lifecycle_status: "OPEN" },
        { event_id: "event-2", lifecycle_status: "OPEN" }
      ]
    };
    const governanceQueueState = {
      request: { lifecycleStatus: "ALL" },
      setAuditHistories: vi.fn((updater) => {
        histories = updater(histories);
      }),
      setQueue: vi.fn((updater) => {
        queue = updater(queue);
      })
    };
    const governanceAnalyticsState = { refresh: vi.fn() };
    const { result } = renderHook(() => useGovernanceAuditWorkflow({
      apiClient,
      canWriteGovernanceAudit: true,
      governanceQueueState,
      governanceAnalyticsState
    }));

    await expect(result.current("event-1", { decision: "ACKNOWLEDGED" })).resolves.toEqual({ ok: true });

    expect(apiClient.recordGovernanceAdvisoryAudit).toHaveBeenCalledWith("event-1", { decision: "ACKNOWLEDGED" });
    expect(histories["event-1"].audit_events[0].audit_id).toBe("audit-1");
    expect(queue.advisory_events[0].lifecycle_status).toBe("ACKNOWLEDGED");
    expect(governanceAnalyticsState.refresh).toHaveBeenCalledTimes(1);
  });

  it("does not mutate local state when recording audit fails", async () => {
    const apiClient = {
      recordGovernanceAdvisoryAudit: vi.fn().mockRejectedValue(new Error("write failed")),
      getGovernanceAdvisoryAudit: vi.fn()
    };
    const governanceQueueState = {
      request: { lifecycleStatus: "ALL" },
      setAuditHistories: vi.fn(),
      setQueue: vi.fn()
    };
    const governanceAnalyticsState = { refresh: vi.fn() };
    const { result } = renderHook(() => useGovernanceAuditWorkflow({
      apiClient,
      canWriteGovernanceAudit: true,
      governanceQueueState,
      governanceAnalyticsState
    }));

    await expect(result.current("event-1", { decision: "ACKNOWLEDGED" })).resolves.toMatchObject({
      ok: false,
      reason: "record-failed",
      message: "write failed"
    });

    expect(apiClient.getGovernanceAdvisoryAudit).not.toHaveBeenCalled();
    expect(governanceQueueState.setAuditHistories).not.toHaveBeenCalled();
    expect(governanceQueueState.setQueue).not.toHaveBeenCalled();
    expect(governanceAnalyticsState.refresh).not.toHaveBeenCalled();
  });

  it("fails closed without runtime client or write authority", async () => {
    const governanceQueueState = {
      request: { lifecycleStatus: "ALL" },
      setAuditHistories: vi.fn(),
      setQueue: vi.fn()
    };
    const governanceAnalyticsState = { refresh: vi.fn() };
    const missingClient = renderHook(() => useGovernanceAuditWorkflow({
      apiClient: null,
      canWriteGovernanceAudit: true,
      governanceQueueState,
      governanceAnalyticsState
    }));
    const missingWriteAuthority = renderHook(() => useGovernanceAuditWorkflow({
      apiClient: { recordGovernanceAdvisoryAudit: vi.fn(), getGovernanceAdvisoryAudit: vi.fn() },
      canWriteGovernanceAudit: false,
      governanceQueueState,
      governanceAnalyticsState
    }));

    await expect(missingClient.result.current("event-1", {})).resolves.toMatchObject({
      ok: false,
      reason: "runtime-not-ready",
      message: "Workspace runtime is not ready."
    });
    await expect(missingWriteAuthority.result.current("event-1", {})).resolves.toMatchObject({
      ok: false,
      reason: "write-authority-unavailable",
      message: "Governance audit write authority is not available."
    });
    expect(governanceQueueState.setAuditHistories).not.toHaveBeenCalled();
    expect(governanceQueueState.setQueue).not.toHaveBeenCalled();
  });

  it("reports audit history refresh failure without mutating local queue state", async () => {
    const apiClient = {
      recordGovernanceAdvisoryAudit: vi.fn().mockResolvedValue(undefined),
      getGovernanceAdvisoryAudit: vi.fn().mockRejectedValue(new Error("history failed"))
    };
    const governanceQueueState = {
      request: { lifecycleStatus: "ALL" },
      setAuditHistories: vi.fn(),
      setQueue: vi.fn()
    };
    const governanceAnalyticsState = { refresh: vi.fn() };
    const { result } = renderHook(() => useGovernanceAuditWorkflow({
      apiClient,
      canWriteGovernanceAudit: true,
      governanceQueueState,
      governanceAnalyticsState
    }));

    await expect(result.current("event-1", { decision: "ACKNOWLEDGED" })).resolves.toMatchObject({
      ok: false,
      reason: "history-refresh-failed",
      message: "history failed"
    });

    expect(apiClient.recordGovernanceAdvisoryAudit).toHaveBeenCalledTimes(1);
    expect(governanceQueueState.setAuditHistories).not.toHaveBeenCalled();
    expect(governanceQueueState.setQueue).not.toHaveBeenCalled();
    expect(governanceAnalyticsState.refresh).not.toHaveBeenCalled();
  });

  it("does not undo recorded audit UI state when analytics refresh fails", async () => {
    const apiClient = {
      recordGovernanceAdvisoryAudit: vi.fn().mockResolvedValue(undefined),
      getGovernanceAdvisoryAudit: vi.fn().mockResolvedValue({
        audit_events: [{ audit_id: "audit-1", decision: "NEEDS_FOLLOW_UP" }]
      })
    };
    let histories = {};
    let queue = {
      advisory_events: [{ event_id: "event-1", lifecycle_status: "OPEN" }]
    };
    const governanceQueueState = {
      request: { lifecycleStatus: "ALL" },
      setAuditHistories: vi.fn((updater) => {
        histories = updater(histories);
      }),
      setQueue: vi.fn((updater) => {
        queue = updater(queue);
      })
    };
    const governanceAnalyticsState = { refresh: vi.fn().mockRejectedValue(new Error("analytics failed")) };
    const { result } = renderHook(() => useGovernanceAuditWorkflow({
      apiClient,
      canWriteGovernanceAudit: true,
      governanceQueueState,
      governanceAnalyticsState
    }));

    await expect(result.current("event-1", { decision: "NEEDS_FOLLOW_UP" })).resolves.toEqual({ ok: true });

    expect(histories["event-1"].audit_events[0].decision).toBe("NEEDS_FOLLOW_UP");
    expect(queue.advisory_events[0].lifecycle_status).toBe("NEEDS_FOLLOW_UP");
    expect(governanceAnalyticsState.refresh).toHaveBeenCalledTimes(1);
  });
});
