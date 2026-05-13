import { afterEach, describe, expect, it, vi } from "vitest";
import { createBffAuthProvider, createDemoAuthProvider, createOidcAuthProvider } from "./authProvider.js";
import { authHeadersForSession } from "./authHeaders.js";
import { createOidcSessionSource, normalizeOidcSessionSnapshot } from "./oidcSessionSource.js";
import { SESSION_STATES } from "./sessionState.js";

describe("authProvider", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps demo as the editable default provider", () => {
    const provider = createDemoAuthProvider();

    expect(provider.kind).toBe("demo");
    expect(provider.supportsSessionEditing).toBe(true);
    expect(provider.getSessionState({ userId: "", roles: [] })).toEqual({
      status: SESSION_STATES.UNAUTHENTICATED
    });
    expect(authHeadersForSession(provider, {
      userId: "analyst-1",
      roles: ["REVIEWER"],
      extraAuthorities: [],
      authorities: ["fraud-case:update"]
    })).toMatchObject({
      "X-Demo-User-Id": "analyst-1",
      "X-Demo-Roles": "REVIEWER"
    });
  });

  it("prepares oidc provider around a token/session source boundary", async () => {
    const oidcClient = {
      getUser: async () => ({
        access_token: "oidc-token",
        expires_at: 2_000_000_000,
        profile: {
          sub: "oidc-user-1",
          groups: ["fraud-analyst"]
        }
      }),
      beginLogin: async () => "redirected",
      completeLoginCallback: async () => ({
        access_token: "oidc-token",
        expires_at: 2_000_000_000,
        profile: {
          sub: "oidc-user-1",
          groups: ["fraud-analyst"]
        }
      }),
      beginLogout: async () => "logout-started",
      hasConfiguration: () => true
    };
    const sessionSource = createOidcSessionSource(oidcClient, normalizeOidcSessionSnapshot({
      accessToken: "oidc-token",
      profile: {
        sub: "oidc-user-1",
        roles: ["ANALYST"],
        authorities: ["alert:read"]
      }
    }));
    const clearSpy = vi.spyOn(sessionSource, "clear");
    const provider = createOidcAuthProvider(sessionSource, oidcClient);

    expect(provider.kind).toBe("oidc");
    expect(provider.supportsSessionEditing).toBe(false);
    expect(provider.getInitialSession()).toMatchObject({
      userId: "oidc-user-1",
      roles: ["ANALYST"]
    });
    expect(authHeadersForSession(provider)).toEqual({
      Authorization: "Bearer oidc-token"
    });
    expect(provider.getSessionState()).toEqual({
      status: SESSION_STATES.AUTHENTICATED,
      expiresAt: null
    });
    await expect(provider.refreshSession()).resolves.toMatchObject({
      userId: "oidc-user-1",
      roles: ["ANALYST"]
    });
    await expect(provider.beginLogin()).resolves.toBe("redirected");
    expect(clearSpy).toHaveBeenCalledTimes(1);
    await expect(provider.completeLoginCallback()).resolves.toMatchObject({
      userId: "oidc-user-1",
      roles: ["ANALYST"]
    });
    await expect(provider.beginLogout()).resolves.toBe("logout-started");
    expect(clearSpy).toHaveBeenCalledTimes(2);
    expect(provider.hasLoginConfiguration()).toBe(true);
  });

  it("prepares bff provider without exposing bearer auth headers", async () => {
    const fetchSession = vi.fn()
      .mockResolvedValueOnce({
        authenticated: true,
        userId: "server-user-1",
        roles: ["FRAUD_OPS_ADMIN"],
        authorities: ["alert:read"],
        csrf: {
          headerName: "X-CSRF-TOKEN",
          token: "csrf-1"
        }
      });
    const provider = createBffAuthProvider(fetchSession);

    expect(provider.kind).toBe("bff");
    expect(provider.requiresSessionBootstrap).toBe(true);
    expect(provider.supportsSessionEditing).toBe(false);
    expect(authHeadersForSession(provider)).toEqual({});

    await expect(provider.refreshSession()).resolves.toMatchObject({
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"]
    });
    expect(authHeadersForSession(provider)).toEqual({
      "X-CSRF-TOKEN": "csrf-1"
    });
    expect(authHeadersForSession(provider).Authorization).toBeUndefined();
    expect(provider.getSessionState()).toEqual({
      status: SESSION_STATES.AUTHENTICATED
    });
  });

  it("redirects bff logout through the provider logout endpoint", async () => {
    const fetchSession = vi.fn().mockResolvedValue({
      authenticated: true,
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["alert:read"],
      csrf: {
        headerName: "X-CSRF-TOKEN",
        token: "csrf-1"
      }
    });
    const navigate = vi.fn();
    const logoutFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        logoutUrl: "http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout?client_id=analyst-console-ui"
      })
    });
    vi.stubGlobal("fetch", logoutFetch);
    const provider = createBffAuthProvider(fetchSession, navigate);

    await provider.refreshSession();
    await provider.beginLogout();

    expect(logoutFetch).toHaveBeenCalledWith("/bff/logout", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "X-CSRF-TOKEN": "csrf-1"
      }
    });
    expect(logoutFetch.mock.calls[0][1].headers.Authorization).toBeUndefined();
    expect(navigate).toHaveBeenCalledWith(
      "http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout?client_id=analyst-console-ui"
    );
  });
});
