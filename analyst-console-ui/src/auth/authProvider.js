import { demoAuthHeaders, getInitialDemoSession, saveDemoSession } from "./demoSession.js";
import { createOidcClient } from "./oidcClient.js";
import { createOidcSessionSource, oidcAuthHeaders, snapshotFromOidcUser } from "./oidcSessionSource.js";
import { normalizeSession } from "./session.js";
import { SESSION_STATES } from "./sessionState.js";

const AUTH_PROVIDER_KIND = resolveAuthProviderKind(import.meta.env);
export const DEMO_PROVIDER_FALLBACK = Object.freeze({
  kind: "demo",
  label: "Local demo session",
  supportsSessionEditing: true,
  authenticatedModeLabel: "local/dev only",
  unauthenticatedModeLabel: "headers off",
  unauthenticatedDescription: "Demo auth headers are disabled"
});

export function getConfiguredAuthProvider() {
  if (AUTH_PROVIDER_KIND === "bff") {
    return createBffAuthProvider();
  }
  if (AUTH_PROVIDER_KIND === "oidc") {
    return createOidcAuthProvider();
  }
  return createDemoAuthProvider();
}

export function resolveAuthProviderKind(env = {}) {
  const configured = typeof env.VITE_AUTH_PROVIDER === "string" ? env.VITE_AUTH_PROVIDER.trim().toLowerCase() : "";
  const allowDemo = env.VITE_ALLOW_DEMO_AUTH === "true";
  const productionLike = env.PROD === true || env.MODE === "production";
  if (configured === "bff" || configured === "oidc") {
    return configured;
  }
  if (configured === "demo") {
    if (productionLike && !allowDemo) {
      throw new Error("Demo auth provider is not allowed for production-like frontend builds.");
    }
    return "demo";
  }
  if (configured) {
    if (productionLike) {
      throw new Error(`Unsupported production auth provider: ${configured}.`);
    }
    return "demo";
  }
  return productionLike ? "bff" : "demo";
}

export function createBffAuthProvider(fetchSession = defaultFetchSession, navigate = defaultNavigate) {
  let snapshot = normalizeBffSessionSnapshot({});
  let logoutError = null;

  return {
    kind: "bff",
    label: "BFF session",
    supportsSessionEditing: false,
    requiresSessionBootstrap: true,
    authenticatedModeLabel: "server session",
    unauthenticatedModeLabel: "sign-in required",
    unauthenticatedDescription: "Use the configured OIDC sign-in flow to start a server-backed analyst session.",
    getInitialSession() {
      return snapshot.session;
    },
    getSessionState() {
      return snapshot.state;
    },
    async refreshSession() {
      snapshot = normalizeBffSessionSnapshot(await fetchSession());
      return snapshot.session;
    },
    beginLogin() {
      navigate("/oauth2/authorization/keycloak");
      return Promise.resolve();
    },
    async beginLogout() {
      logoutError = null;
      const headers = csrfHeaders(snapshot.csrf);
      if (snapshot.session.userId && Object.keys(headers).length === 0) {
        logoutError = new Error("Logout requires a current CSRF token.");
        throw logoutError;
      }
      const response = await fetch("/bff/logout", {
        method: "POST",
        credentials: "same-origin",
        headers
      });
      if (!response.ok) {
        logoutError = new Error(`Logout request failed with status ${response.status}.`);
        throw logoutError;
      }
      const logoutUrl = normalizeLogoutUrl(await response.json().catch(() => ({})));
      snapshot = normalizeBffSessionSnapshot({});
      navigate(logoutUrl);
    },
    getLogoutError() {
      return logoutError;
    },
    hasLoginConfiguration() {
      return true;
    },
    persistSession(session) {
      snapshot = {
        ...snapshot,
        session: normalizeSession(session)
      };
    },
    getRequestHeaders() {
      return csrfHeaders(snapshot.csrf);
    }
  };
}

export function createDemoAuthProvider() {
  return {
    ...DEMO_PROVIDER_FALLBACK,
    getInitialSession() {
      return getInitialDemoSession();
    },
    getSessionState(session) {
      return {
        status: session?.userId ? SESSION_STATES.AUTHENTICATED : SESSION_STATES.UNAUTHENTICATED
      };
    },
    persistSession(session) {
      saveDemoSession(session);
    },
    getRequestHeaders(session) {
      return demoAuthHeaders(session);
    }
  };
}

