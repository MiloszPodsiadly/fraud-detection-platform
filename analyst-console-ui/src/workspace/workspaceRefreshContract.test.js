import { describe, expect, it, vi } from "vitest";
import { SESSION_STATES } from "../auth/sessionState.js";
import { createWorkspaceRefreshHandler, shouldBlockDashboardFetch } from "./workspaceRefreshContract.js";

describe("workspaceRefreshContract", () => {
  it("does not refresh workspace or counters for blocked session states", () => {
    const refreshWorkspace = vi.fn();
    const refreshWorkspaceCounters = vi.fn();
    const refresh = createWorkspaceRefreshHandler({
      sessionState: { status: SESSION_STATES.ACCESS_DENIED },
      sharedWorkspaceReadsEnabled: true,
      refreshWorkspace,
      refreshWorkspaceCounters
    });

    expect(refresh()).toEqual({ refreshed: false, reason: "blocked-session" });
    expect(refreshWorkspace).not.toHaveBeenCalled();
    expect(refreshWorkspaceCounters).not.toHaveBeenCalled();
  });

  it("refreshes only active runtime once plus shared counters once", () => {
    const refreshWorkspace = vi.fn();
    const refreshWorkspaceCounters = vi.fn();
    const refresh = createWorkspaceRefreshHandler({
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      sharedWorkspaceReadsEnabled: true,
      refreshWorkspace,
      refreshWorkspaceCounters
    });

    expect(refresh()).toEqual({
      refreshed: true,
      workspaceRefresh: "started",
      countersRefresh: "started"
    });
    expect(refreshWorkspace).toHaveBeenCalledTimes(1);
    expect(refreshWorkspaceCounters).toHaveBeenCalledTimes(1);
  });

  it("keeps counters behind the shared reads gate", () => {
    const refreshWorkspace = vi.fn();
    const refreshWorkspaceCounters = vi.fn();
    const refresh = createWorkspaceRefreshHandler({
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      sharedWorkspaceReadsEnabled: false,
      refreshWorkspace,
      refreshWorkspaceCounters
    });

    expect(refresh()).toEqual({
      refreshed: true,
      workspaceRefresh: "started",
      countersRefresh: "skipped"
    });

    expect(refreshWorkspace).toHaveBeenCalledTimes(1);
    expect(refreshWorkspaceCounters).not.toHaveBeenCalled();
  });

  it("fails closed when workspace refresh throws synchronously", () => {
    const refreshWorkspace = vi.fn(() => {
      throw new Error("workspace failed");
    });
    const refreshWorkspaceCounters = vi.fn();
    const refresh = createWorkspaceRefreshHandler({
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      sharedWorkspaceReadsEnabled: true,
      refreshWorkspace,
      refreshWorkspaceCounters
    });

    expect(refresh()).toEqual({
      refreshed: false,
      reason: "refresh-failed",
      failedStep: "workspace"
    });
    expect(refreshWorkspaceCounters).not.toHaveBeenCalled();
  });

  it("fails closed when counters refresh throws synchronously", () => {
    const refreshWorkspace = vi.fn();
    const refreshWorkspaceCounters = vi.fn(() => {
      throw new Error("counters failed");
    });
    const refresh = createWorkspaceRefreshHandler({
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      sharedWorkspaceReadsEnabled: true,
      refreshWorkspace,
      refreshWorkspaceCounters
    });

    expect(refresh()).toEqual({
      refreshed: false,
      reason: "refresh-failed",
      failedStep: "counters"
    });
    expect(refreshWorkspace).toHaveBeenCalledTimes(1);
  });
});

describe("shouldBlockDashboardFetch", () => {
  it.each([
    SESSION_STATES.UNAUTHENTICATED,
    SESSION_STATES.EXPIRED,
    SESSION_STATES.ACCESS_DENIED,
    SESSION_STATES.AUTH_ERROR
  ])("blocks %s", (status) => {
    expect(shouldBlockDashboardFetch({ status })).toBe(true);
  });
});
