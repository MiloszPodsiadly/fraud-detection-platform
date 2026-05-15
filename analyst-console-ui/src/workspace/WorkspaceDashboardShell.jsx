import { AlertsListPage } from "../pages/AlertsListPage.jsx";
import { AnalystWorkspaceContainer } from "./AnalystWorkspaceContainer.jsx";
import { FraudTransactionWorkspaceContainer } from "./FraudTransactionWorkspaceContainer.jsx";
import { GovernanceWorkspaceContainer } from "./GovernanceWorkspaceContainer.jsx";
import { ReportsWorkspaceContainer } from "./ReportsWorkspaceContainer.jsx";
import { TransactionScoringWorkspaceContainer } from "./TransactionScoringWorkspaceContainer.jsx";
import { WorkspaceDetailRouter } from "./WorkspaceDetailRouter.jsx";
import { useAnalystWorkspaceRuntime } from "./useAnalystWorkspaceRuntime.js";
import { useGovernanceWorkspaceRuntime } from "./useGovernanceWorkspaceRuntime.js";
import { useTransactionWorkspaceRuntime } from "./useTransactionWorkspaceRuntime.js";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";
import { shouldBlockDashboardFetch, useWorkspaceRefreshController } from "./useWorkspaceRefreshController.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";

// Composition hub: wires runtime hooks, counters, and workspace containers.
// New business workflows belong in workspace-specific hooks/containers; do not add mutation workflows here.
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
    authProvider,
    apiClient,
    canReadFraudCases,
    canReadAlerts,
    canReadTransactions,
    canReadGovernanceAdvisories,
    canWriteGovernanceAudit,
    runtimeStatus
  } = useWorkspaceRuntime();
  // Gates shared workspace reads/counters; active tab refresh behavior is still owned by each workspace hook/controller.
  const sharedWorkspaceReadsEnabled = runtimeStatus === "ready" && !shouldBlockDashboardFetch(sessionState);
  const workspaceCounterState = useWorkspaceCounters({
    enabled: sharedWorkspaceReadsEnabled,
    includeAlerts: workspacePage !== "fraudTransaction",
    includeTransactions: workspacePage !== "transactionScoring"
  });
  const { refresh: refreshWorkspaceCounters, setCounterValue } = workspaceCounterState;
  const {
    workQueueState: fraudCaseWorkQueueState,
    summaryState: fraudCaseWorkQueueSummaryState
  } = useAnalystWorkspaceRuntime({
    workspacePage,
    sharedWorkspaceReadsEnabled,
    canReadFraudCases,
    session,
    authProvider,
    setSessionState
  });
  const {
    alertQueueState,
    transactionStreamState,
    changeTransactionFilters,
    changeTransactionPage,
    changeTransactionPageSize,
    changeAlertPage,
    changeAlertPageSize
  } = useTransactionWorkspaceRuntime({
    workspacePage,
    sharedWorkspaceReadsEnabled,
    canReadAlerts,
    canReadTransactions,
    setCounterValue
  });
  const {
    queueState: governanceQueueState,
    analyticsState: governanceAnalyticsState,
    recordGovernanceAudit
  } = useGovernanceWorkspaceRuntime({
    workspacePage,
    sharedWorkspaceReadsEnabled,
    canReadGovernanceAdvisories,
    canWriteGovernanceAudit,
    apiClient
  });

  const refreshDashboard = useWorkspaceRefreshController({
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

  function closeSelection() {
    clearSelection();
  }

  if ((selectedAlertId || selectedFraudCaseId) && apiClient) {
    return (
      <WorkspaceDetailRouter
        selectedAlertId={selectedAlertId}
        selectedFraudCaseId={selectedFraudCaseId}
        alertQueueState={alertQueueState}
        session={session}
        apiClient={apiClient}
        canReadAlerts={canReadAlerts}
        canReadFraudCases={canReadFraudCases}
        workspacePage={workspacePage}
        onCloseSelection={closeSelection}
        onRefreshDashboard={refreshDashboard}
      />
    );
  }

  return (
    <AlertsListPage
      workspacePage={workspacePage}
      workspaceCounters={workspaceCounterState.counters}
      workspaceCountersStatus={workspaceCounterState}
      canReadFraudCases={canReadFraudCases}
      canReadAlerts={canReadAlerts}
      canReadTransactions={canReadTransactions}
      canReadGovernanceAdvisories={canReadGovernanceAdvisories}
      canWriteGovernanceAudit={canWriteGovernanceAudit}
      alertPage={alertQueueState.page}
      fraudCaseSummary={fraudCaseWorkQueueSummaryState.summary}
      fraudCaseSummaryError={fraudCaseWorkQueueSummaryState.error}
      isFraudCaseSummaryLoading={fraudCaseWorkQueueSummaryState.isLoading}
      onWorkspaceChange={navigateWorkspace}
      transactionPage={transactionStreamState.page}
      advisoryQueue={governanceQueueState.queue}
      governanceAnalytics={governanceAnalyticsState.analytics}
      sessionState={sessionState}
      error={workspacePage === "transactionScoring" ? transactionStreamState.error : alertQueueState.error}
      onRetry={refreshDashboard}
    >
      {workspacePage === "analyst" && (
        <AnalystWorkspaceContainer
          canReadFraudCases={canReadFraudCases}
          workQueueState={fraudCaseWorkQueueState}
          summaryState={fraudCaseWorkQueueSummaryState}
          onOpenFraudCase={openFraudCase}
        />
      )}
      {workspacePage === "fraudTransaction" && (
        <FraudTransactionWorkspaceContainer
          alertQueueState={alertQueueState}
          onRetryWorkspace={refreshDashboard}
          onPageChange={changeAlertPage}
          onPageSizeChange={changeAlertPageSize}
          onOpenAlert={openAlert}
        />
      )}
      {workspacePage === "transactionScoring" && (
        <TransactionScoringWorkspaceContainer
          transactionStreamState={transactionStreamState}
          onRetryWorkspace={refreshDashboard}
          onFiltersChange={changeTransactionFilters}
          onPageChange={changeTransactionPage}
          onPageSizeChange={changeTransactionPageSize}
        />
      )}
      {workspacePage === "reports" && (
        <ReportsWorkspaceContainer
          analyticsState={governanceAnalyticsState}
        />
      )}
      {workspacePage === "compliance" && (
        <GovernanceWorkspaceContainer
          queueState={governanceQueueState}
          session={session}
          canWriteGovernanceAudit={canWriteGovernanceAudit}
          onRecordGovernanceAudit={recordGovernanceAudit}
        />
      )}
    </AlertsListPage>
  );
}
