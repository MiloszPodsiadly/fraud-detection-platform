import { demoAuthHeaders, getInitialDemoSession, saveDemoSession } from "./demoSession.js";
import { createOidcClient } from "./oidcClient.js";
import { createOidcSessionSource, oidcAuthHeaders, snapshotFromOidcUser } from "./oidcSessionSource.js";
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
  if (AUTH_PROVIDER_KIND === "oidc") {
    return createOidcAuthProvider();
  }
  return createDemoAuthProvider();
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
