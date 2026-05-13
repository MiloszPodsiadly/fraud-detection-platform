import { demoAuthHeaders, getInitialDemoSession, saveDemoSession } from "./demoSession.js";
import { createOidcClient } from "./oidcClient.js";
import { createOidcSessionSource, oidcAuthHeaders, snapshotFromOidcUser } from "./oidcSessionSource.js";
import { normalizeSession } from "./session.js";
import { SESSION_STATES } from "./sessionState.js";

const AUTH_PROVIDER_KIND = (import.meta.env.VITE_AUTH_PROVIDER || "demo").trim().toLowerCase();
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

export function createBffAuthProvider(fetchSession = defaultFetchSession, navigate = defaultNavigate) {
  let snapshot = normalizeBffSessionSnapshot({});

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
      let logoutUrl = "/";
      try {
        const response = await fetch("/bff/logout", {
          method: "POST",
          credentials: "same-origin",
          headers: csrfHeaders(snapshot.csrf)
        });
        if (response.ok) {
          logoutUrl = normalizeLogoutUrl(await response.json().catch(() => ({})));
        }
      } finally {
        snapshot = normalizeBffSessionSnapshot({});
        navigate(logoutUrl);
      }
    },
    hasLoginConfiguration() {
      return true;
    },
    persistSession(session) {
      snapshot = normalizeBffSessionSnapshot({
        ...snapshot,
        session
      });
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
  const session = input.authenticated
    ? normalizeSession({
        userId: input.userId,
        roles: input.roles,
        extraAuthorities: input.authorities
      })
    : normalizeSession(input.session || {});
  return {
    session,
    csrf: normalizeCsrf(input.csrf),
    state: {
      status: session.userId ? SESSION_STATES.AUTHENTICATED : SESSION_STATES.UNAUTHENTICATED
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

function normalizeLogoutUrl(payload) {
  const logoutUrl = typeof payload?.logoutUrl === "string" ? payload.logoutUrl.trim() : "";
  if (!logoutUrl) {
    return "/";
  }
  if (logoutUrl.startsWith("/") || logoutUrl.startsWith("http://") || logoutUrl.startsWith("https://")) {
    return logoutUrl;
  }
  return "/";
}

function defaultNavigate(url) {
  window.location.assign(url);
}
