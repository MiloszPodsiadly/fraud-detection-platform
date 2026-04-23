import { authorityLabel, isAuthenticated } from "../auth/session.js";

export function UnauthorizedPanel({ onRetry }) {
  return (
    <div className="statePanel securityStatePanel unauthorizedPanel">
      <span className="securityStateIcon">!</span>
      <h3>Session required</h3>
      <p>No authenticated analyst session is active. Select a local demo user and role in the session panel, then retry.</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function AccessDeniedPanel({ onRetry }) {
  return (
    <div className="statePanel securityStatePanel accessDeniedPanel">
      <span className="securityStateIcon">!</span>
      <h3>Access denied</h3>
      <p>Your session is active, but this role does not include the authority required for this view.</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function PermissionNotice({ session, authority, action }) {
  if (!isAuthenticated(session)) {
    return (
      <div className="permissionNotice permissionNoticeBlocked">
        <strong>Session required</strong>
        <span>Select a local demo user before {action}.</span>
      </div>
    );
  }

  return (
    <div className="permissionNotice permissionNoticeBlocked">
      <strong>Insufficient permission</strong>
      <span>This action requires {authorityLabel(authority)}.</span>
    </div>
  );
}
