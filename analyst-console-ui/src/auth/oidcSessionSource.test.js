import { describe, expect, it } from "vitest";
import { SESSION_STATES } from "./sessionState.js";
import {
  createOidcSessionSource,
  createInMemoryOidcSessionSource,
  normalizeOidcSessionSnapshot,
  oidcAuthHeaders,
  snapshotFromOidcUser
} from "./oidcSessionSource.js";

describe("oidcSessionSource", () => {
  it("normalizes a provider snapshot into session, token, and lifecycle state", () => {
    const snapshot = normalizeOidcSessionSnapshot({
      accessToken: " oidc-token-1 ",
      profile: {
        sub: "oidc-user-1",
        roles: ["ANALYST"],
        authorities: ["alert:read"]
      },
      state: {
        status: SESSION_STATES.AUTHENTICATED,
        expiresAt: "2026-04-24T18:30:00Z"
      }
    });

    expect(snapshot.session).toMatchObject({
      userId: "oidc-user-1",
      roles: ["ANALYST"]
    });
    expect(snapshot.accessToken).toBe("oidc-token-1");
    expect(snapshot.state).toEqual({
      status: SESSION_STATES.AUTHENTICATED,
      expiresAt: "2026-04-24T18:30:00Z"
    });
  });

  it("keeps the in-memory source aligned to the normalized snapshot contract", () => {
    const source = createInMemoryOidcSessionSource({
      profile: { sub: "oidc-user-2", roles: ["REVIEWER"] },
      accessToken: "token-2"
    });

    expect(source.getSession()).toMatchObject({
      userId: "oidc-user-2",
      roles: ["REVIEWER"]
    });
    expect(source.getAccessToken()).toBe("token-2");
    expect(source.getState()).toEqual({
      status: SESSION_STATES.AUTHENTICATED,
      expiresAt: null
    });
  });

  it("keeps bearer auth headers empty when there is no usable access token", () => {
    expect(oidcAuthHeaders("")).toEqual({});
  });

  it("maps OIDC groups into the existing analyst role contract", () => {
    const snapshot = snapshotFromOidcUser({
      access_token: "token-3",
      expires_at: 2_000_000_000,
      profile: {
        sub: "subject-3",
        groups: ["fraud-reviewer"]
      }
    });

    expect(snapshot.session).toMatchObject({
      userId: "subject-3",
      roles: ["REVIEWER"]
    });
    expect(snapshot.state.status).toBe(SESSION_STATES.AUTHENTICATED);
  });

  it("marks expired users from the provider snapshot before the UI reaches the API", async () => {
    const source = createOidcSessionSource({
      getUser: async () => ({
        access_token: "expired-token",
        expired: true,
        expires_at: 1_700_000_000,
        profile: {
          sub: "subject-expired",
          groups: ["fraud-analyst"]
        }
      }),
      completeLoginCallback: async () => ({
        access_token: "fresh-token",
        expires_at: 2_000_000_000,
        profile: {
          sub: "subject-fresh",
          groups: ["fraud-analyst"]
        }
      })
    });

    await source.refresh();
    expect(source.getState()).toEqual({
      status: SESSION_STATES.EXPIRED,
      expiresAt: "2023-11-14T22:13:20.000Z"
    });

    await source.completeLoginCallback();
    expect(source.getSession()).toMatchObject({
      userId: "subject-fresh",
      roles: ["ANALYST"]
    });
    expect(source.getAccessToken()).toBe("fresh-token");
    expect(source.getState()).toEqual({
      status: SESSION_STATES.AUTHENTICATED,
      expiresAt: "2033-05-18T03:33:20.000Z"
    });
  });
});