export function createOidcAuthProvider(
  sessionSource,
  oidcClient = createOidcClient()
) {
  const source = sessionSource || createOidcSessionSource(oidcClient);

  return {
    kind: "oidc",
    label: "OIDC session",
    supportsSessionEditing: false,
    authenticatedModeLabel: "oidc",
    unauthenticatedModeLabel: "waiting for oidc",
    unauthenticatedDescription: "Use the configured OIDC sign-in flow to start a real provider session.",
    getInitialSession() {
      return source.getSession();
    },
    // Keep the provider contract vendor-neutral so a real SDK can plug in behind the session source seam.
    getSessionState() {
      return source.getState();
    },
    async refreshSession() {
      if (typeof source.refresh === "function") {
        return source.refresh();
      }

      replaceSourceFromOidcUser(source, await oidcClient.getUser());
      return source.getSession();
    },
    beginLogin() {
      source.clear?.();
      return oidcClient.beginLogin();
    },
    completeLoginCallback() {
      if (typeof source.completeLoginCallback === "function") {
        return source.completeLoginCallback();
      }

      return oidcClient.completeLoginCallback().then((user) => {
        replaceSourceFromOidcUser(source, user);
        return source.getSession();
      });
    },
    beginLogout() {
      source.clear?.();
      return oidcClient.beginLogout();
    },
    hasLoginConfiguration() {
      return oidcClient.hasConfiguration();
    },
    persistSession(session) {
      source.setSession(session);
    },
    getRequestHeaders() {
      return oidcAuthHeaders(source.getAccessToken());
    }
  };
}

function replaceSourceFromOidcUser(source, user) {
  source.replace?.(snapshotFromOidcUser(user));
}

async function defaultFetchSession() {
  const response = await fetch("/api/v1/session", {
    credentials: "same-origin",
    headers: {
      Accept: "application/json"
    }
  });
  if (!response.ok) {
    throw new Error(`Session request failed with status ${response.status}.`);
  }
  return response.json();
}

function normalizeBffSessionSnapshot(input = {}) {
  const hasSessionStatus = Object.prototype.hasOwnProperty.call(input, "sessionStatus");
  const sessionStatus = hasSessionStatus
    ? String(input.sessionStatus || "").trim().toUpperCase()
    : Object.keys(input).length === 0 ? "ANONYMOUS" : "";
  let session = normalizeSession({});
  let status = SESSION_STATES.AUTH_ERROR;

  if (sessionStatus === "AUTHENTICATED") {
    session = normalizeSession({
      userId: input.userId,
      roles: input.roles,
      extraAuthorities: input.authorities
    });
    status = session.userId ? SESSION_STATES.AUTHENTICATED : SESSION_STATES.AUTH_ERROR;
    if (!session.userId) {
      session = normalizeSession({});
    }
  } else if (sessionStatus === "ANONYMOUS") {
    status = SESSION_STATES.UNAUTHENTICATED;
  }

  return {
    session,
    csrf: normalizeCsrf(input.csrf),
    state: {
      status
    }
  };
}

function normalizeCsrf(csrf) {
  const headerName = typeof csrf?.headerName === "string" ? csrf.headerName.trim() : "";
  const token = typeof csrf?.token === "string" ? csrf.token.trim() : "";
  return headerName && token ? { headerName, token } : null;
}

function csrfHeaders(csrf) {
  if (!csrf?.headerName || !csrf?.token) {
    return {};
  }
  return {
    [csrf.headerName]: csrf.token
  };
}

export function normalizeLogoutUrl(payload) {
  // Frontend performs syntax/dangerous-scheme sanity checks only.
  // Backend BFF logout origin validation is the source of trust.
  const logoutUrl = typeof payload?.logoutUrl === "string" ? payload.logoutUrl.trim() : "";
  if (!logoutUrl) {
    throw new Error("Logout redirect URL is not trusted.");
  }
  if (logoutUrl.startsWith("//")) {
    throw new Error("Logout redirect URL is not trusted.");
  }
  if (logoutUrl.startsWith("/") && !logoutUrl.startsWith("//")) {
    return logoutUrl;
  }
  const baseUrl = typeof window !== "undefined" ? window.location.origin : "http://localhost:4173";
  const hasScheme = /^[a-z][a-z\d+.-]*:/i.test(logoutUrl);
  let parsed;
  try {
    parsed = new URL(logoutUrl, baseUrl);
  } catch {
    throw new Error("Logout redirect URL is not trusted.");
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error("Logout redirect URL is not trusted.");
  }
  if (!hasScheme) {
    return logoutUrl;
  }
  return parsed.toString();
}

function defaultNavigate(url) {
  window.location.assign(url);
}
