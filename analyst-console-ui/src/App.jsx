import { useEffect, useMemo, useRef, useState } from "react";
import { getConfiguredAuthProvider } from "./auth/authProvider.js";
import { isOidcCallbackPath } from "./auth/oidcClient.js";
import { normalizeSession } from "./auth/session.js";
import { SESSION_STATES, getSessionStateForProvider } from "./auth/sessionState.js";
import { SessionBadge, SessionSettingsMenu } from "./components/SessionBadge.jsx";
import { WorkspaceDashboardShell } from "./workspace/WorkspaceDashboardShell.jsx";
import { WorkspaceRuntimeProvider } from "./workspace/WorkspaceRuntimeProvider.jsx";
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
  const [session, setSession] = useState(() => authProvider.getInitialSession());
  const [sessionState, setSessionState] = useState(() => getSessionStateForProvider(authProvider.getInitialSession(), authProvider));
  const [callbackError, setCallbackError] = useState(null);
  const handlingOidcCallback = authProvider.kind === "oidc" && isOidcCallbackPath();
  const [sessionBootstrapPending, setSessionBootstrapPending] = useState(Boolean(authProvider.requiresSessionBootstrap) || authProvider.kind === "oidc");
  const skipNextOidcBootstrapRef = useRef(false);
  const sessionBoundaryKey = useMemo(() => sessionBoundaryKeyFor(session, authProvider), [authProvider, session]);
  const previousSessionBoundaryKeyRef = useRef(sessionBoundaryKey);
  const workspaceNavigationEnabled = !handlingOidcCallback
    && !sessionBootstrapPending
    && !shouldBlockDashboardFetch(sessionState);
  const detailSelectionPendingBoundaryReset = Boolean(selectedAlertId || selectedFraudCaseId)
    && previousSessionBoundaryKeyRef.current !== sessionBoundaryKey;

  useEffect(() => {
    authProvider.persistSession(session);
    setSessionState(getSessionStateForProvider(session, authProvider));
  }, [authProvider, session]);

  useEffect(() => {
    if (previousSessionBoundaryKeyRef.current === sessionBoundaryKey) {
      return;
    }
    previousSessionBoundaryKeyRef.current = sessionBoundaryKey;
    if (selectedAlertId || selectedFraudCaseId) {
      clearSelection();
    }
  }, [clearSelection, selectedAlertId, selectedFraudCaseId, sessionBoundaryKey]);

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

  function changeSession(nextSession) {
    setSession(normalizeSession(nextSession));
  }

  const detailMode = Boolean(selectedAlertId || selectedFraudCaseId) && !detailSelectionPendingBoundaryReset;
  const runtimeSelectedAlertId = detailSelectionPendingBoundaryReset ? null : selectedAlertId;
  const runtimeSelectedFraudCaseId = detailSelectionPendingBoundaryReset ? null : selectedFraudCaseId;
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
        <WorkspaceRuntimeProvider session={session} authProvider={authProvider} enabled={Boolean(session?.userId)}>
          <WorkspaceDashboardShell
            workspacePage={workspacePage}
            selectedAlertId={runtimeSelectedAlertId}
            selectedFraudCaseId={runtimeSelectedFraudCaseId}
            clearSelection={clearSelection}
            navigateWorkspace={navigateWorkspace}
            openAlert={openAlert}
            openFraudCase={openFraudCase}
            sessionState={sessionState}
            setSessionState={setSessionState}
          />
        </WorkspaceRuntimeProvider>
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

function sessionBoundaryKeyFor(session, authProvider) {
  const roles = Array.isArray(session?.roles) ? session.roles.join(",") : "";
  const authorities = Array.isArray(session?.authorities) ? session.authorities.join(",") : "";
  return [
    authProvider?.kind || "none",
    session?.userId || "",
    roles,
    authorities
  ].join(":");
}
