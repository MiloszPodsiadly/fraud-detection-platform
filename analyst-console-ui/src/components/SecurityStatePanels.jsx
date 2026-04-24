import { authorityLabel, isAuthenticated } from "../auth/session.js";
import { SESSION_STATES } from "../auth/sessionState.js";
import { LoadingPanel } from "./LoadingPanel.jsx";

export function UnauthorizedPanel({ onRetry }) {
  return (
    <div className="statePanel securityStatePanel unauthorizedPanel">
      <span className="securityStateIcon">!</span>
      <h3>Session required</h3>
      <p>No analyst session is currently active. Sign in with the configured provider, then retry this workspace.</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function AccessDeniedPanel({ onRetry }) {
  return (
    <div className="statePanel securityStatePanel accessDeniedPanel">
      <span className="securityStateIcon">!</span>
      <h3>Access denied</h3>
      <p>Your analyst session is active, but it does not include the authority required for this view or action.</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function SessionExpiredPanel({ onRetry }) {
  return (
    <div className="statePanel securityStatePanel unauthorizedPanel">
      <span className="securityStateIcon">!</span>
      <h3>Session expired</h3>
      <p>The current analyst session is no longer valid for API access. Sign in again to restore bearer access, then retry.</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function AuthErrorPanel({ onRetry }) {
  return (
    <div className="statePanel securityStatePanel accessDeniedPanel">
      <span className="securityStateIcon">!</span>
      <h3>Authentication provider error</h3>
      <p>The configured auth provider could not supply a usable analyst session. Check the local provider setup or sign in flow, then retry.</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function SessionStatePanel({ sessionState, onRetry }) {
  switch (sessionState?.status) {
    case SESSION_STATES.LOADING:
      return <LoadingPanel label="Checking analyst session..." />;
    case SESSION_STATES.UNAUTHENTICATED:
      return <UnauthorizedPanel onRetry={onRetry} />;
    case SESSION_STATES.EXPIRED:
      return <SessionExpiredPanel onRetry={onRetry} />;
    case SESSION_STATES.ACCESS_DENIED:
      return <AccessDeniedPanel onRetry={onRetry} />;
    case SESSION_STATES.AUTH_ERROR:
      return <AuthErrorPanel onRetry={onRetry} />;
    default:
      return null;
  }
}

export function PermissionNotice({ session, authority, action }) {
  if (!isAuthenticated(session)) {
    return (
      <div className="permissionNotice permissionNoticeBlocked">
        <strong>Session required</strong>
        <span>An active analyst session is required before {action}.</span>
      </div>
    );
  }

  return (
    <div className="permissionNotice permissionNoticeBlocked">
      <strong>Insufficient permission</strong>
      <span>This action requires {authorityLabel(authority)} in the active analyst session.</span>
    </div>
  );
}
