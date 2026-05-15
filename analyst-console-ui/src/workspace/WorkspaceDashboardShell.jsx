import { AlertsListPage } from "../pages/AlertsListPage.jsx";
import { WorkspaceDetailRouter } from "./WorkspaceDetailRouter.jsx";
import { WORKSPACE_ROUTE_ENTRIES, resolveWorkspaceRoute } from "./WorkspaceRouteRegistry.jsx";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";
import { shouldBlockDashboardFetch } from "./useWorkspaceRefreshController.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const EMPTY_PAGE = Object.freeze({
  content: Object.freeze([]),
  totalElements: 0,
  totalPages: 0,
  page: 0,
  size: 0
});

const EMPTY_ALERT_QUEUE_STATE = Object.freeze({
  page: EMPTY_PAGE
});

const EMPTY_ADVISORY_QUEUE = Object.freeze({
  count: 0,
  advisory_events: Object.freeze([])
});

export function WorkspaceDashboardShell({
  workspacePage,
  selectedAlertId,
  selectedFraudCaseId,
  clearSelection,
  navigateWorkspace,
  openAlert,
  openFraudCase,
  sessionState,
  setSessionState
}) {
  const {
    session,
    apiClient,
    canReadFraudCases,
    canReadAlerts,
    canReadTransactions,
    canReadGovernanceAdvisories,
    canWriteGovernanceAudit,
    runtimeStatus
  } = useWorkspaceRuntime();
  const activeRoute = resolveWorkspaceRoute(workspacePage);
  const ActiveWorkspaceRuntime = activeRoute.Runtime;
  const sharedWorkspaceReadsEnabled = runtimeStatus === "ready" && !shouldBlockDashboardFetch(sessionState);
  const workspaceCounterState = useWorkspaceCounters({
    enabled: sharedWorkspaceReadsEnabled,
    includeAlerts: activeRoute.key !== "fraudTransaction",
    includeTransactions: activeRoute.key !== "transactionScoring"
  });
  const { refresh: refreshWorkspaceCounters, setCounterValue } = workspaceCounterState;

  function closeSelection() {
    clearSelection();
  }

  return (
    <ActiveWorkspaceRuntime
      route={activeRoute}
      sharedWorkspaceReadsEnabled={sharedWorkspaceReadsEnabled}
      setCounterValue={setCounterValue}
      setSessionState={setSessionState}
      onOpenAlert={openAlert}
      onOpenFraudCase={openFraudCase}
    >
      {({
        workspaceContent,
        navigationState = {},
        detailRouterState = {},
        error,
        refreshWorkspace
      }) => {
        function refreshDashboard() {
          if (shouldBlockDashboardFetch(sessionState)) {
            return;
          }
          refreshWorkspace?.();
          if (sharedWorkspaceReadsEnabled) {
            refreshWorkspaceCounters();
          }
        }

        if ((selectedAlertId || selectedFraudCaseId) && apiClient) {
          return (
            <WorkspaceDetailRouter
              selectedAlertId={selectedAlertId}
              selectedFraudCaseId={selectedFraudCaseId}
              alertQueueState={detailRouterState.alertQueueState || EMPTY_ALERT_QUEUE_STATE}
              session={session}
              apiClient={apiClient}
              canReadAlerts={canReadAlerts}
              canReadFraudCases={canReadFraudCases}
              workspacePage={activeRoute.key}
              workspaceLabel={activeRoute.label}
              onCloseSelection={closeSelection}
              onRefreshDashboard={refreshDashboard}
            />
          );
        }

        return (
          <AlertsListPage
            workspacePage={activeRoute.key}
            workspaceRoutes={WORKSPACE_ROUTE_ENTRIES}
            workspaceCounters={workspaceCounterState.counters}
            workspaceCountersStatus={workspaceCounterState}
            canReadFraudCases={canReadFraudCases}
            canReadAlerts={canReadAlerts}
            canReadTransactions={canReadTransactions}
            canReadGovernanceAdvisories={canReadGovernanceAdvisories}
            canWriteGovernanceAudit={canWriteGovernanceAudit}
            alertPage={navigationState.alertPage || EMPTY_PAGE}
            fraudCaseSummary={navigationState.fraudCaseSummary}
            fraudCaseSummaryError={navigationState.fraudCaseSummaryError}
            isFraudCaseSummaryLoading={navigationState.isFraudCaseSummaryLoading}
            onWorkspaceChange={navigateWorkspace}
            transactionPage={navigationState.transactionPage || EMPTY_PAGE}
            advisoryQueue={navigationState.advisoryQueue || EMPTY_ADVISORY_QUEUE}
            governanceAnalytics={navigationState.governanceAnalytics}
            sessionState={sessionState}
            error={error}
            onRetry={refreshDashboard}
          >
            {workspaceContent}
          </AlertsListPage>
        );
      }}
    </ActiveWorkspaceRuntime>
  );
}
