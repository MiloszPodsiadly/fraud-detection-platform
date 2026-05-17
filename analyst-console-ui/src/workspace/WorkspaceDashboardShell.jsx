import { AlertsListPage } from "../pages/AlertsListPage.jsx";
import { WorkspaceDetailRouter } from "./WorkspaceDetailRouter.jsx";
import { WORKSPACE_ROUTE_ENTRIES, resolveWorkspaceRouteResult } from "./WorkspaceRouteRegistry.jsx";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";
import { useWorkspaceRefreshNotice } from "./useWorkspaceRefreshNotice.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";
import { createWorkspaceRefreshHandler, shouldBlockDashboardFetch } from "./workspaceRefreshContract.js";
import { WORKSPACE_DETAIL_RUNTIME_STATE } from "./workspaceRuntimeStates.js";

export function WorkspaceDashboardShell({
  workspacePage,
  selectedAlertId,
  selectedFraudCaseId,
  clearSelection,
  navigateWorkspace,
  openAlert,
  openFraudCase,
  invalidWorkspaceRoute,
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
  const resolvedRoute = resolveWorkspaceRouteResult(workspacePage);
  const activeRoute = resolvedRoute.route;
  const ActiveWorkspaceRuntime = activeRoute.Runtime;
  const { refreshNotice, consumeRefreshResult } = useWorkspaceRefreshNotice(activeRoute.key);
  const sharedWorkspaceReadsEnabled = runtimeStatus === "ready" && !shouldBlockDashboardFetch(sessionState);
  const workspaceCounterState = useWorkspaceCounters({
    enabled: sharedWorkspaceReadsEnabled,
    includeAlerts: activeRoute.key !== "fraudTransaction",
    includeFraudCases: activeRoute.key !== "analyst",
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
        const refreshDashboard = createWorkspaceRefreshHandler({
          sessionState,
          sharedWorkspaceReadsEnabled,
          refreshWorkspace,
          refreshWorkspaceCounters
        });
        const refreshDashboardWithNotice = () => {
          return consumeRefreshResult(refreshDashboard());
        };
        const routeFallbackNotice = fallbackNoticeFor({
          invalidWorkspaceRoute,
          resolvedRoute
        });

        if ((selectedAlertId || selectedFraudCaseId) && apiClient) {
          return (
            <WorkspaceDetailRouter
              selectedAlertId={selectedAlertId}
              selectedFraudCaseId={selectedFraudCaseId}
              alertQueueState={detailRouterState.alertQueueState}
              alertSummaryRuntimeState={detailRouterState.alertQueueState
                ? WORKSPACE_DETAIL_RUNTIME_STATE.AVAILABLE
                : WORKSPACE_DETAIL_RUNTIME_STATE.NOT_MOUNTED}
              session={session}
              apiClient={apiClient}
              canReadAlerts={canReadAlerts}
              canReadFraudCases={canReadFraudCases}
              workspacePage={activeRoute.key}
              workspaceLabel={activeRoute.label}
              onCloseSelection={closeSelection}
              onRefreshDashboard={refreshDashboardWithNotice}
            />
          );
        }

        return (
          <AlertsListPage
            workspacePage={activeRoute.key}
            routeFallbackNotice={routeFallbackNotice}
            refreshNotice={refreshNotice}
            workspaceRoutes={WORKSPACE_ROUTE_ENTRIES}
            workspaceCounters={workspaceCounterState.counters}
            workspaceCountersStatus={workspaceCounterState}
            canReadFraudCases={canReadFraudCases}
            canReadAlerts={canReadAlerts}
            canReadTransactions={canReadTransactions}
            canReadGovernanceAdvisories={canReadGovernanceAdvisories}
            canWriteGovernanceAudit={canWriteGovernanceAudit}
            alertPage={navigationState.alertPage}
            fraudCaseSummary={navigationState.fraudCaseSummary}
            fraudCaseTotalElements={workspaceCounterState.counters.fraudCases}
            fraudCaseSummaryError={navigationState.fraudCaseSummaryError || workspaceCounterState.errorByCounter.fraudCases}
            isFraudCaseSummaryLoading={navigationState.isFraudCaseSummaryLoading
              || (activeRoute.key !== "analyst" && workspaceCounterState.isLoading && workspaceCounterState.counters.fraudCases == null)}
            onWorkspaceChange={navigateWorkspace}
            transactionPage={navigationState.transactionPage}
            advisoryQueue={navigationState.advisoryQueue}
            governanceAnalytics={navigationState.governanceAnalytics}
            sessionState={sessionState}
            error={error}
            onRetry={refreshDashboardWithNotice}
          >
            {workspaceContent}
          </AlertsListPage>
        );
      }}
    </ActiveWorkspaceRuntime>
  );
}

function fallbackNoticeFor({ invalidWorkspaceRoute, resolvedRoute }) {
  const requestedKey = invalidWorkspaceRoute || (resolvedRoute.wasInvalid ? resolvedRoute.requestedKey : null);
  if (!requestedKey) {
    return null;
  }
  return `Unknown workspace route "${requestedKey}"; showing ${resolvedRoute.route.label} workspace.`;
}
