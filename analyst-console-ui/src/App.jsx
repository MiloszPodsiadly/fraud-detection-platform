import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  listAlerts,
  listGovernanceAdvisories,
  getGovernanceAdvisoryAnalytics,
  getFraudCaseWorkQueueSummary,
  listScoredTransactions,
  getGovernanceAdvisoryAudit,
  recordGovernanceAdvisoryAudit,
  setApiSession
} from "./api/alertsApi.js";
import { getConfiguredAuthProvider } from "./auth/authProvider.js";
import { isOidcCallbackPath } from "./auth/oidcClient.js";
import { normalizeSession } from "./auth/session.js";
import { SESSION_STATES, getSessionStateForApiError, getSessionStateForProvider } from "./auth/sessionState.js";
import { SessionBadge, SessionSettingsMenu } from "./components/SessionBadge.jsx";
import { useFraudCaseWorkQueue } from "./fraudCases/useFraudCaseWorkQueue.js";
import { AlertDetailsPage } from "./pages/AlertDetailsPage.jsx";
import { AlertsListPage } from "./pages/AlertsListPage.jsx";
import { FraudCaseDetailsPage } from "./pages/FraudCaseDetailsPage.jsx";

const WORKSPACE_PAGES = {
  analyst: { label: "Fraud Case", path: "analyst" },
  fraudTransaction: { label: "Fraud Transaction", path: "fraud-transaction" },
  transactionScoring: { label: "Transaction Scoring", path: "transaction-scoring" },
  compliance: { label: "Compliance", path: "compliance" },
  reports: { label: "Reports", path: "reports" }
};

function getInitialAlertId() {
  return new URLSearchParams(window.location.search).get("alertId");
}

function getInitialFraudCaseId() {
  return new URLSearchParams(window.location.search).get("fraudCaseId");
}

function getInitialWorkspacePage() {
  const workspace = new URLSearchParams(window.location.search).get("workspace");
  return Object.entries(WORKSPACE_PAGES)
    .find(([, page]) => page.path === workspace)?.[0] || "analyst";
}

