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
    refreshWorkspace();
    if (sharedWorkspaceReadsEnabled) {
      refreshWorkspaceCounters();
    }
    return { refreshed: true };
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
