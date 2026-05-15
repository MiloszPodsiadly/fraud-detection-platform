import { AlertsListPage } from "../pages/AlertsListPage.jsx";
import { WorkspaceDetailRouter } from "./WorkspaceDetailRouter.jsx";
import { useAnalystWorkspaceRuntime } from "./useAnalystWorkspaceRuntime.js";
import { useGovernanceWorkspaceRuntime } from "./useGovernanceWorkspaceRuntime.js";
import { useTransactionWorkspaceRuntime } from "./useTransactionWorkspaceRuntime.js";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";
import { shouldBlockDashboardFetch, useWorkspaceRefreshController } from "./useWorkspaceRefreshController.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";

// Transitional composition layer: keep new business workflows in workspace-specific hooks/containers.
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
      fraudCaseWorkQueue={fraudCaseWorkQueueState.queue}
      fraudCaseSummaryError={fraudCaseWorkQueueSummaryState.error}
      isFraudCaseSummaryLoading={fraudCaseWorkQueueSummaryState.isLoading}
      fraudCaseWorkQueueRequest={fraudCaseWorkQueueState.committedFilters}
      fraudCaseWorkQueueDraftFilters={fraudCaseWorkQueueState.draftFilters}
      fraudCaseWorkQueueWarning={fraudCaseWorkQueueState.warning}
      fraudCaseWorkQueueFilterError={fraudCaseWorkQueueState.filterError}
      fraudCaseWorkQueueLastRefreshedAt={fraudCaseWorkQueueState.lastRefreshedAt}
      onWorkspaceChange={navigateWorkspace}
      transactionPage={transactionStreamState.page}
      transactionPageRequest={transactionStreamState.request}
      advisoryQueue={governanceQueueState.queue}
      advisoryQueueRequest={governanceQueueState.request}
      governanceAnalytics={governanceAnalyticsState.analytics}
      analyticsWindowDays={governanceAnalyticsState.windowDays}
      isLoading={workspacePage === "transactionScoring" ? transactionStreamState.isLoading : alertQueueState.isLoading}
      isFraudCaseWorkQueueLoading={fraudCaseWorkQueueState.isLoading}
      isGovernanceLoading={governanceQueueState.isLoading}
      isAnalyticsLoading={governanceAnalyticsState.isLoading}
      error={workspacePage === "transactionScoring" ? transactionStreamState.error : alertQueueState.error}
      fraudCaseWorkQueueError={fraudCaseWorkQueueState.error}
      governanceError={governanceQueueState.error}
      analyticsError={governanceAnalyticsState.error}
      governanceAuditHistories={governanceQueueState.auditHistories}
      session={session}
      sessionState={sessionState}
      onRetry={refreshDashboard}
      onFraudCaseSummaryRetry={fraudCaseWorkQueueSummaryState.retry}
      onGovernanceRetry={governanceQueueState.refresh}
      onAnalyticsRetry={governanceAnalyticsState.refresh}
      onAdvisoryQueueRequestChange={governanceQueueState.setRequest}
      onFraudCaseWorkQueueDraftChange={fraudCaseWorkQueueState.updateDraftFilter}
      onFraudCaseWorkQueueApplyFilters={fraudCaseWorkQueueState.applyFilters}
      onFraudCaseWorkQueueResetFilters={fraudCaseWorkQueueState.resetFilters}
      onFraudCaseWorkQueueRetry={fraudCaseWorkQueueState.refreshFirstSlice}
      onFraudCaseWorkQueueRefreshFirstSlice={fraudCaseWorkQueueState.refreshFirstSlice}
      onFraudCaseWorkQueueLoadMore={fraudCaseWorkQueueState.loadMore}
      onAnalyticsWindowDaysChange={governanceAnalyticsState.setWindowDays}
      onRecordGovernanceAudit={recordGovernanceAudit}
      onTransactionFiltersChange={changeTransactionFilters}
      onTransactionPageChange={changeTransactionPage}
      onTransactionPageSizeChange={changeTransactionPageSize}
      onAlertPageChange={changeAlertPage}
      onAlertPageSizeChange={changeAlertPageSize}
      onOpenAlert={openAlert}
      onOpenFraudCase={openFraudCase}
    />
  );
}
