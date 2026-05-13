import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createAlertsApiClient } from "./api/alertsApi.js";
import { getConfiguredAuthProvider } from "./auth/authProvider.js";
import { isOidcCallbackPath } from "./auth/oidcClient.js";
import { normalizeSession } from "./auth/session.js";
import { SESSION_STATES, getSessionStateForApiError, getSessionStateForProvider } from "./auth/sessionState.js";
import { AUTHORITIES, hasAuthority } from "./auth/session.js";
import { SessionBadge, SessionSettingsMenu } from "./components/SessionBadge.jsx";
import { useFraudCaseWorkQueue } from "./fraudCases/useFraudCaseWorkQueue.js";
import { useFraudCaseWorkQueueSummary } from "./fraudCases/useFraudCaseWorkQueueSummary.js";
import { AlertDetailsPage } from "./pages/AlertDetailsPage.jsx";
import { AlertsListPage } from "./pages/AlertsListPage.jsx";
import { FraudCaseDetailsPage } from "./pages/FraudCaseDetailsPage.jsx";
import { useAlertQueue } from "./workspace/useAlertQueue.js";
import { useGovernanceAnalytics } from "./workspace/useGovernanceAnalytics.js";
import { useGovernanceQueue } from "./workspace/useGovernanceQueue.js";
import { useScoredTransactionStream } from "./workspace/useScoredTransactionStream.js";
import { WORKSPACE_PAGES, useWorkspaceRoute } from "./workspace/useWorkspaceRoute.js";

