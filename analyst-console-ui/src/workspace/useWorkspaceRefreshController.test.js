import { describe, expect, it, vi } from "vitest";
import { SESSION_STATES } from "../auth/sessionState.js";
import { refreshWorkspaceDashboard, shouldBlockDashboardFetch } from "./useWorkspaceRefreshController.js";

describe("refreshWorkspaceDashboard", () => {
  it("does not refresh workspace data for blocked session states", () => {
    const states = workspaceStates();

    refreshWorkspaceDashboard({
      ...states,
      sessionState: { status: SESSION_STATES.ACCESS_DENIED },
      workspacePage: "analyst",
      sharedWorkspaceReadsEnabled: true
    });

    expect(states.alertQueueState.refresh).not.toHaveBeenCalled();
    expect(states.transactionStreamState.refresh).not.toHaveBeenCalled();
    expect(states.fraudCaseWorkQueueSummaryState.retry).not.toHaveBeenCalled();
    expect(states.refreshWorkspaceCounters).not.toHaveBeenCalled();
    expect(states.fraudCaseWorkQueueState.refreshFirstSlice).not.toHaveBeenCalled();
    expect(states.governanceQueueState.refresh).not.toHaveBeenCalled();
    expect(states.governanceAnalyticsState.refresh).not.toHaveBeenCalled();
  });

  it("refreshes the analyst page work queue plus shared workspace counters", () => {
    const states = workspaceStates();

    refreshWorkspaceDashboard({
      ...states,
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      workspacePage: "analyst",
      sharedWorkspaceReadsEnabled: true
    });

    expect(states.fraudCaseWorkQueueState.refreshFirstSlice).toHaveBeenCalledTimes(1);
    expect(states.fraudCaseWorkQueueSummaryState.retry).toHaveBeenCalledTimes(1);
    expect(states.refreshWorkspaceCounters).toHaveBeenCalledTimes(1);
    expect(states.alertQueueState.refresh).not.toHaveBeenCalled();
    expect(states.transactionStreamState.refresh).not.toHaveBeenCalled();
  });

  it("refreshes the active transaction and governance views without changing routing behavior", () => {
    const fraudTransaction = workspaceStates();
    refreshWorkspaceDashboard({
      ...fraudTransaction,
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      workspacePage: "fraudTransaction",
      sharedWorkspaceReadsEnabled: true
    });
    expect(fraudTransaction.alertQueueState.refresh).toHaveBeenCalledTimes(1);
    expect(fraudTransaction.fraudCaseWorkQueueSummaryState.retry).toHaveBeenCalledTimes(1);
    expect(fraudTransaction.refreshWorkspaceCounters).toHaveBeenCalledTimes(1);

    const transactionScoring = workspaceStates();
    refreshWorkspaceDashboard({
      ...transactionScoring,
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      workspacePage: "transactionScoring",
      sharedWorkspaceReadsEnabled: true
    });
    expect(transactionScoring.transactionStreamState.refresh).toHaveBeenCalledTimes(1);
    expect(transactionScoring.fraudCaseWorkQueueSummaryState.retry).toHaveBeenCalledTimes(1);
    expect(transactionScoring.refreshWorkspaceCounters).toHaveBeenCalledTimes(1);

    const compliance = workspaceStates();
    refreshWorkspaceDashboard({
      ...compliance,
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      workspacePage: "compliance",
      sharedWorkspaceReadsEnabled: true
    });
    expect(compliance.governanceQueueState.refresh).toHaveBeenCalledTimes(1);
    expect(compliance.fraudCaseWorkQueueSummaryState.retry).toHaveBeenCalledTimes(1);
    expect(compliance.refreshWorkspaceCounters).toHaveBeenCalledTimes(1);

    const reports = workspaceStates();
    refreshWorkspaceDashboard({
      ...reports,
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      workspacePage: "reports",
      sharedWorkspaceReadsEnabled: true
    });
    expect(reports.governanceAnalyticsState.refresh).toHaveBeenCalledTimes(1);
    expect(reports.fraudCaseWorkQueueSummaryState.retry).toHaveBeenCalledTimes(1);
    expect(reports.refreshWorkspaceCounters).toHaveBeenCalledTimes(1);
  });

  it("keeps shared summary and counters behind the shared read gate while active tab refresh can still run", () => {
    const states = workspaceStates();

    refreshWorkspaceDashboard({
      ...states,
      sessionState: { status: SESSION_STATES.AUTHENTICATED },
      workspacePage: "fraudTransaction",
      sharedWorkspaceReadsEnabled: false
    });

    expect(states.alertQueueState.refresh).toHaveBeenCalledTimes(1);
    expect(states.fraudCaseWorkQueueSummaryState.retry).not.toHaveBeenCalled();
    expect(states.refreshWorkspaceCounters).not.toHaveBeenCalled();
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

function workspaceStates() {
  return {
    alertQueueState: { refresh: vi.fn() },
    transactionStreamState: { refresh: vi.fn() },
    fraudCaseWorkQueueSummaryState: { retry: vi.fn() },
    refreshWorkspaceCounters: vi.fn(),
    fraudCaseWorkQueueState: { refreshFirstSlice: vi.fn() },
    governanceQueueState: { refresh: vi.fn() },
    governanceAnalyticsState: { refresh: vi.fn() }
  };
}
