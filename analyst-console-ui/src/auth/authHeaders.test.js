import { describe, expect, it } from "vitest";
import { authHeadersForSession } from "./authHeaders.js";
import { createDemoAuthProvider, createOidcAuthProvider } from "./authProvider.js";
import { normalizeSession } from "./session.js";
import { createInMemoryOidcSessionSource } from "./oidcSessionSource.js";

describe("authHeadersForSession", () => {
  it("returns demo auth headers for authenticated demo sessions", () => {
    const provider = createDemoAuthProvider();

    expect(authHeadersForSession(provider, normalizeSession({
      userId: "analyst-1",
      roles: ["REVIEWER"]
    }))).toEqual({
      "X-Demo-User-Id": "analyst-1",
      "X-Demo-Roles": "REVIEWER"
    });
  });

  it("returns bearer auth headers for oidc-backed sessions with a token", () => {
    const provider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "oidc-token-123",
      session: { userId: "oidc-1", roles: ["ANALYST"] }
    }));

    expect(authHeadersForSession(provider, normalizeSession({
      userId: "oidc-1",
      roles: ["ANALYST"]
    }))).toEqual({
      Authorization: "Bearer oidc-token-123"
    });
  });

  it("returns no auth headers for oidc sessions without a usable access token", () => {
    const provider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "   ",
      session: { userId: "oidc-1", roles: ["ANALYST"] }
    }));

    expect(authHeadersForSession(provider, normalizeSession({
      userId: "oidc-1",
      roles: ["ANALYST"]
    }))).toEqual({});
  });

  it("returns no auth headers for unauthenticated sessions", () => {
    const provider = createDemoAuthProvider();

    expect(authHeadersForSession(provider, normalizeSession({
      userId: "",
      roles: []
    }))).toEqual({});
  });
});
