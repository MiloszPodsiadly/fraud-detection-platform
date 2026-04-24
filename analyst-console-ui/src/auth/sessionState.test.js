import { describe, expect, it } from "vitest";
import { getSessionStateForProvider, SESSION_STATES } from "./sessionState.js";

describe("sessionState", () => {
  it("treats oidc sessions with a usable bearer token as authenticated even when the provider snapshot is stale", () => {
    const session = {
      userId: "oidc-user-1",
      roles: ["ANALYST"],
      extraAuthorities: [],
      authorities: ["alert:read"]
    };
    const authProvider = {
      kind: "oidc",
      getSessionState: () => ({ status: SESSION_STATES.UNAUTHENTICATED }),
      getRequestHeaders: () => ({ Authorization: "Bearer oidc-token" })
    };

    expect(getSessionStateForProvider(session, authProvider)).toEqual({
      status: SESSION_STATES.AUTHENTICATED,
      expiresAt: null
    });
  });
});
