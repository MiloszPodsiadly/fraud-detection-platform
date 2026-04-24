import { describe, expect, it } from "vitest";
import { createDemoAuthProvider, createOidcAuthProvider } from "./authProvider.js";
import { authHeadersForSession } from "./authHeaders.js";
import { createInMemoryOidcSessionSource, normalizeOidcSessionSnapshot } from "./oidcSessionSource.js";
import { SESSION_STATES } from "./sessionState.js";

describe("authProvider", () => {
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
    const sessionSource = createInMemoryOidcSessionSource(normalizeOidcSessionSnapshot({
      accessToken: "oidc-token",
      profile: {
        sub: "oidc-user-1",
        roles: ["ANALYST"],
        authorities: ["alert:read"]
      }
    }));
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
    await expect(provider.completeLoginCallback()).resolves.toMatchObject({
      userId: "oidc-user-1",
      roles: ["ANALYST"]
    });
    await expect(provider.beginLogout()).resolves.toBe("logout-started");
    expect(provider.hasLoginConfiguration()).toBe(true);
  });
});