export default function App() {
  const authProvider = useMemo(() => getConfiguredAuthProvider(), []);
  const [workspacePage, setWorkspacePage] = useState(getInitialWorkspacePage);
  const [alertPage, setAlertPage] = useState({
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 10
  });
  const [alertPageRequest, setAlertPageRequest] = useState({ page: 0, size: 10 });
  const [transactionPage, setTransactionPage] = useState({
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 25
  });
  const [fraudCaseWorkQueueSummary, setFraudCaseWorkQueueSummary] = useState({
    totalFraudCases: 0
  });
  const [transactionPageRequest, setTransactionPageRequest] = useState({
    page: 0,
    size: 25,
    query: "",
    riskLevel: "ALL",
    status: "ALL"
  });
  const [advisoryQueue, setAdvisoryQueue] = useState({
    status: "UNAVAILABLE",
    count: 0,
    retention_limit: 0,
    advisory_events: []
  });
  const [advisoryQueueRequest, setAdvisoryQueueRequest] = useState({
    severity: "ALL",
    modelVersion: "",
    lifecycleStatus: "ALL",
    limit: 25
  });
  const [governanceAnalytics, setGovernanceAnalytics] = useState({
    status: "UNAVAILABLE",
    window: { from: null, to: null, days: 7 },
    totals: { advisories: 0, reviewed: 0, open: 0 },
    decision_distribution: {},
    lifecycle_distribution: {},
    review_timeliness: {
      status: "LOW_CONFIDENCE",
      time_to_first_review_p50_minutes: 0,
      time_to_first_review_p95_minutes: 0
    }
  });
  const [analyticsWindowDays, setAnalyticsWindowDays] = useState(7);
  const [selectedAlertId, setSelectedAlertId] = useState(getInitialAlertId);
  const [selectedFraudCaseId, setSelectedFraudCaseId] = useState(getInitialFraudCaseId);
  const [session, setSession] = useState(() => authProvider.getInitialSession());
  const [sessionState, setSessionState] = useState(() => getSessionStateForProvider(authProvider.getInitialSession(), authProvider));
  const [isAlertLoading, setIsAlertLoading] = useState(false);
  const [isTransactionLoading, setIsTransactionLoading] = useState(false);
  const [isFraudCaseSummaryLoading, setIsFraudCaseSummaryLoading] = useState(false);
  const [isGovernanceLoading, setIsGovernanceLoading] = useState(true);
  const [isAnalyticsLoading, setIsAnalyticsLoading] = useState(true);
  const [alertError, setAlertError] = useState(null);
  const [transactionError, setTransactionError] = useState(null);
  const [fraudCaseSummaryError, setFraudCaseSummaryError] = useState(null);
  const [governanceError, setGovernanceError] = useState(null);
  const [analyticsError, setAnalyticsError] = useState(null);
  const [governanceAuditHistories, setGovernanceAuditHistories] = useState({});
  const [callbackError, setCallbackError] = useState(null);
  const handlingOidcCallback = authProvider.kind === "oidc" && isOidcCallbackPath();
  const [sessionBootstrapPending, setSessionBootstrapPending] = useState(authProvider.kind === "oidc");
  const skipNextOidcBootstrapRef = useRef(false);
  const alertRequestSeqRef = useRef(0);
  const transactionRequestSeqRef = useRef(0);
  const fraudCaseSummaryRequestSeqRef = useRef(0);
  const workQueueEnabled = workspacePage === "analyst"
    && !handlingOidcCallback
    && !sessionBootstrapPending
    && !shouldBlockDashboardFetch(sessionState);
  const handleWorkQueueSessionError = useCallback((apiError) => {
    setSessionState(getSessionStateForApiError(session, apiError) || getSessionStateForProvider(session, authProvider));
  }, [authProvider, session]);
  const fraudCaseWorkQueueState = useFraudCaseWorkQueue({
    enabled: workQueueEnabled,
    session,
    authProvider,
    onSessionError: handleWorkQueueSessionError
  });

  useEffect(() => {
    setApiSession(session, authProvider);
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
    if (authProvider.kind !== "oidc" || handlingOidcCallback || typeof authProvider.refreshSession !== "function") {
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
    setIsAlertLoading(false);
    setIsTransactionLoading(false);
    setAlertError(null);
    setTransactionError(null);
    setFraudCaseSummaryError(null);
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
          setIsAlertLoading(false);
          setIsTransactionLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [authProvider, handlingOidcCallback]);

  useEffect(() => {
    if (handlingOidcCallback) {
      return;
    }
    if (sessionBootstrapPending) {
      return;
    }
    if (shouldBlockDashboardFetch(sessionState)) {
      setIsAlertLoading(false);
      return;
    }
    if (workspacePage !== "fraudTransaction") {
      setIsAlertLoading(false);
      return;
    }
    setApiSession(session, authProvider);
    loadAlertQueue(alertPageRequest);
  }, [authProvider, alertPageRequest, handlingOidcCallback, session, sessionBootstrapPending, sessionState?.status, workspacePage]);

  useEffect(() => {
    if (handlingOidcCallback || sessionBootstrapPending) {
      return;
    }
    if (shouldBlockDashboardFetch(sessionState)) {
      setIsTransactionLoading(false);
      return;
    }
    if (workspacePage !== "transactionScoring") {
      setIsTransactionLoading(false);
      return;
    }
    setApiSession(session, authProvider);
    loadTransactionStream(transactionPageRequest);
  }, [authProvider, transactionPageRequest, handlingOidcCallback, session, sessionBootstrapPending, sessionState?.status, workspacePage]);

  useEffect(() => {
    if (handlingOidcCallback || sessionBootstrapPending) {
      return;
    }
    if (shouldBlockDashboardFetch(sessionState)) {
      setIsFraudCaseSummaryLoading(false);
      return;
    }
    setApiSession(session, authProvider);
    loadFraudCaseSummary();
  }, [authProvider, handlingOidcCallback, session, sessionBootstrapPending, sessionState?.status]);

  useEffect(() => {
    if (handlingOidcCallback || sessionBootstrapPending) {
      return;
    }
    if (shouldBlockDashboardFetch(sessionState)) {
      setIsGovernanceLoading(false);
      return;
    }
    loadGovernanceQueue(advisoryQueueRequest);
  }, [advisoryQueueRequest, handlingOidcCallback, sessionBootstrapPending, sessionState?.status]);

  useEffect(() => {
    if (handlingOidcCallback || sessionBootstrapPending) {
      return;
    }
    if (shouldBlockDashboardFetch(sessionState)) {
      setIsAnalyticsLoading(false);
      return;
    }
    loadGovernanceAnalytics(analyticsWindowDays);
  }, [analyticsWindowDays, handlingOidcCallback, sessionBootstrapPending, sessionState?.status]);

  useEffect(() => {
    if (!handlingOidcCallback || typeof authProvider.completeLoginCallback !== "function") {
      return;
    }

    let cancelled = false;
    setIsAlertLoading(false);
    setIsTransactionLoading(false);
    setAlertError(null);
    setTransactionError(null);
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
      .finally(() => {
        if (!cancelled) {
          setIsAlertLoading(false);
          setIsTransactionLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [authProvider, handlingOidcCallback, session]);

  useEffect(() => {
    const handlePopState = () => {
      setSelectedAlertId(getInitialAlertId());
      setSelectedFraudCaseId(getInitialFraudCaseId());
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const selectedAlertSummary = useMemo(
    () => alertPage.content.find((alert) => alert.alertId === selectedAlertId),
    [alertPage.content, selectedAlertId]
  );

  async function loadAlertQueue(nextRequest = alertPageRequest) {
    const requestSeq = alertRequestSeqRef.current + 1;
    alertRequestSeqRef.current = requestSeq;
    setIsAlertLoading(true);
    setAlertError(null);
    try {
      const nextAlerts = await listAlerts(nextRequest);
      if (alertRequestSeqRef.current !== requestSeq) {
        return;
      }
      setAlertPage(nextAlerts);
    } catch (apiError) {
      if (alertRequestSeqRef.current !== requestSeq) {
        return;
      }
      setAlertError(apiError);
      setSessionState(getSessionStateForApiError(session, apiError) || getSessionStateForProvider(session, authProvider));
    } finally {
      if (alertRequestSeqRef.current === requestSeq) {
        setIsAlertLoading(false);
      }
    }
  }

  async function loadTransactionStream(nextRequest = transactionPageRequest) {
    const requestSeq = transactionRequestSeqRef.current + 1;
    transactionRequestSeqRef.current = requestSeq;
    setIsTransactionLoading(true);
    setTransactionError(null);
    try {
      const nextTransactionPage = await listScoredTransactions(nextRequest);
      if (transactionRequestSeqRef.current !== requestSeq) {
        return;
      }
      setTransactionPage(nextTransactionPage);
    } catch (apiError) {
      if (transactionRequestSeqRef.current !== requestSeq) {
        return;
      }
      setTransactionError(apiError);
      setSessionState(getSessionStateForApiError(session, apiError) || getSessionStateForProvider(session, authProvider));
    } finally {
      if (transactionRequestSeqRef.current === requestSeq) {
        setIsTransactionLoading(false);
      }
    }
  }

  async function loadFraudCaseSummary() {
    const requestSeq = fraudCaseSummaryRequestSeqRef.current + 1;
    fraudCaseSummaryRequestSeqRef.current = requestSeq;
    setIsFraudCaseSummaryLoading(true);
    setFraudCaseSummaryError(null);
    try {
      const nextFraudCaseSummary = await getFraudCaseWorkQueueSummary();
      if (fraudCaseSummaryRequestSeqRef.current !== requestSeq) {
        return;
      }
      setFraudCaseWorkQueueSummary(nextFraudCaseSummary);
    } catch (apiError) {
      if (fraudCaseSummaryRequestSeqRef.current !== requestSeq) {
        return;
      }
      setFraudCaseSummaryError(apiError);
    } finally {
      if (fraudCaseSummaryRequestSeqRef.current === requestSeq) {
        setIsFraudCaseSummaryLoading(false);
      }
    }
  }

  async function loadGovernanceQueue(nextRequest = advisoryQueueRequest) {
    setIsGovernanceLoading(true);
    setGovernanceError(null);
    try {
      const nextQueue = await listGovernanceAdvisories(nextRequest);
      setAdvisoryQueue(nextQueue);
      await loadGovernanceAuditHistories(nextQueue.advisory_events || []);
    } catch (apiError) {
      setGovernanceError(apiError);
      setGovernanceAuditHistories({});
    } finally {
      setIsGovernanceLoading(false);
    }
  }

  async function loadGovernanceAnalytics(windowDays = analyticsWindowDays) {
    setIsAnalyticsLoading(true);
    setAnalyticsError(null);
    try {
      const nextAnalytics = await getGovernanceAdvisoryAnalytics({ windowDays });
      setGovernanceAnalytics(nextAnalytics);
    } catch (apiError) {
      setAnalyticsError(apiError);
    } finally {
      setIsAnalyticsLoading(false);
    }
  }

  async function loadGovernanceAuditHistories(events) {
    const histories = {};
    await Promise.all(events.map(async (event) => {
      try {
        histories[event.event_id] = await getGovernanceAdvisoryAudit(event.event_id);
      } catch (apiError) {
        histories[event.event_id] = {
          advisory_event_id: event.event_id,
          status: "UNAVAILABLE",
          audit_events: [],
          error: apiError.message
        };
      }
    }));
    setGovernanceAuditHistories(histories);
  }

  async function recordGovernanceAudit(eventId, audit) {
    await recordGovernanceAdvisoryAudit(eventId, audit);
    const nextHistory = await getGovernanceAdvisoryAudit(eventId);
    setGovernanceAuditHistories((current) => ({
      ...current,
      [eventId]: nextHistory
    }));
    const latestDecision = nextHistory.audit_events?.[0]?.decision || "OPEN";
    setAdvisoryQueue((current) => ({
      ...current,
      advisory_events: current.advisory_events
        .map((event) => (event.event_id === eventId ? { ...event, lifecycle_status: latestDecision } : event))
        .filter((event) => advisoryQueueRequest.lifecycleStatus === "ALL" || event.lifecycle_status === advisoryQueueRequest.lifecycleStatus)
    }));
    loadGovernanceAnalytics(analyticsWindowDays);
  }

  function refreshDashboard() {
    if (shouldBlockDashboardFetch(sessionState)) {
      setAlertError(null);
      setTransactionError(null);
      setFraudCaseSummaryError(null);
      setIsAlertLoading(false);
      setIsTransactionLoading(false);
      setIsFraudCaseSummaryLoading(false);
      setIsGovernanceLoading(false);
      setIsAnalyticsLoading(false);
      return;
    }
    if (workspacePage === "fraudTransaction") {
      loadAlertQueue(alertPageRequest);
    }
    if (workspacePage === "transactionScoring") {
      loadTransactionStream(transactionPageRequest);
    }
    loadFraudCaseSummary();
    fraudCaseWorkQueueState.refreshFirstSlice();
    loadGovernanceQueue(advisoryQueueRequest);
    loadGovernanceAnalytics(analyticsWindowDays);
  }

  function changeSession(nextSession) {
    setSession(normalizeSession(nextSession));
  }

  function changeTransactionPage(page) {
    setTransactionPageRequest((current) => ({ ...current, page: Math.min(Math.max(Number(page) || 0, 0), 1000) }));
  }

  function changeTransactionPageSize(size) {
    setTransactionPageRequest((current) => ({ ...current, page: 0, size: Math.min(Math.max(Number(size) || 25, 1), 100) }));
  }

  function changeTransactionFilters(filters) {
    setTransactionPageRequest((current) => ({
      ...current,
      ...filters,
      page: 0
    }));
  }

  function changeAlertPage(page) {
    setAlertPageRequest((current) => ({ ...current, page }));
  }

  function changeAlertPageSize(size) {
    setAlertPageRequest({ page: 0, size });
  }

  function openAlert(alertId) {
    const nextUrl = `${window.location.pathname}?alertId=${encodeURIComponent(alertId)}`;
    window.history.pushState({}, "", nextUrl);
    setSelectedAlertId(alertId);
    setSelectedFraudCaseId(null);
  }

  function closeAlert() {
    window.history.pushState({}, "", window.location.pathname);
    setSelectedAlertId(null);
  }

  function openFraudCase(caseId) {
    const nextUrl = `${window.location.pathname}?fraudCaseId=${encodeURIComponent(caseId)}`;
    window.history.pushState({}, "", nextUrl);
    setSelectedFraudCaseId(caseId);
    setSelectedAlertId(null);
  }

  function closeFraudCase() {
    window.history.pushState({}, "", window.location.pathname);
    setSelectedFraudCaseId(null);
  }

  function changeWorkspacePage(page) {
    const nextPage = WORKSPACE_PAGES[page] ? page : "analyst";
    const params = new URLSearchParams(window.location.search);
    params.delete("alertId");
    params.delete("fraudCaseId");
    if (nextPage === "analyst") {
      params.delete("workspace");
    } else {
      params.set("workspace", WORKSPACE_PAGES[nextPage].path);
    }
    const query = params.toString();
    window.history.pushState({}, "", `${window.location.pathname}${query ? `?${query}` : ""}`);
    setSelectedAlertId(null);
    setSelectedFraudCaseId(null);
    setWorkspacePage(nextPage);
  }

  function workspaceHref(page) {
    return page === "analyst" ? "/" : `/?workspace=${WORKSPACE_PAGES[page].path}`;
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
                changeWorkspacePage(page);
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
            onBack={closeAlert}
            onDecisionSubmitted={refreshDashboard}
          />
        ) : selectedFraudCaseId ? (
          <FraudCaseDetailsPage
            caseId={selectedFraudCaseId}
            session={session}
            onBack={closeFraudCase}
            onCaseUpdated={refreshDashboard}
          />
        ) : (
          <AlertsListPage
            workspacePage={workspacePage}
            alertPage={alertPage}
            fraudCaseTotalElements={fraudCaseWorkQueueSummary.totalFraudCases}
            fraudCaseWorkQueue={fraudCaseWorkQueueState.queue}
            fraudCaseSummaryError={fraudCaseSummaryError}
            isFraudCaseSummaryLoading={isFraudCaseSummaryLoading}
            fraudCaseWorkQueueRequest={fraudCaseWorkQueueState.committedFilters}
            fraudCaseWorkQueueDraftFilters={fraudCaseWorkQueueState.draftFilters}
            fraudCaseWorkQueueWarning={fraudCaseWorkQueueState.warning}
            fraudCaseWorkQueueFilterError={fraudCaseWorkQueueState.filterError}
            fraudCaseWorkQueueLastRefreshedAt={fraudCaseWorkQueueState.lastRefreshedAt}
            onWorkspaceChange={changeWorkspacePage}
            transactionPage={transactionPage}
            transactionPageRequest={transactionPageRequest}
            advisoryQueue={advisoryQueue}
            advisoryQueueRequest={advisoryQueueRequest}
            governanceAnalytics={governanceAnalytics}
            analyticsWindowDays={analyticsWindowDays}
            isLoading={workspacePage === "transactionScoring" ? isTransactionLoading : isAlertLoading}
            isFraudCaseWorkQueueLoading={fraudCaseWorkQueueState.isLoading}
            isGovernanceLoading={isGovernanceLoading}
            isAnalyticsLoading={isAnalyticsLoading}
            error={workspacePage === "transactionScoring" ? transactionError : alertError}
            fraudCaseWorkQueueError={fraudCaseWorkQueueState.error}
            governanceError={governanceError}
            analyticsError={analyticsError}
            governanceAuditHistories={governanceAuditHistories}
            session={session}
            sessionState={sessionState}
            onRetry={refreshDashboard}
            onFraudCaseSummaryRetry={loadFraudCaseSummary}
            onGovernanceRetry={() => loadGovernanceQueue(advisoryQueueRequest)}
            onAnalyticsRetry={() => loadGovernanceAnalytics(analyticsWindowDays)}
            onAdvisoryQueueRequestChange={setAdvisoryQueueRequest}
            onFraudCaseWorkQueueDraftChange={fraudCaseWorkQueueState.updateDraftFilter}
            onFraudCaseWorkQueueApplyFilters={fraudCaseWorkQueueState.applyFilters}
            onFraudCaseWorkQueueResetFilters={fraudCaseWorkQueueState.resetFilters}
            onFraudCaseWorkQueueRetry={fraudCaseWorkQueueState.refreshFirstSlice}
            onFraudCaseWorkQueueRefreshFirstSlice={fraudCaseWorkQueueState.refreshFirstSlice}
            onFraudCaseWorkQueueLoadMore={fraudCaseWorkQueueState.loadMore}
            onAnalyticsWindowDaysChange={setAnalyticsWindowDays}
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
