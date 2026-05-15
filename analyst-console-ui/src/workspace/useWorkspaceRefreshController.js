import { useCallback } from "react";
import { SESSION_STATES } from "../auth/sessionState.js";

export function useWorkspaceRefreshController({
  sessionState,
  workspacePage,
  sharedWorkspaceReadsEnabled,
  alertQueueState,
  transactionStreamState,
  fraudCaseWorkQueueSummaryState,
  refreshWorkspaceCounters,
  fraudCaseWorkQueueState,
  governanceQueueState,
  governanceAnalyticsState
}) {
  return useCallback(() => {
    refreshWorkspaceDashboard({
      sessionState,
      workspacePage,
      sharedWorkspaceReadsEnabled,
      alertQueueState,
      transactionStreamState,
      fraudCaseWorkQueueSummaryState,
      refreshWorkspaceCounters,
      fraudCaseWorkQueueState,
      governanceQueueState,
      governanceAnalyticsState
    });
  }, [
    alertQueueState,
    fraudCaseWorkQueueState,
    fraudCaseWorkQueueSummaryState,
    governanceAnalyticsState,
    governanceQueueState,
    refreshWorkspaceCounters,
    sessionState,
    transactionStreamState,
    sharedWorkspaceReadsEnabled,
    workspacePage
  ]);
}

export function refreshWorkspaceDashboard({
  sessionState,
  workspacePage,
  sharedWorkspaceReadsEnabled,
  alertQueueState,
  transactionStreamState,
  fraudCaseWorkQueueSummaryState,
  refreshWorkspaceCounters,
  fraudCaseWorkQueueState,
  governanceQueueState,
  governanceAnalyticsState
}) {
  if (shouldBlockDashboardFetch(sessionState)) {
    return;
  }
  if (workspacePage === "fraudTransaction") {
    alertQueueState.refresh();
  }
  if (workspacePage === "transactionScoring") {
    transactionStreamState.refresh();
  }
  if (sharedWorkspaceReadsEnabled) {
    fraudCaseWorkQueueSummaryState.retry();
    refreshWorkspaceCounters();
  }
  if (workspacePage === "analyst") {
    fraudCaseWorkQueueState.refreshFirstSlice();
  }
  if (workspacePage === "compliance") {
    governanceQueueState.refresh();
  }
  if (workspacePage === "reports") {
    governanceAnalyticsState.refresh();
  }
}

export function shouldBlockDashboardFetch(sessionState) {
  return [
    SESSION_STATES.UNAUTHENTICATED,
    SESSION_STATES.EXPIRED,
    SESSION_STATES.ACCESS_DENIED,
    SESSION_STATES.AUTH_ERROR
  ].includes(sessionState?.status);
}
