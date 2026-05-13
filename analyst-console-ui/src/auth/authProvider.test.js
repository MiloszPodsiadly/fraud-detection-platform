import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createBffAuthProvider,
  createDemoAuthProvider,
  createOidcAuthProvider,
  normalizeLogoutUrl,
  resolveAuthProviderKind
} from "./authProvider.js";
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
        sessionStatus: "AUTHENTICATED",
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

  it("uses backend bff sessionStatus as the session lifecycle source", async () => {
    const provider = createBffAuthProvider(vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "AUTHENTICATED",
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["alert:read"]
    }));

    await expect(provider.refreshSession()).resolves.toMatchObject({
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"]
    });
    expect(provider.getSessionState()).toEqual({
      status: SESSION_STATES.AUTHENTICATED
    });
  });

  it("fails closed when bff sessionStatus is authenticated but userId is missing", async () => {
    const provider = createBffAuthProvider(vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "AUTHENTICATED",
      userId: " ",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["alert:read"]
    }));

    await expect(provider.refreshSession()).resolves.toMatchObject({
      userId: ""
    });
    expect(provider.getSessionState()).toEqual({
      status: SESSION_STATES.AUTH_ERROR
    });
  });

  it("treats anonymous bff sessionStatus as unauthenticated without inferring from user fields", async () => {
    const provider = createBffAuthProvider(vi.fn().mockResolvedValue({
      authenticated: false,
      sessionStatus: "ANONYMOUS",
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["alert:read"]
    }));

    await provider.refreshSession();

    expect(provider.getInitialSession().userId).toBe("");
    expect(provider.getSessionState()).toEqual({
      status: SESSION_STATES.UNAUTHENTICATED
    });
  });

  it("fails closed when bff sessionStatus is missing or unknown", async () => {
    const missingStatusProvider = createBffAuthProvider(vi.fn().mockResolvedValue({
      authenticated: true,
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"]
    }));
    const unknownStatusProvider = createBffAuthProvider(vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "PARTIAL",
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"]
    }));

    await missingStatusProvider.refreshSession();
    await unknownStatusProvider.refreshSession();

    expect(missingStatusProvider.getInitialSession().userId).toBe("");
    expect(missingStatusProvider.getSessionState()).toEqual({
      status: SESSION_STATES.AUTH_ERROR
    });
    expect(unknownStatusProvider.getInitialSession().userId).toBe("");
    expect(unknownStatusProvider.getSessionState()).toEqual({
      status: SESSION_STATES.AUTH_ERROR
    });
  });

  it("fails closed for future bff session statuses without widening the contract", async () => {
    for (const sessionStatus of ["EXPIRED", "AUTH_UNAVAILABLE", "MISCONFIGURED"]) {
      const provider = createBffAuthProvider(vi.fn().mockResolvedValue({
        authenticated: false,
        sessionStatus,
        userId: "server-user-1",
        roles: ["FRAUD_OPS_ADMIN"],
        authorities: ["alert:read"]
      }));

      await provider.refreshSession();

      expect(provider.getInitialSession()).toMatchObject({
        userId: "",
        roles: [],
        authorities: []
      });
      expect(provider.getSessionState()).toEqual({
        status: SESSION_STATES.AUTH_ERROR
      });
    }
  });

  it("treats an empty bff session response as anonymous bootstrap", async () => {
    const provider = createBffAuthProvider(vi.fn().mockResolvedValue({}));

    await provider.refreshSession();

    expect(provider.getInitialSession().userId).toBe("");
    expect(provider.getSessionState()).toEqual({
      status: SESSION_STATES.UNAUTHENTICATED
    });
  });

  it("normalizes backend roles and authorities as session hints without granting unknown roles", async () => {
    const provider = createBffAuthProvider(vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "AUTHENTICATED",
      userId: "server-user-1",
      roles: ["UNKNOWN_ROLE"],
      authorities: ["unknown:capability"]
    }));

    await expect(provider.refreshSession()).resolves.toMatchObject({
      userId: "server-user-1",
      roles: [],
      authorities: ["unknown:capability"]
    });
    expect(authHeadersForSession(provider).Authorization).toBeUndefined();
  });

  it("fetches bff session with same-origin credentials and no authorization header", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        authenticated: true,
        sessionStatus: "AUTHENTICATED",
        userId: "server-user-1",
        roles: ["FRAUD_OPS_ADMIN"],
        authorities: ["alert:read"],
        csrf: {
          headerName: "X-CSRF-TOKEN",
          token: "csrf-1"
        }
      })
    });
    vi.stubGlobal("fetch", fetchMock);
    const provider = createBffAuthProvider();

    await provider.refreshSession();

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/session", {
      credentials: "same-origin",
      headers: {
        Accept: "application/json"
      }
    });
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it("redirects bff logout through the provider logout endpoint", async () => {
    const fetchSession = vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "AUTHENTICATED",
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

  it("keeps the bff session when logout fails", async () => {
    const fetchSession = vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "AUTHENTICATED",
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["alert:read"],
      csrf: {
        headerName: "X-CSRF-TOKEN",
        token: "csrf-1"
      }
    });
    const navigate = vi.fn();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({})
    }));
    const provider = createBffAuthProvider(fetchSession, navigate);

    await provider.refreshSession();
    await expect(provider.beginLogout()).rejects.toThrow("Logout request failed with status 403.");

    expect(provider.getInitialSession().userId).toBe("server-user-1");
    expect(provider.getLogoutError()).toBeInstanceOf(Error);
    expect(navigate).not.toHaveBeenCalled();
  });

  it("fails closed when bff logout lacks csrf for an authenticated session", async () => {
    const fetchSession = vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "AUTHENTICATED",
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["alert:read"]
    });
    const navigate = vi.fn();
    const logoutFetch = vi.fn();
    vi.stubGlobal("fetch", logoutFetch);
    const provider = createBffAuthProvider(fetchSession, navigate);

    await provider.refreshSession();
    await expect(provider.beginLogout()).rejects.toThrow("Logout requires a current CSRF token.");

    expect(logoutFetch).not.toHaveBeenCalled();
    expect(provider.getInitialSession().userId).toBe("server-user-1");
    expect(navigate).not.toHaveBeenCalled();
  });

  it("accepts backend-vetted external https bff logout redirects", async () => {
    const fetchSession = vi.fn().mockResolvedValue({
      authenticated: true,
      sessionStatus: "AUTHENTICATED",
      userId: "server-user-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["alert:read"],
      csrf: {
        headerName: "X-CSRF-TOKEN",
        token: "csrf-1"
      }
    });
    const navigate = vi.fn();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ logoutUrl: "https://issuer.bank.example/realms/fraud/protocol/openid-connect/logout" })
    }));
    const provider = createBffAuthProvider(fetchSession, navigate);

    await provider.refreshSession();
    await provider.beginLogout();

    expect(navigate).toHaveBeenCalledWith("https://issuer.bank.example/realms/fraud/protocol/openid-connect/logout");
  });

  it("accepts relative bff logout redirects returned by the backend", () => {
    expect(normalizeLogoutUrl({ logoutUrl: "/" })).toBe("/");
    expect(normalizeLogoutUrl({ logoutUrl: "/logged-out" })).toBe("/logged-out");
    expect(normalizeLogoutUrl({ logoutUrl: "/signed-out" })).toBe("/signed-out");
    expect(normalizeLogoutUrl({ logoutUrl: "signed-out" })).toBe("signed-out");
  });

  it("accepts backend-vetted absolute https and local http bff logout redirects", () => {
    expect(normalizeLogoutUrl({
      logoutUrl: "https://issuer.bank.example/logout?client_id=x"
    })).toBe("https://issuer.bank.example/logout?client_id=x");
    expect(normalizeLogoutUrl({
      logoutUrl: "https://console.bank.example/logged-out"
    })).toBe("https://console.bank.example/logged-out");
    expect(normalizeLogoutUrl({
      logoutUrl: "http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout"
    })).toBe("http://localhost:8086/realms/fraud-detection/protocol/openid-connect/logout");
  });

  it("rejects empty, protocol-relative, malformed, and unsafe bff logout redirects", () => {
    expect(() => normalizeLogoutUrl({ logoutUrl: "" }))
      .toThrow("Logout redirect URL is not trusted.");
    expect(() => normalizeLogoutUrl({ logoutUrl: "   " }))
      .toThrow("Logout redirect URL is not trusted.");
    expect(() => normalizeLogoutUrl({ logoutUrl: "//evil.example.test/logout" }))
      .toThrow("Logout redirect URL is not trusted.");
    expect(() => normalizeLogoutUrl({ logoutUrl: "javascript:alert(1)" }))
      .toThrow("Logout redirect URL is not trusted.");
    expect(() => normalizeLogoutUrl({ logoutUrl: "data:text/html,logout" }))
      .toThrow("Logout redirect URL is not trusted.");
    expect(() => normalizeLogoutUrl({ logoutUrl: "file:///tmp/logout" }))
      .toThrow("Logout redirect URL is not trusted.");
    expect(() => normalizeLogoutUrl({ logoutUrl: "https://[" }))
      .toThrow("Logout redirect URL is not trusted.");
  });

  it("defaults production-like builds to bff and rejects implicit demo auth", () => {
    expect(resolveAuthProviderKind({ PROD: true })).toBe("bff");
    expect(() => resolveAuthProviderKind({ PROD: true, VITE_AUTH_PROVIDER: "demo" }))
      .toThrow("Demo auth provider is not allowed");
    expect(resolveAuthProviderKind({
      PROD: true,
      VITE_AUTH_PROVIDER: "demo",
      VITE_ALLOW_DEMO_AUTH: "true"
    })).toBe("demo");
  });
});
