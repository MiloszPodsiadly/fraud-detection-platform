import { isAuthenticated } from "./session.js";
import { authHeadersForSession } from "./authHeaders.js";

export const SESSION_STATES = {
  LOADING: "loading",
  AUTHENTICATED: "authenticated",
  UNAUTHENTICATED: "unauthenticated",
  EXPIRED: "expired",
  ACCESS_DENIED: "access_denied",
  AUTH_ERROR: "auth_error"
};

export function getSessionStateForProvider(session, authProvider) {
  const providerState = normalizeProviderState(authProvider?.getSessionState?.(session));
  const hasAuthorizationHeader = Boolean(authHeadersForSession(authProvider, session).Authorization);

  if (
    providerState?.status === SESSION_STATES.UNAUTHENTICATED &&
    isAuthenticated(session) &&
    hasAuthorizationHeader
  ) {
    return {
      status: SESSION_STATES.AUTHENTICATED,
      expiresAt: providerState.expiresAt || null
    };
  }

  if (providerState) {
    return providerState;
  }

  if (authProvider?.kind === "oidc" && !hasAuthorizationHeader) {
    return { status: SESSION_STATES.UNAUTHENTICATED };
  }

  return {
    status: isAuthenticated(session) ? SESSION_STATES.AUTHENTICATED : SESSION_STATES.UNAUTHENTICATED
  };
}

export function getSessionStateForApiError(session, error) {
  if (error?.status === 401) {
    return {
      status: isAuthenticated(session) ? SESSION_STATES.EXPIRED : SESSION_STATES.UNAUTHENTICATED
    };
  }

  if (error?.status === 403) {
    return { status: SESSION_STATES.ACCESS_DENIED };
  }

  return null;
}

export function getSessionStateDescription(sessionState, authProvider) {
  switch (sessionState?.status) {
    case SESSION_STATES.LOADING:
      return "Loading session state...";
    case SESSION_STATES.EXPIRED:
      return authProvider?.kind === "oidc"
        ? "The provider session expired or no longer has a usable access token."
        : "The current session is no longer accepted by the API.";
    case SESSION_STATES.ACCESS_DENIED:
      return "The current session is active, but this view requires more authority.";
    case SESSION_STATES.AUTH_ERROR:
      return "The configured auth provider could not supply a valid session.";
    default:
      return authProvider?.unauthenticatedDescription || "";
  }
}

function normalizeProviderState(sourceState) {
  const status = sourceState?.status;
  if (!status) {
    return null;
  }

  if (Object.values(SESSION_STATES).includes(status)) {
    return {
      status,
      expiresAt: sourceState.expiresAt || null
    };
  }

  return { status: SESSION_STATES.AUTH_ERROR };
}
