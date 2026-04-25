import { useEffect, useMemo, useRef, useState } from "react";
import { listAlerts, listFraudCases, listScoredTransactions, setApiSession } from "./api/alertsApi.js";
import { getConfiguredAuthProvider } from "./auth/authProvider.js";
import { isOidcCallbackPath } from "./auth/oidcClient.js";
import { normalizeSession } from "./auth/session.js";
import { SESSION_STATES, getSessionStateForApiError, getSessionStateForProvider } from "./auth/sessionState.js";
import { SessionBadge } from "./components/SessionBadge.jsx";
import { AlertDetailsPage } from "./pages/AlertDetailsPage.jsx";
import { AlertsListPage } from "./pages/AlertsListPage.jsx";
import { FraudCaseDetailsPage } from "./pages/FraudCaseDetailsPage.jsx";

function getInitialAlertId() {
  return new URLSearchParams(window.location.search).get("alertId");
}

function getInitialFraudCaseId() {
  return new URLSearchParams(window.location.search).get("fraudCaseId");
}

export default function App() {
  const authProvider = useMemo(() => getConfiguredAuthProvider(), []);
  const [alertPage, setAlertPage] = useState({
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 10
  });
  const [alertPageRequest, setAlertPageRequest] = useState({ page: 0, size: 10 });
  const [fraudCasePage, setFraudCasePage] = useState({
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 4
  });
  const [fraudCasePageRequest, setFraudCasePageRequest] = useState({ page: 0, size: 4 });
  const [transactionPage, setTransactionPage] = useState({
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 25
  });
  const [transactionPageRequest, setTransactionPageRequest] = useState({ page: 0, size: 25 });
  const [selectedAlertId, setSelectedAlertId] = useState(getInitialAlertId);
  const [selectedFraudCaseId, setSelectedFraudCaseId] = useState(getInitialFraudCaseId);
  const [session, setSession] = useState(() => authProvider.getInitialSession());
  const [sessionState, setSessionState] = useState(() => getSessionStateForProvider(authProvider.getInitialSession(), authProvider));
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [callbackError, setCallbackError] = useState(null);
  const handlingOidcCallback = authProvider.kind === "oidc" && isOidcCallbackPath();
  const [sessionBootstrapPending, setSessionBootstrapPending] = useState(authProvider.kind === "oidc");
  const skipNextOidcBootstrapRef = useRef(false);

  useEffect(() => {
    setApiSession(session, authProvider);
    authProvider.persistSession(session);
    setSessionState(getSessionStateForProvider(session, authProvider));
  }, [authProvider, session]);

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
    setIsLoading(true);
    setError(null);
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
          setIsLoading(false);
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
      setIsLoading(false);
      return;
    }
    setApiSession(session, authProvider);
    loadDashboard({ transaction: transactionPageRequest, alert: alertPageRequest, fraudCase: fraudCasePageRequest });
  }, [authProvider, transactionPageRequest, alertPageRequest, fraudCasePageRequest, handlingOidcCallback, session, sessionBootstrapPending]);

  useEffect(() => {
    if (!handlingOidcCallback || typeof authProvider.completeLoginCallback !== "function") {
      return;
    }

    let cancelled = false;
    setIsLoading(true);
    setError(null);
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
          setIsLoading(false);
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

  async function loadDashboard(nextRequests = { transaction: transactionPageRequest, alert: alertPageRequest, fraudCase: fraudCasePageRequest }) {
    setIsLoading(true);
    setError(null);
    try {
      const [nextAlerts, nextFraudCasePage, nextTransactionPage] = await Promise.all([
        listAlerts(nextRequests.alert),
        listFraudCases(nextRequests.fraudCase),
        listScoredTransactions(nextRequests.transaction)
      ]);
      setAlertPage(nextAlerts);
      setFraudCasePage(nextFraudCasePage);
      setTransactionPage(nextTransactionPage);
    } catch (apiError) {
      setError(apiError);
      setSessionState(getSessionStateForApiError(session, apiError) || getSessionStateForProvider(session, authProvider));
    } finally {
      setIsLoading(false);
    }
  }

  function refreshDashboard() {
    loadDashboard({ transaction: transactionPageRequest, alert: alertPageRequest, fraudCase: fraudCasePageRequest });
  }

  function changeSession(nextSession) {
    setSession(normalizeSession(nextSession));
  }

  function changeTransactionPage(page) {
    setTransactionPageRequest((current) => ({ ...current, page }));
  }

  function changeTransactionPageSize(size) {
    setTransactionPageRequest({ page: 0, size });
  }

  function changeAlertPage(page) {
    setAlertPageRequest((current) => ({ ...current, page }));
  }

  function changeAlertPageSize(size) {
    setAlertPageRequest({ page: 0, size });
  }

  function changeFraudCasePage(page) {
    setFraudCasePageRequest((current) => ({ ...current, page }));
  }

  function changeFraudCasePageSize(size) {
    setFraudCasePageRequest({ page: 0, size });
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
      <header className="hero">
        <div>
          <p className="eyebrow">Fraud operations</p>
          <h1>Analyst Console</h1>
          <p className="heroCopy">
            Internal workspace for watching legitimate and suspicious transactions, triaging
            high-risk alerts, and submitting analyst decisions.
          </p>
        </div>
        <div className="heroStats" aria-label="Alert summary">
          <div>
            <span>{transactionPage.totalElements}</span>
            <small>Scored transactions</small>
          </div>
          <div>
            <span>{alertPage.totalElements}</span>
            <small>Alert queue</small>
          </div>
          <div>
            <span>{fraudCasePage.totalElements}</span>
            <small>Fraud cases</small>
          </div>
        </div>
        <SessionBadge
          session={session}
          sessionState={sessionState}
          authProvider={authProvider}
          onSessionChange={changeSession}
        />
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
            alertPage={alertPage}
            fraudCasePage={fraudCasePage}
            transactionPage={transactionPage}
            isLoading={isLoading}
            error={error}
            sessionState={sessionState}
            onRetry={refreshDashboard}
            onTransactionPageChange={changeTransactionPage}
            onTransactionPageSizeChange={changeTransactionPageSize}
            onAlertPageChange={changeAlertPage}
            onAlertPageSizeChange={changeAlertPageSize}
            onFraudCasePageChange={changeFraudCasePage}
            onFraudCasePageSizeChange={changeFraudCasePageSize}
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
