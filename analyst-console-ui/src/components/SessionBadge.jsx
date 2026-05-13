import { useState } from "react";
import { DEMO_PROVIDER_FALLBACK } from "../auth/authProvider.js";
import { ROLE_AUTHORITIES, isAuthenticated } from "../auth/session.js";
import { SESSION_STATES, getSessionStateDescription } from "../auth/sessionState.js";

const ROLE_OPTIONS = ["READ_ONLY_ANALYST", "ANALYST", "REVIEWER", "FRAUD_OPS_ADMIN"];
const EMPTY_SESSION = { userId: "", roles: [], extraAuthorities: [] };

export function SessionBadge({ session, sessionState, authProvider, onSessionChange, showDetails = true }) {
  const [providerActionError, setProviderActionError] = useState("");
  const lifecycleState = sessionState?.status || (isAuthenticated(session) ? SESSION_STATES.AUTHENTICATED : SESSION_STATES.UNAUTHENTICATED);
  const authenticated = lifecycleState === SESSION_STATES.AUTHENTICATED;
  const roleSummary = displayRoleSummary(session.roles);
  const authorityCount = session.authorities?.length || 0;
  const provider = authProvider || DEMO_PROVIDER_FALLBACK;
  const oidcProvider = provider.kind === "oidc";
  const description = authenticated
    ? authenticatedDescription(roleSummary, authorityCount, provider)
    : getSessionStateDescription({ status: lifecycleState }, provider);
  const modeLabel = authenticated
    ? provider.authenticatedModeLabel
    : stateModeLabel(lifecycleState, provider);
  const modeSummary = provider.kind === "demo" ? "Demo auth mode" : "Provider-backed mode";

  async function startOidcLogin() {
    if (typeof provider.beginLogin !== "function") {
      return;
    }

    try {
      setProviderActionError("");
      await provider.beginLogin();
    } catch (error) {
      setProviderActionError(error instanceof Error ? error.message : "OIDC sign-in could not be started.");
    }
  }

  async function startOidcLogout() {
    if (typeof provider.beginLogout !== "function") {
      return;
    }

    try {
      setProviderActionError("");
      onSessionChange(EMPTY_SESSION);
      await provider.beginLogout();
    } catch (error) {
      setProviderActionError(error instanceof Error ? error.message : "OIDC sign-out could not be started.");
    }
  }

  return (
    <section className="sessionBadge" aria-label="Current session">
      <div className="sessionIdentity">
        <span className={authenticated ? "sessionDot sessionDotActive" : "sessionDot"} />
        {!authenticated && (
          <div className="sessionIdentityCopy">
            <strong>Not authenticated</strong>
            <small>{description}</small>
          </div>
        )}
        {authenticated && (
          <div className="sessionIdentityCopy">
            <strong>Authenticated</strong>
            <small>{roleSummary}</small>
          </div>
        )}
        {!authenticated && (
          <div className="sessionModeBlock">
            <span className="sessionModePill sessionModePillMuted">
              {modeLabel}
            </span>
            <small>{modeSummary}</small>
          </div>
        )}
      </div>

      {!authenticated && oidcProvider && typeof provider.beginLogin === "function" && (
        <button
          type="button"
          className="secondaryButton"
          onClick={startOidcLogin}
          disabled={!provider.hasLoginConfiguration?.()}
        >
          Sign in with OIDC
        </button>
      )}

      {authenticated && oidcProvider && typeof provider.beginLogout === "function" && (
        <button type="button" className="secondaryButton" onClick={startOidcLogout}>
          Sign out
        </button>
      )}

      {providerActionError && (
        <p className="sessionHint" role="alert">{providerActionError}</p>
      )}

      {showDetails && <details className="sessionDetails">
        <summary>Session details</summary>
        <SessionDetailsPanel session={session} sessionState={sessionState} provider={provider} onSessionChange={onSessionChange} />
      </details>}
    </section>
  );
}

export function SessionSettingsMenu({ session, sessionState, authProvider, onSessionChange }) {
  const provider = authProvider || DEMO_PROVIDER_FALLBACK;

  return (
    <details className="sessionDetails settingsMenu">
      <summary className="iconButton" aria-label="Session settings">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 15.5A3.5 3.5 0 1 0 12 8a3.5 3.5 0 0 0 0 7.5Z" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06A1.65 1.65 0 0 0 15 19.4a1.65 1.65 0 0 0-1 .6l-.06.06a2 2 0 1 1-3.88 0L10 20a1.65 1.65 0 0 0-1-.6 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-.6-1l-.06-.06a2 2 0 1 1 0-3.88L4 10a1.65 1.65 0 0 0 .6-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-.6l.06-.06a2 2 0 1 1 3.88 0L14 4a1.65 1.65 0 0 0 1 .6 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.08.36.28.7.6 1l.06.06a2 2 0 1 1 0 3.88L20 14c-.32.3-.52.64-.6 1Z" />
        </svg>
      </summary>
      <SessionDetailsPanel session={session} sessionState={sessionState} provider={provider} onSessionChange={onSessionChange} />
    </details>
  );
}

