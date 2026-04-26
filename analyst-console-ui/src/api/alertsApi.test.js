import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getGovernanceAdvisoryAnalytics,
  getGovernanceAdvisoryAudit,
  listAlerts,
  listGovernanceAdvisories,
  recordGovernanceAdvisoryAudit,
  setApiSession
} from "./alertsApi.js";
import { normalizeSession } from "../auth/session.js";
import { createDemoAuthProvider, createOidcAuthProvider } from "../auth/authProvider.js";
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

  it("calls governance advisories with bounded query params and auth headers", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      status: "AVAILABLE",
      count: 0,
      retention_limit: 200,
      advisory_events: []
    }));
    const authProvider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "oidc-token-123",
      session: { userId: "oidc-analyst", roles: ["ANALYST"] }
    }));

    setApiSession(normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }), authProvider);

    await listGovernanceAdvisories({
      severity: "HIGH",
      modelVersion: " 2026-04-21.trained.v1 ",
      lifecycleStatus: "ACKNOWLEDGED",
      limit: 25
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/governance/advisories?limit=25&severity=HIGH&model_version=2026-04-21.trained.v1&lifecycle_status=ACKNOWLEDGED",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          Authorization: "Bearer oidc-token-123"
        })
      })
    );
    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty("X-Demo-User-Id");
  });

  it("calls governance analytics with bounded window and auth headers", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      status: "AVAILABLE",
      window: { days: 7 },
      totals: { advisories: 0, reviewed: 0, open: 0 },
      decision_distribution: {},
      lifecycle_distribution: {},
      review_timeliness: { status: "LOW_CONFIDENCE" }
    }));
    setApiSession(normalizeSession({ userId: "analyst-1", roles: ["READ_ONLY_ANALYST"] }), createDemoAuthProvider());

    await getGovernanceAdvisoryAnalytics({ windowDays: 14 });

    expect(fetchMock).toHaveBeenCalledWith(
      "/governance/advisories/analytics?window_days=14",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Demo-User-Id": "analyst-1"
        })
      })
    );
  });

  it("uses auth headers for governance audit history and writes only decision payload", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse({
        advisory_event_id: "event-1",
        status: "AVAILABLE",
        audit_events: []
      }))
      .mockResolvedValueOnce(jsonResponse({
        audit_id: "audit-1",
        advisory_event_id: "event-1",
        decision: "ACKNOWLEDGED"
      }, 201));
    setApiSession(normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] }), createDemoAuthProvider());

    await getGovernanceAdvisoryAudit("event-1");
    await recordGovernanceAdvisoryAudit("event-1", {
      decision: "ACKNOWLEDGED",
      note: "Reviewed by operator"
    });

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/governance/advisories/event-1/audit", expect.objectContaining({
      headers: expect.objectContaining({
        "X-Demo-User-Id": "analyst-1",
        "X-Demo-Roles": "ANALYST"
      })
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/governance/advisories/event-1/audit", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({
        decision: "ACKNOWLEDGED",
        note: "Reviewed by operator"
      }),
      headers: expect.objectContaining({
        "X-Demo-User-Id": "analyst-1"
      })
    }));
    expect(fetchMock.mock.calls[1][1].body).not.toContain("actor");
  });
});

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
