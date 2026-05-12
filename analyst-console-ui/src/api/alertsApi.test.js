import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getGovernanceAdvisoryAnalytics,
  getGovernanceAdvisoryAudit,
  listAlerts,
  listFraudCaseWorkQueue,
  listGovernanceAdvisories,
  listScoredTransactions,
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

  it("calls only the dedicated fraud case work queue endpoint with allowlisted non-empty params", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      content: [],
      size: 50,
      hasNext: false,
      nextCursor: null,
      sort: "updatedAt,asc"
    }));

    const createdFrom = "2026-05-01T10:00";
    await listFraudCaseWorkQueue({
      size: 50,
      status: "ALL",
      priority: "HIGH",
      riskLevel: "",
      assignee: " investigator-1 ",
      createdFrom,
      sort: "updatedAt,asc"
    });

    const encodedCreatedFrom = encodeURIComponent(new Date(createdFrom).toISOString());
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/fraud-cases/work-queue?size=50&priority=HIGH&assignee=investigator-1&createdFrom=${encodedCreatedFrom}&sort=updatedAt%2Casc`,
      expect.objectContaining({ headers: expect.objectContaining({ "Content-Type": "application/json" }) })
    );
    expect(fetchMock.mock.calls[0][0]).not.toContain("/api/v1/fraud-cases?");
    expect(fetchMock.mock.calls[0][0]).not.toContain("status=ALL");
    expect(fetchMock.mock.calls[0][0]).not.toContain("riskLevel=");
  });

  it("rejects invalid work queue local date filters before fetch", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));

    expect(() => listFraudCaseWorkQueue({ createdFrom: "not-a-date" })).toThrow("Invalid local date filter.");

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("passes work queue cursor opaquely and never sends page with cursor", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      content: [],
      size: 20,
      hasNext: false
    }));
    const cursor = "opaque.cursor/with+symbols==";

    await listFraudCaseWorkQueue({ cursor, size: 20, sort: "createdAt,desc" });

    const url = fetchMock.mock.calls[0][0];
    expect(url).toContain("cursor=opaque.cursor%2Fwith%2Bsymbols%3D%3D");
    expect(url).not.toContain("page=");
  });

  it("sends scored transaction filters as backend query params", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      content: [],
      totalElements: 0,
      totalPages: 0,
      page: 0,
      size: 25
    }));

    await listScoredTransactions({
      page: 2,
      size: 25,
      query: " customer-1 ",
      riskLevel: "CRITICAL",
      status: "SUSPICIOUS"
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/transactions/scored?page=2&size=25&query=customer-1&riskLevel=CRITICAL&classification=SUSPICIOUS",
      expect.objectContaining({ headers: expect.objectContaining({ "Content-Type": "application/json" }) })
    );
  });

  it("surfaces work queue security and cursor errors without endpoint fallback", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      error: "INVALID_CURSOR",
      message: "INVALID_CURSOR"
    }, 400));

    await expect(listFraudCaseWorkQueue({ cursor: "bad", sort: "createdAt,desc" })).rejects.toMatchObject({
      status: 400,
      error: "INVALID_CURSOR"
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toContain("/api/v1/fraud-cases/work-queue?");
  });

  it("sends fraud case update with required idempotency header", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      operation_status: "COMMITTED",
      updated_case: { caseId: "case-1", status: "CLOSED" }
    }));
    const { updateFraudCase } = await import("./alertsApi.js");

    await updateFraudCase("case-1", {
      status: "CLOSED",
      analystId: "analyst-1",
      decisionReason: "Reviewed",
      tags: ["reviewed"]
    }, { idempotencyKey: "fraud-case-update-case-1-key" });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/fraud-cases/case-1", expect.objectContaining({
      method: "PATCH",
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "X-Idempotency-Key": "fraud-case-update-case-1-key"
      })
    }));
  });

  it("sends analyst decision with idempotency header", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      resultingStatus: "RESOLVED"
    }));
    const { submitAnalystDecision } = await import("./alertsApi.js");

    await submitAnalystDecision("alert-1", {
      analystId: "analyst-1",
      decision: "MARKED_LEGITIMATE",
      decisionReason: "Reviewed",
      tags: ["manual-review"]
    }, { idempotencyKey: "alert-decision-alert-1-key" });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts/alert-1/decision", expect.objectContaining({
      method: "POST",
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "X-Idempotency-Key": "alert-decision-alert-1-key"
      })
    }));
  });
});

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
