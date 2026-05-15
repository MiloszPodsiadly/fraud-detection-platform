import { SessionStatePanel } from "../components/SecurityStatePanels.jsx";
import { SESSION_STATES } from "../auth/sessionState.js";
import { WorkspaceNavigation } from "../workspace/WorkspaceNavigation.jsx";

export function AlertsListPage({
  workspacePage = "analyst",
  workspaceRoutes,
  workspaceCounters = { alerts: null, transactions: null },
  workspaceCountersStatus = { degraded: false, failedCounters: [], errorByCounter: {}, stale: false, lastRefreshedAt: null },
  canReadFraudCases,
  canReadAlerts,
  canReadTransactions,
  canReadGovernanceAdvisories,
  alertPage = emptyPage(),
  transactionPage = emptyPage(),
  advisoryQueue = { count: 0 },
  governanceAnalytics,
  fraudCaseSummary = { totalFraudCases: 0 },
  fraudCaseTotalElements,
  fraudCaseSummaryError,
  isFraudCaseSummaryLoading = false,
  onWorkspaceChange,
  sessionState,
  error,
  onRetry,
  children
}) {
  const sessionBlocksDashboard = shouldBlockDashboard(sessionState, error);

  return (
    <div className="dashboardGrid pageEnter">
      <WorkspaceNavigation
        workspacePage={workspacePage}
        workspaceRoutes={workspaceRoutes}
        workspaceCounters={workspaceCounters}
        workspaceCountersStatus={workspaceCountersStatus}
        canReadFraudCases={canReadFraudCases}
        canReadAlerts={canReadAlerts}
        canReadTransactions={canReadTransactions}
        canReadGovernanceAdvisories={canReadGovernanceAdvisories}
        alertPage={alertPage}
        transactionPage={transactionPage}
        advisoryQueue={advisoryQueue}
        governanceAnalytics={governanceAnalytics}
        fraudCaseSummary={fraudCaseSummary}
        fraudCaseTotalElements={fraudCaseTotalElements}
        fraudCaseSummaryError={fraudCaseSummaryError}
        isFraudCaseSummaryLoading={isFraudCaseSummaryLoading}
        onWorkspaceChange={onWorkspaceChange}
      />

      {workspaceCountersStatus.degraded && (
        <div className="statePanel warningPanel" role="status" aria-live="polite">
          <h3>Some workspace counters are temporarily unavailable.</h3>
          <p>
            {workspaceCountersStatus.stale
              ? "Last known counter values are marked as stale."
              : "Unavailable counters are not shown as zero."}
          </p>
          {workspaceCountersStatus.lastRefreshedAt && (
            <p>Last successful refresh {new Date(workspaceCountersStatus.lastRefreshedAt).toLocaleString()}.</p>
          )}
          {typeof workspaceCountersStatus.refresh === "function" && (
            <button className="secondaryButton" type="button" onClick={workspaceCountersStatus.refresh}>
              Retry counters
            </button>
          )}
        </div>
      )}

      {sessionBlocksDashboard ? <SessionStatePanel sessionState={sessionState} onRetry={onRetry} /> : children}
    </div>
  );
}

function shouldBlockDashboard(sessionState, error) {
  const blockedSessionStates = [
    SESSION_STATES.UNAUTHENTICATED,
    SESSION_STATES.EXPIRED,
    SESSION_STATES.ACCESS_DENIED,
    SESSION_STATES.AUTH_ERROR
  ];

  return blockedSessionStates.includes(sessionState?.status) || [401, 403].includes(error?.status);
}

function emptyPage() {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 0
  };
}
