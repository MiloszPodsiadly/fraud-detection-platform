import { useCallback, useEffect, useMemo } from "react";
import { SESSION_STATES, getSessionStateForApiError, getSessionStateForProvider } from "../auth/sessionState.js";
import { useFraudCaseWorkQueue } from "../fraudCases/useFraudCaseWorkQueue.js";
import { useFraudCaseWorkQueueSummary } from "../fraudCases/useFraudCaseWorkQueueSummary.js";
import { AlertDetailsPage } from "../pages/AlertDetailsPage.jsx";
import { AlertsListPage } from "../pages/AlertsListPage.jsx";
import { FraudCaseDetailsPage } from "../pages/FraudCaseDetailsPage.jsx";
import { useAlertQueue } from "./useAlertQueue.js";
import { useGovernanceAnalytics } from "./useGovernanceAnalytics.js";
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
    canReadGovernance,
    runtimeStatus
  } = useWorkspaceRuntime();
  const workspaceNavigationEnabled = runtimeStatus === "ready" && !shouldBlockDashboardFetch(sessionState);
  const workQueueEnabled = workspacePage === "analyst" && workspaceNavigationEnabled && canReadFraudCases !== false;
  const summaryEnabled = workspacePage === "analyst" && workspaceNavigationEnabled && canReadFraudCases !== false;
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
    enabled: workspacePage === "fraudTransaction" && workspaceNavigationEnabled && canReadAlerts !== false
  });
  const transactionStreamState = useScoredTransactionStream({
    enabled: workspacePage === "transactionScoring" && workspaceNavigationEnabled && canReadTransactions !== false
  });
  const governanceQueueState = useGovernanceQueue({
    enabled: workspacePage === "compliance" && workspaceNavigationEnabled && canReadGovernance !== false
  });
  const governanceAnalyticsState = useGovernanceAnalytics({
    enabled: workspacePage === "reports" && workspaceNavigationEnabled && canReadGovernance !== false
  });
  const workspaceCounterState = useWorkspaceCounters({
    enabled: workspaceNavigationEnabled,
    includeAlerts: workspacePage !== "fraudTransaction",
    includeTransactions: workspacePage !== "transactionScoring"
  });
  const { refresh: refreshWorkspaceCounters, setCounterValue } = workspaceCounterState;

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
    setCounterValue("alerts", alertQueueState.page.totalElements);
  }, [alertQueueState.page.totalElements, setCounterValue]);

  useEffect(() => {
    setCounterValue("transactions", transactionStreamState.page.totalElements);
  }, [setCounterValue, transactionStreamState.page.totalElements]);

  async function recordGovernanceAudit(eventId, audit) {
    await apiClient.recordGovernanceAdvisoryAudit(eventId, audit);
    const nextHistory = await apiClient.getGovernanceAdvisoryAudit(eventId);
    governanceQueueState.setAuditHistories((current) => ({
      ...current,
      [eventId]: nextHistory
    }));
    const latestDecision = nextHistory.audit_events?.[0]?.decision || "OPEN";
    governanceQueueState.setQueue((current) => ({
      ...current,
      advisory_events: current.advisory_events
        .map((event) => (event.event_id === eventId ? { ...event, lifecycle_status: latestDecision } : event))
        .filter((event) => governanceQueueState.request.lifecycleStatus === "ALL" || event.lifecycle_status === governanceQueueState.request.lifecycleStatus)
    }));
    governanceAnalyticsState.refresh();
  }

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

  if (selectedAlertId) {
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

  if (selectedFraudCaseId) {
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
      canReadGovernance={canReadGovernance}
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