export default function App() {
  const authProvider = useMemo(() => getConfiguredAuthProvider(), []);
  const {
    workspacePage,
    selectedAlertId,
    selectedFraudCaseId,
    navigateWorkspace,
    openAlert,
    openFraudCase,
    clearSelection,
    workspaceHref
  } = useWorkspaceRoute();
  const [workspaceCounters, setWorkspaceCounters] = useState({
    alerts: 0,
    transactions: 0
  });
  const [session, setSession] = useState(() => authProvider.getInitialSession());
  const [sessionState, setSessionState] = useState(() => getSessionStateForProvider(authProvider.getInitialSession(), authProvider));
  const apiClient = useMemo(() => createAlertsApiClient({ session, authProvider }), [authProvider, session]);
  const [callbackError, setCallbackError] = useState(null);
  const handlingOidcCallback = authProvider.kind === "oidc" && isOidcCallbackPath();
  const [sessionBootstrapPending, setSessionBootstrapPending] = useState(Boolean(authProvider.requiresSessionBootstrap) || authProvider.kind === "oidc");
  const skipNextOidcBootstrapRef = useRef(false);
  const workspaceCounterRequestSeqRef = useRef(0);
  const workspaceNavigationEnabled = !handlingOidcCallback
    && !sessionBootstrapPending
    && !shouldBlockDashboardFetch(sessionState);
  const canReadFraudCases = session?.authorities?.length > 0 ? hasAuthority(session, AUTHORITIES.FRAUD_CASE_READ) : undefined;
  const workQueueEnabled = workspacePage === "analyst" && workspaceNavigationEnabled && canReadFraudCases !== false;
  const summaryEnabled = workspacePage === "analyst" && workspaceNavigationEnabled && canReadFraudCases !== false;
  const handleWorkQueueSessionError = useCallback((apiError) => {
    setSessionState(getSessionStateForApiError(session, apiError) || getSessionStateForProvider(session, authProvider));
  }, [authProvider, session]);
  const fraudCaseWorkQueueState = useFraudCaseWorkQueue({
    enabled: workQueueEnabled,
    session,
    authProvider,
    apiClient,
    onSessionError: handleWorkQueueSessionError
  });
  const fraudCaseWorkQueueSummaryState = useFraudCaseWorkQueueSummary({
    enabled: summaryEnabled,
    canReadFraudCases,
    session,
    authProvider,
    apiClient
  });
  const alertQueueState = useAlertQueue({
    enabled: workspacePage === "fraudTransaction" && workspaceNavigationEnabled,
    session,
    authProvider,
    apiClient
  });
  const transactionStreamState = useScoredTransactionStream({
    enabled: workspacePage === "transactionScoring" && workspaceNavigationEnabled,
    session,
    authProvider,
    apiClient
  });
  const governanceQueueState = useGovernanceQueue({
    enabled: workspacePage === "compliance" && workspaceNavigationEnabled,
    apiClient
  });
  const governanceAnalyticsState = useGovernanceAnalytics({
    enabled: workspacePage === "reports" && workspaceNavigationEnabled,
    apiClient
  });

  useEffect(() => {
    authProvider.persistSession(session);
    setSessionState(getSessionStateForProvider(session, authProvider));
  }, [authProvider, session]);

  useEffect(() => {
    if (!selectedAlertId && !selectedFraudCaseId) {
      return;
    }
    window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }, [selectedAlertId, selectedFraudCaseId]);

  useEffect(() => {
    if ((!authProvider.requiresSessionBootstrap && authProvider.kind !== "oidc") || handlingOidcCallback || typeof authProvider.refreshSession !== "function") {
      setSessionBootstrapPending(false);
      return;
    }
    if (skipNextOidcBootstrapRef.current) {
      skipNextOidcBootstrapRef.current = false;
      setSessionBootstrapPending(false);
      return;
    }

    let cancelled = false;
    setSessionBootstrapPending(true);
    setSessionState({ status: SESSION_STATES.LOADING });

    authProvider.refreshSession()
      .then((nextSession) => {
        if (cancelled) {
          return;
        }
        setSession(normalizeSession(nextSession));
        setSessionState(getSessionStateForProvider(nextSession, authProvider));
      })
      .catch((refreshFailure) => {
        if (cancelled) {
          return;
        }
        setCallbackError(refreshFailure);
        setSessionState({ status: SESSION_STATES.AUTH_ERROR });
      })
      .finally(() => {
        if (!cancelled) {
          setSessionBootstrapPending(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [authProvider, handlingOidcCallback]);

  useEffect(() => {
    if (!workspaceNavigationEnabled) {
      return;
    }
    loadWorkspaceCounters({
      includeAlerts: workspacePage !== "fraudTransaction",
      includeTransactions: workspacePage !== "transactionScoring"
    });
  }, [apiClient, workspaceNavigationEnabled, workspacePage]);

  useEffect(() => {
    if (!handlingOidcCallback || typeof authProvider.completeLoginCallback !== "function") {
      return;
    }

    let cancelled = false;
    setCallbackError(null);
    setSessionState({ status: SESSION_STATES.LOADING });

    authProvider.completeLoginCallback()
      .then((nextSession) => {
        if (cancelled) {
          return;
        }
        setSession(normalizeSession(nextSession));
        skipNextOidcBootstrapRef.current = true;
        window.history.replaceState({}, "", "/");
        setSessionState(getSessionStateForProvider(nextSession, authProvider));
      })
      .catch((callbackFailure) => {
        if (cancelled) {
          return;
        }
        setCallbackError(callbackFailure);
        setSessionState({ status: SESSION_STATES.AUTH_ERROR });
      })
      .finally(() => {});

    return () => {
      cancelled = true;
    };
  }, [authProvider, handlingOidcCallback, session]);

  const selectedAlertSummary = useMemo(
    () => alertQueueState.page.content.find((alert) => alert.alertId === selectedAlertId),
    [alertQueueState.page.content, selectedAlertId]
  );

  useEffect(() => {
    setWorkspaceCounters((current) => ({
      ...current,
      alerts: alertQueueState.page.totalElements ?? current.alerts
    }));
  }, [alertQueueState.page.totalElements]);

  useEffect(() => {
    setWorkspaceCounters((current) => ({
      ...current,
      transactions: transactionStreamState.page.totalElements ?? current.transactions
    }));
  }, [transactionStreamState.page.totalElements]);

  async function loadWorkspaceCounters({ includeAlerts = true, includeTransactions = true } = {}) {
    const requests = [];
    if (includeAlerts) {
      requests.push(["alerts", apiClient.listAlerts({ page: 0, size: 1 })]);
    }
    if (includeTransactions) {
      requests.push([
        "transactions",
        apiClient.listScoredTransactions({
          page: 0,
          size: 1,
          query: "",
          riskLevel: "ALL",
          status: "ALL"
        })
      ]);
    }
    if (requests.length === 0) {
      return;
    }

    const requestSeq = workspaceCounterRequestSeqRef.current + 1;
    workspaceCounterRequestSeqRef.current = requestSeq;
    const results = await Promise.allSettled(requests.map(([, request]) => request));
    if (workspaceCounterRequestSeqRef.current !== requestSeq) {
      return;
    }

    setWorkspaceCounters((current) => requests.reduce((next, [counterName], index) => {
      const result = results[index];
      if (result.status !== "fulfilled") {
        return next;
      }
      return {
        ...next,
        [counterName]: result.value.totalElements ?? next[counterName]
      };
    }, { ...current }));
  }

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
      loadWorkspaceCounters({
        includeAlerts: workspacePage !== "fraudTransaction",
        includeTransactions: workspacePage !== "transactionScoring"
      });
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

  function changeSession(nextSession) {
    setSession(normalizeSession(nextSession));
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

  function closeAlert() {
    clearSelection();
  }

  function closeFraudCase() {
    clearSelection();
  }

  const detailMode = Boolean(selectedAlertId || selectedFraudCaseId);
  const workspaceTitle = WORKSPACE_PAGES[workspacePage]?.label || WORKSPACE_PAGES.analyst.label;

  if (handlingOidcCallback) {
    return (
      <div className="appShell">
        <main>
          <section className="panelStack" aria-label="OIDC callback">
            <article className="panel">
              <p className="eyebrow">OIDC callback</p>
              <h1>{callbackError ? "Sign-in callback failed" : "Completing sign-in"}</h1>
              <p>
                {callbackError
                  ? callbackError.message || "The configured OIDC provider did not complete the redirect."
                  : "Finishing the provider redirect before returning to the analyst console."}
              </p>
            </article>
          </section>
        </main>
      </div>
    );
  }

  return (
    <div className="appShell">
      <header className="appTopbar">
        <a className="appBrand" href="/" aria-label="Fraud Detection Platform home">
          <span className="brandMark" aria-hidden="true">FDP</span>
          <span>
            <strong>Fraud Detection Platform</strong>
            <small>Bank fraud operations</small>
          </span>
        </a>

        <nav className="primaryNav" aria-label="Primary workspace navigation">
          {Object.entries(WORKSPACE_PAGES).map(([page, item]) => (
            <a
              key={page}
              href={workspaceHref(page)}
              className={workspacePage === page ? "primaryNavActive" : ""}
              aria-current={workspacePage === page ? "page" : undefined}
              onClick={(event) => {
                event.preventDefault();
                navigateWorkspace(page);
              }}
            >
              {item.label}
            </a>
          ))}
        </nav>

        <div className="topbarActions">
          <button className="iconButton notificationButton" type="button" aria-label="Notifications">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 8-3 8h18s-3-1-3-8" />
              <path d="M13.73 21a2 2 0 0 1-3.46 0" />
            </svg>
            <span aria-hidden="true" />
          </button>
          <SessionSettingsMenu
            session={session}
            sessionState={sessionState}
            authProvider={authProvider}
            onSessionChange={changeSession}
          />
          <SessionBadge
            session={session}
            sessionState={sessionState}
            authProvider={authProvider}
            onSessionChange={changeSession}
            showDetails={false}
          />
        </div>
      </header>

      <header className={detailMode ? "appHeader appHeaderCompact" : "appHeader appHeaderLanding"}>
        <div className="brandBlock">
          <h1>{detailMode ? "Fraud Case" : workspaceTitle}</h1>
        </div>
      </header>

      <main>
        {selectedAlertId ? (
          <AlertDetailsPage
            alertId={selectedAlertId}
            alertSummary={selectedAlertSummary}
            session={session}
            apiClient={apiClient}
            onBack={closeAlert}
            onDecisionSubmitted={refreshDashboard}
          />
        ) : selectedFraudCaseId ? (
          <FraudCaseDetailsPage
            caseId={selectedFraudCaseId}
            session={session}
            apiClient={apiClient}
            onBack={closeFraudCase}
            onCaseUpdated={refreshDashboard}
          />
        ) : (
          <AlertsListPage
            workspacePage={workspacePage}
            workspaceCounters={workspaceCounters}
            canReadFraudCases={canReadFraudCases}
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
        )}
      </main>
    </div>
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
