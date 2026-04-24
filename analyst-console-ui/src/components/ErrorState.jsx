import { securityErrorKind, securityErrorMessage } from "../auth/securityErrors.js";
import { AccessDeniedPanel, AuthErrorPanel, SessionExpiredPanel, UnauthorizedPanel } from "./SecurityStatePanels.jsx";

export function ErrorState({ error, message, onRetry }) {
  const displayMessage = securityErrorMessage(error || message);
  const kind = securityErrorKind(error || message);

  if (kind === "unauthorized") {
    return <UnauthorizedPanel onRetry={onRetry} />;
  }

  if (kind === "forbidden") {
    return <AccessDeniedPanel onRetry={onRetry} />;
  }

  if (kind === "expired") {
    return <SessionExpiredPanel onRetry={onRetry} />;
  }

  if (kind === "auth_error") {
    return <AuthErrorPanel onRetry={onRetry} />;
  }

  return (
    <div className="statePanel errorPanel">
      <h3>Unable to load data</h3>
      <p>{displayMessage}</p>
      <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>
    </div>
  );
}
