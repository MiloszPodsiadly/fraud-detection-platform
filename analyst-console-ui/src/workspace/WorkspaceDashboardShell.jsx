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

// Composition layer: keep new business workflows in workspace-specific hooks/containers.
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
          fraudCaseSummaryError={fraudCaseWorkQueueSummaryState.error}
          fraudCaseWorkQueue={fraudCaseWorkQueueState.queue}
          fraudCaseWorkQueueRequest={fraudCaseWorkQueueState.committedFilters}
          fraudCaseWorkQueueDraftFilters={fraudCaseWorkQueueState.draftFilters}
          fraudCaseWorkQueueWarning={fraudCaseWorkQueueState.warning}
          fraudCaseWorkQueueFilterError={fraudCaseWorkQueueState.filterError}
          fraudCaseWorkQueueLastRefreshedAt={fraudCaseWorkQueueState.lastRefreshedAt}
          isFraudCaseWorkQueueLoading={fraudCaseWorkQueueState.isLoading}
          fraudCaseWorkQueueError={fraudCaseWorkQueueState.error}
          onFraudCaseSummaryRetry={fraudCaseWorkQueueSummaryState.retry}
          onFraudCaseWorkQueueDraftChange={fraudCaseWorkQueueState.updateDraftFilter}
          onFraudCaseWorkQueueApplyFilters={fraudCaseWorkQueueState.applyFilters}
          onFraudCaseWorkQueueResetFilters={fraudCaseWorkQueueState.resetFilters}
          onFraudCaseWorkQueueRetry={fraudCaseWorkQueueState.refreshFirstSlice}
          onFraudCaseWorkQueueRefreshFirstSlice={fraudCaseWorkQueueState.refreshFirstSlice}
          onFraudCaseWorkQueueLoadMore={fraudCaseWorkQueueState.loadMore}
          onOpenFraudCase={openFraudCase}
        />
      )}
      {workspacePage === "fraudTransaction" && (
        <FraudTransactionWorkspaceContainer
          alertPage={alertQueueState.page}
          isLoading={alertQueueState.isLoading}
          error={alertQueueState.error}
          onRetry={refreshDashboard}
          onAlertPageChange={changeAlertPage}
          onAlertPageSizeChange={changeAlertPageSize}
          onOpenAlert={openAlert}
        />
      )}
      {workspacePage === "transactionScoring" && (
        <TransactionScoringWorkspaceContainer
          transactionPage={transactionStreamState.page}
          transactionPageRequest={transactionStreamState.request}
          isLoading={transactionStreamState.isLoading}
          error={transactionStreamState.error}
          onRetry={refreshDashboard}
          onTransactionFiltersChange={changeTransactionFilters}
          onTransactionPageChange={changeTransactionPage}
          onTransactionPageSizeChange={changeTransactionPageSize}
        />
      )}
      {workspacePage === "reports" && (
        <ReportsWorkspaceContainer
          governanceAnalytics={governanceAnalyticsState.analytics}
          analyticsWindowDays={governanceAnalyticsState.windowDays}
          isAnalyticsLoading={governanceAnalyticsState.isLoading}
          analyticsError={governanceAnalyticsState.error}
          onAnalyticsWindowDaysChange={governanceAnalyticsState.setWindowDays}
          onAnalyticsRetry={governanceAnalyticsState.refresh}
        />
      )}
      {workspacePage === "compliance" && (
        <GovernanceWorkspaceContainer
          advisoryQueue={governanceQueueState.queue}
          advisoryQueueRequest={governanceQueueState.request}
          isGovernanceLoading={governanceQueueState.isLoading}
          governanceError={governanceQueueState.error}
          governanceAuditHistories={governanceQueueState.auditHistories}
          session={session}
          canWriteGovernanceAudit={canWriteGovernanceAudit}
          onAdvisoryQueueRequestChange={governanceQueueState.setRequest}
          onGovernanceRetry={governanceQueueState.refresh}
          onRecordGovernanceAudit={recordGovernanceAudit}
        />
      )}
    </AlertsListPage>
  );
}
