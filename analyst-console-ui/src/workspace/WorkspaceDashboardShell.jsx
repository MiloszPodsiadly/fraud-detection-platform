import { useCallback, useEffect, useMemo } from "react";
import { SESSION_STATES, getSessionStateForApiError, getSessionStateForProvider } from "../auth/sessionState.js";
import { useFraudCaseWorkQueue } from "../fraudCases/useFraudCaseWorkQueue.js";
import { useFraudCaseWorkQueueSummary } from "../fraudCases/useFraudCaseWorkQueueSummary.js";
import { AlertDetailsPage } from "../pages/AlertDetailsPage.jsx";
import { AlertsListPage } from "../pages/AlertsListPage.jsx";
import { FraudCaseDetailsPage } from "../pages/FraudCaseDetailsPage.jsx";
import { useAlertQueue } from "./useAlertQueue.js";
import { useGovernanceAnalytics } from "./useGovernanceAnalytics.js";
import { useGovernanceAuditWorkflow } from "./useGovernanceAuditWorkflow.js";
import { useGovernanceQueue } from "./useGovernanceQueue.js";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";
import { useWorkspaceCounters } from "./useWorkspaceCounters.js";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";

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
  const workspaceNavigationEnabled = runtimeStatus === "ready" && !shouldBlockDashboardFetch(sessionState);
  const workQueueEnabled = workspacePage === "analyst" && workspaceNavigationEnabled && canReadFraudCases === true;
  const summaryEnabled = workspacePage === "analyst" && workspaceNavigationEnabled && canReadFraudCases === true;
  const handleWorkQueueSessionError = useCallback((apiError) => {
    setSessionState(getSessionStateForApiError(session, apiError) || getSessionStateForProvider(session, authProvider));
  }, [authProvider, session, setSessionState]);
  const fraudCaseWorkQueueState = useFraudCaseWorkQueue({
    enabled: workQueueEnabled,
    onSessionError: handleWorkQueueSessionError
  });
  const fraudCaseWorkQueueSummaryState = useFraudCaseWorkQueueSummary({
    enabled: summaryEnabled
  });
  const alertQueueState = useAlertQueue({
    enabled: workspacePage === "fraudTransaction" && workspaceNavigationEnabled && canReadAlerts === true
  });
  const transactionStreamState = useScoredTransactionStream({
    enabled: workspacePage === "transactionScoring" && workspaceNavigationEnabled && canReadTransactions === true
  });
  const governanceQueueState = useGovernanceQueue({
    enabled: workspacePage === "compliance" && workspaceNavigationEnabled && canReadGovernanceAdvisories === true
  });
  const governanceAnalyticsState = useGovernanceAnalytics({
    enabled: workspacePage === "reports" && workspaceNavigationEnabled && canReadGovernanceAdvisories === true
  });
  const workspaceCounterState = useWorkspaceCounters({
    enabled: workspaceNavigationEnabled,
    includeAlerts: workspacePage !== "fraudTransaction",
    includeTransactions: workspacePage !== "transactionScoring"
  });
  const { refresh: refreshWorkspaceCounters, setCounterValue } = workspaceCounterState;
  const recordGovernanceAudit = useGovernanceAuditWorkflow({
    apiClient,
    canWriteGovernanceAudit,
    governanceQueueState,
    governanceAnalyticsState
  });

  const selectedAlertSummary = useMemo(
    () => alertQueueState.page.content.find((alert) => alert.alertId === selectedAlertId),
    [alertQueueState.page.content, selectedAlertId]
  );

  useEffect(() => {
    if (!selectedAlertId && !selectedFraudCaseId) {
      return;
    }
    window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }, [selectedAlertId, selectedFraudCaseId]);

  useEffect(() => {
    if (canReadAlerts === true) {
      setCounterValue("alerts", alertQueueState.page.totalElements);
    }
  }, [alertQueueState.page.totalElements, canReadAlerts, setCounterValue]);

  useEffect(() => {
    if (canReadTransactions === true) {
      setCounterValue("transactions", transactionStreamState.page.totalElements);
    }
  }, [canReadTransactions, setCounterValue, transactionStreamState.page.totalElements]);

  function refreshDashboard() {
    if (shouldBlockDashboardFetch(sessionState)) {
      return;
    }
    if (workspacePage === "fraudTransaction") {
      alertQueueState.refresh();
    }
    if (workspacePage === "transactionScoring") {
      transactionStreamState.refresh();
    }
    if (workspaceNavigationEnabled) {
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

  function changeTransactionPage(page) {
    transactionStreamState.setRequest((current) => ({ ...current, page: Math.min(Math.max(Number(page) || 0, 0), 1000) }));
  }

  function changeTransactionPageSize(size) {
    transactionStreamState.setRequest((current) => ({ ...current, page: 0, size: Math.min(Math.max(Number(size) || 25, 1), 100) }));
  }

  function changeTransactionFilters(filters) {
    transactionStreamState.setRequest((current) => ({
      ...current,
      ...filters,
      page: 0
    }));
  }

  function changeAlertPage(page) {
    alertQueueState.setRequest((current) => ({ ...current, page }));
  }

  function changeAlertPageSize(size) {
    alertQueueState.setRequest({ page: 0, size });
  }

  function closeSelection() {
    clearSelection();
  }

  if (selectedAlertId && apiClient) {
    return (
      <AlertDetailsPage
        alertId={selectedAlertId}
        alertSummary={selectedAlertSummary}
        session={session}
        apiClient={apiClient}
        onBack={closeSelection}
        onDecisionSubmitted={refreshDashboard}
      />
    );
  }

  if (selectedFraudCaseId && apiClient) {
    return (
      <FraudCaseDetailsPage
        caseId={selectedFraudCaseId}
        session={session}
        apiClient={apiClient}
        onBack={closeSelection}
        onCaseUpdated={refreshDashboard}
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

function shouldBlockDashboardFetch(sessionState) {
  return [
    SESSION_STATES.UNAUTHENTICATED,
    SESSION_STATES.EXPIRED,
    SESSION_STATES.ACCESS_DENIED,
    SESSION_STATES.AUTH_ERROR
  ].includes(sessionState?.status);
}
