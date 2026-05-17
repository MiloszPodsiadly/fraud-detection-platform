import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useWorkspaceRefreshNotice } from "./useWorkspaceRefreshNotice.js";

describe("useWorkspaceRefreshNotice", () => {
  it("keeps blocked refresh silent and clears on successful refresh", () => {
    const { result } = renderHook(() => useWorkspaceRefreshNotice("analyst"));

    act(() => {
      result.current.consumeRefreshResult({ refreshed: false, reason: "blocked-session" });
    });
    expect(result.current.refreshNotice).toBeNull();

    act(() => {
      result.current.consumeRefreshResult({ refreshed: true, workspaceRefresh: "started", countersRefresh: "started" });
    });
    expect(result.current.refreshNotice).toBeNull();
  });

  it("surfaces low-cardinality refresh failure without entity identifiers", () => {
    const { result } = renderHook(() => useWorkspaceRefreshNotice("analyst"));

    act(() => {
      result.current.consumeRefreshResult({
        refreshed: false,
        reason: "refresh-failed",
        failedStep: "workspace",
        alertId: "alert-1"
      });
    });

    expect(result.current.refreshNotice).toEqual(expect.objectContaining({
      message: "Refresh could not be started. Try again."
    }));
    expect(JSON.stringify(result.current.refreshNotice)).not.toContain("alert-1");
  });

  it("clears stale refresh notice when workspace changes", () => {
    const { result, rerender } = renderHook(({ workspaceKey }) => useWorkspaceRefreshNotice(workspaceKey), {
      initialProps: { workspaceKey: "analyst" }
    });

    act(() => {
      result.current.consumeRefreshResult({ refreshed: false, reason: "refresh-failed", failedStep: "counters" });
    });
    expect(result.current.refreshNotice).not.toBeNull();

    rerender({ workspaceKey: "reports" });

    expect(result.current.refreshNotice).toBeNull();
  });
});
