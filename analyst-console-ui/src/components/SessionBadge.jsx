import { useState } from "react";
import { DEMO_PROVIDER_FALLBACK } from "../auth/authProvider.js";
import { ROLE_AUTHORITIES, isAuthenticated } from "../auth/session.js";
import { SESSION_STATES, getSessionStateDescription } from "../auth/sessionState.js";

const ROLE_OPTIONS = ["READ_ONLY_ANALYST", "ANALYST", "REVIEWER", "FRAUD_OPS_ADMIN"];
const EMPTY_SESSION = { userId: "", roles: [], extraAuthorities: [] };

export function SessionBadge({ session, sessionState, authProvider, onSessionChange }) {
  const [providerActionError, setProviderActionError] = useState("");
  const lifecycleState = sessionState?.status || (isAuthenticated(session) ? SESSION_STATES.AUTHENTICATED : SESSION_STATES.UNAUTHENTICATED);
  const authenticated = lifecycleState === SESSION_STATES.AUTHENTICATED;
  const activeRole = session.roles[0] || "";
  const roleSummary = session.roles.join(", ") || "No role";
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
        <div className="sessionIdentityCopy">
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
          value={session.userId}
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
            <option key={role} value={role}>{role}</option>
          ))}
        </select>
      </label>

      {!provider.supportsSessionEditing && (
        <p className="sessionHint">
          Session values come from the configured auth provider. Login redirect is active; session hydration stays behind the provider seam.
        </p>
      )}

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
    </section>
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
