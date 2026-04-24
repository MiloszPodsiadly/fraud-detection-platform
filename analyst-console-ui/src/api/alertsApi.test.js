import { afterEach, describe, expect, it, vi } from "vitest";
import { listAlerts, setApiSession } from "./alertsApi.js";
import { normalizeSession } from "../auth/session.js";
import { createOidcAuthProvider } from "../auth/authProvider.js";
import { createInMemoryOidcSessionSource } from "../auth/oidcSessionSource.js";

describe("alertsApi auth headers", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    setApiSession(null);
  });

  it("adds demo auth headers for the active session", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    setApiSession(normalizeSession({ userId: "analyst-1", roles: ["REVIEWER"] }));

    await listAlerts();

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts?page=0&size=10", expect.objectContaining({
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "X-Demo-User-Id": "analyst-1",
        "X-Demo-Roles": "REVIEWER"
      })
    }));
    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty("X-Demo-Authorities");
  });

  it("omits demo auth headers when the session is unauthenticated", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    setApiSession(normalizeSession({ userId: "", roles: [] }));

    await listAlerts();

    const headers = fetchMock.mock.calls[0][1].headers;
    expect(headers).toEqual({ "Content-Type": "application/json" });
  });

  it("uses the configured auth provider headers instead of demo headers", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    const sessionSource = createInMemoryOidcSessionSource({
      accessToken: "oidc-token-123",
      session: { userId: "oidc-analyst", roles: ["ANALYST"] }
    });
    const authProvider = createOidcAuthProvider(sessionSource);

    setApiSession(normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }), authProvider);

    await listAlerts();

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts?page=0&size=10", expect.objectContaining({
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        Authorization: "Bearer oidc-token-123"
      })
    }));
    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty("X-Demo-User-Id");
  });

  it("sends no auth headers when the active oidc provider has no usable token", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    const authProvider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "",
      session: { userId: "", roles: [] }
    }));

    setApiSession(normalizeSession({ userId: "", roles: [] }), authProvider);

    await listAlerts();

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts?page=0&size=10", expect.objectContaining({
      headers: {
        "Content-Type": "application/json"
      }
    }));
  });
});

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
