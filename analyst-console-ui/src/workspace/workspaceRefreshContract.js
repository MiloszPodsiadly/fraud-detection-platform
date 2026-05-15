import { SESSION_STATES } from "../auth/sessionState.js";

export function createWorkspaceRefreshHandler({
  sessionState,
  sharedWorkspaceReadsEnabled,
  refreshWorkspace,
  refreshWorkspaceCounters
}) {
  return function refreshActiveWorkspaceDashboard() {
    if (shouldBlockDashboardFetch(sessionState)) {
      return { refreshed: false, reason: "blocked-session" };
    }
    try {
      refreshWorkspace();
    } catch {
      return { refreshed: false, reason: "refresh-failed", failedStep: "workspace" };
    }
    if (sharedWorkspaceReadsEnabled) {
      try {
        refreshWorkspaceCounters();
      } catch {
        return { refreshed: false, reason: "refresh-failed", failedStep: "counters" };
      }
      return { refreshed: true, workspaceRefresh: "started", countersRefresh: "started" };
    }
    return { refreshed: true, workspaceRefresh: "started", countersRefresh: "skipped" };
  };
}

export function shouldBlockDashboardFetch(sessionState) {
  return [
    SESSION_STATES.UNAUTHENTICATED,
    SESSION_STATES.EXPIRED,
    SESSION_STATES.ACCESS_DENIED,
    SESSION_STATES.AUTH_ERROR
  ].includes(sessionState?.status);
}