function SessionDetailsPanel({ session, sessionState, provider, onSessionChange }) {
  const lifecycleState = sessionState?.status || (isAuthenticated(session) ? SESSION_STATES.AUTHENTICATED : SESSION_STATES.UNAUTHENTICATED);
  const authenticated = lifecycleState === SESSION_STATES.AUTHENTICATED;
  const activeRole = authenticated || provider.supportsSessionEditing ? session.roles[0] || "" : "";
  const userId = authenticated || provider.supportsSessionEditing ? session.userId : "";
  const roleSummary = displayRoleSummary(session.roles);
  const authorityCount = session.authorities?.length || 0;
  const description = authenticated
    ? authenticatedDescription(roleSummary, authorityCount, provider)
    : getSessionStateDescription({ status: lifecycleState }, provider);
  const modeLabel = authenticated ? provider.authenticatedModeLabel : stateModeLabel(lifecycleState, provider);
  const modeSummary = provider.kind === "demo" ? "Demo auth mode" : "Provider-backed mode";

  function changeRole(event) {
    if (!provider.supportsSessionEditing) {
      return;
    }
    const nextRole = event.target.value;
    if (!nextRole) {
      onSessionChange(EMPTY_SESSION);
      return;
    }
    onSessionChange({
      userId: session.userId || "analyst.local",
      roles: [nextRole],
      extraAuthorities: []
    });
  }

  function changeUserId(event) {
    if (!provider.supportsSessionEditing) {
      return;
    }
    onSessionChange({
      userId: event.target.value,
      roles: session.roles.length > 0 ? session.roles : ["READ_ONLY_ANALYST"],
      extraAuthorities: session.extraAuthorities
    });
  }

  return (
    <div className="sessionDetailsPanel">
      <div className="sessionSettingsSummary">
        <span className={authenticated ? "sessionDot sessionDotActive" : "sessionDot"} />
        <div>
          <p className="eyebrow">{provider.label}</p>
          <strong>{authenticated ? session.userId : "Not authenticated"}</strong>
          <small>{description}</small>
        </div>
        <div className="sessionModeBlock">
          <span className={authenticated ? "sessionModePill" : "sessionModePill sessionModePillMuted"}>
            {modeLabel}
          </span>
          <small>{modeSummary}</small>
        </div>
      </div>

      <label>
        User
        <input
          value={userId}
          onChange={changeUserId}
          placeholder="analyst.local"
          disabled={!provider.supportsSessionEditing}
        />
      </label>

      <label>
        Role
        <select value={activeRole} onChange={changeRole} disabled={!provider.supportsSessionEditing}>
          <option value="">Unauthenticated</option>
          {ROLE_OPTIONS.map((role) => (
            <option key={role} value={role}>{displayRole(role)}</option>
          ))}
        </select>
      </label>

      {!provider.supportsSessionEditing && (
        <p className="sessionHint">
          Session values come from the configured auth provider. Login redirect is active; session hydration stays behind the provider.
        </p>
      )}

      {authenticated && (
        <div className="sessionFacts">
          <span className="sessionFact"><strong>User</strong> {session.userId}</span>
          <span className="sessionFact"><strong>Role</strong> {roleSummary}</span>
          <span className="sessionFact"><strong>Authorities</strong> {authorityCount}</span>
        </div>
      )}

      {authenticated && (
        <div className="authorityList" aria-label="Session authorities">
          {(ROLE_AUTHORITIES[activeRole] || session.authorities).map((authority) => (
            <span className="tag" key={authority}>{authority}</span>
          ))}
        </div>
      )}
    </div>
  );
}

function stateModeLabel(sessionState, provider) {
  switch (sessionState) {
    case SESSION_STATES.LOADING:
      return "loading";
    case SESSION_STATES.EXPIRED:
      return "expired";
    case SESSION_STATES.ACCESS_DENIED:
      return "forbidden";
    case SESSION_STATES.AUTH_ERROR:
      return "auth error";
    default:
      return provider.unauthenticatedModeLabel;
  }
}

function authenticatedDescription(roleSummary, authorityCount, provider) {
  const mode = provider.kind === "demo" ? "local demo session" : "provider-backed analyst session";
  const authorityLabel = authorityCount === 1 ? "authority" : "authorities";
  return `${roleSummary} access active via ${mode}. ${authorityCount} ${authorityLabel} available.`;
}

function displayRoleSummary(roles) {
  return roles.length > 0 ? roles.map(displayRole).join(", ") : "no role";
}

function displayRole(role) {
  switch (role) {
    case "READ_ONLY_ANALYST":
      return "readonly";
    case "ANALYST":
      return "analyst";
    case "REVIEWER":
      return "reviewer";
    case "FRAUD_OPS_ADMIN":
      return "opsadmin";
    default:
      return role ? role.toLowerCase().replaceAll("_", " ") : "no role";
  }
}
