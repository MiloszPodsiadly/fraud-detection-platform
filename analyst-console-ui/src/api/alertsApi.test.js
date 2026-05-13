import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createAlertsApiClient,
  toUtcInstantParam,
} from "./alertsApi.js";
import { normalizeSession } from "../auth/session.js";
import { createBffAuthProvider, createDemoAuthProvider, createOidcAuthProvider } from "../auth/authProvider.js";
import { createInMemoryOidcSessionSource } from "../auth/oidcSessionSource.js";

describe("alertsApi auth headers", () => {
  let apiClient = createAlertsApiClient({
    session: null,
    authProvider: createDemoAuthProvider()
  });

  function resetApiClient(session, authProvider = createDemoAuthProvider()) {
    apiClient = createAlertsApiClient({ session, authProvider });
  }

  const listAlerts = (...args) => apiClient.listAlerts(...args);
  const listFraudCaseWorkQueue = (...args) => apiClient.listFraudCaseWorkQueue(...args);
  const getFraudCaseWorkQueueSummary = (...args) => apiClient.getFraudCaseWorkQueueSummary(...args);
  const listScoredTransactions = (...args) => apiClient.listScoredTransactions(...args);
  const listGovernanceAdvisories = (...args) => apiClient.listGovernanceAdvisories(...args);
  const getGovernanceAdvisoryAnalytics = (...args) => apiClient.getGovernanceAdvisoryAnalytics(...args);
  const getGovernanceAdvisoryAudit = (...args) => apiClient.getGovernanceAdvisoryAudit(...args);
  const recordGovernanceAdvisoryAudit = (...args) => apiClient.recordGovernanceAdvisoryAudit(...args);
  const updateFraudCase = (...args) => apiClient.updateFraudCase(...args);
  const submitAnalystDecision = (...args) => apiClient.submitAnalystDecision(...args);

  afterEach(() => {
    vi.restoreAllMocks();
    resetApiClient(null);
  });

  it("adds demo auth headers for the active session", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    resetApiClient(normalizeSession({ userId: "analyst-1", roles: ["REVIEWER"] }));

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
    resetApiClient(normalizeSession({ userId: "", roles: [] }));

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

    resetApiClient(normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }), authProvider);

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

    resetApiClient(normalizeSession({ userId: "", roles: [] }), authProvider);

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

    resetApiClient(normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }), authProvider);

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
    resetApiClient(normalizeSession({ userId: "analyst-1", roles: ["READ_ONLY_ANALYST"] }), createDemoAuthProvider());

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
    resetApiClient(normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] }), createDemoAuthProvider());

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

  it("loads fraud case work queue summary from the current v1 endpoint only", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      totalFraudCases: 46
    }));

    await getFraudCaseWorkQueueSummary();

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/fraud-cases/work-queue/summary",
      expect.objectContaining({ headers: expect.objectContaining({ "Content-Type": "application/json" }) })
    );
    expect(fetchMock.mock.calls[0][0]).not.toContain("/api/fraud-cases");
    expect(fetchMock.mock.calls[0][0]).not.toContain("/api/v1/fraud-cases?");
  });

  it("uses same-origin credentials and csrf without authorization for bff requests", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    const authProvider = await refreshedBffProvider("X-CSRF-TOKEN", "csrf-1");

    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), authProvider);
    await listAlerts();

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts?page=0&size=10", expect.objectContaining({
      credentials: "same-origin",
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "csrf-1"
      })
    }));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty("X-Demo-User-Id");
  });

  it("uses bff credentials without authorization for all FDP-48 read paths", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const authProvider = await refreshedBffProvider("X-CSRF-TOKEN", "csrf-read");
    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), authProvider);

    await listAlerts();
    await listFraudCaseWorkQueue();
    await getFraudCaseWorkQueueSummary();
    await listScoredTransactions();
    await listGovernanceAdvisories();
    await getGovernanceAdvisoryAnalytics();

    for (const [, options] of fetchMock.mock.calls) {
      expect(options.credentials).toBe("same-origin");
      expect(options.headers.Authorization).toBeUndefined();
      expect(options.headers).not.toHaveProperty("X-Demo-User-Id");
    }
  });

  it("sends bff csrf and same-origin credentials for mutating requests", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      operation_status: "COMMITTED",
      updated_case: { caseId: "case-1", status: "CLOSED" }
    }));
    const authProvider = await refreshedBffProvider("X-XSRF-TOKEN", "csrf-2");

    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), authProvider);
    await updateFraudCase("case-1", {
      status: "CLOSED",
      analystId: "server-user-1",
      decisionReason: "Reviewed",
      tags: []
    }, { idempotencyKey: "fraud-case-update-case-1-key" });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/fraud-cases/case-1", expect.objectContaining({
      method: "PATCH",
      credentials: "same-origin",
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": "csrf-2",
        "X-Idempotency-Key": "fraud-case-update-case-1-key"
      })
    }));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it("uses bff csrf and no authorization for alert and governance mutations", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({
      operation_status: "COMMITTED"
    })));
    const authProvider = await refreshedBffProvider("X-XSRF-TOKEN", "csrf-mutation");
    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), authProvider);

    await submitAnalystDecision("alert-1", {
      analystId: "server-user-1",
      decision: "MARKED_LEGITIMATE",
      decisionReason: "Reviewed",
      tags: []
    }, { idempotencyKey: "alert-decision-alert-1-key" });
    await updateFraudCase("case-1", {
      status: "CLOSED",
      analystId: "server-user-1",
      decisionReason: "Reviewed",
      tags: []
    }, { idempotencyKey: "fraud-case-update-case-1-key" });
    await recordGovernanceAdvisoryAudit("event-1", {
      decision: "ACKNOWLEDGED",
      note: "Reviewed by operator"
    });

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/alerts/alert-1/decision", expect.objectContaining({
      method: "POST",
      credentials: "same-origin",
      headers: expect.objectContaining({
        "X-XSRF-TOKEN": "csrf-mutation",
        "X-Idempotency-Key": "alert-decision-alert-1-key"
      })
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/fraud-cases/case-1", expect.objectContaining({
      method: "PATCH",
      credentials: "same-origin",
      headers: expect.objectContaining({
        "X-XSRF-TOKEN": "csrf-mutation",
        "X-Idempotency-Key": "fraud-case-update-case-1-key"
      })
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(3, "/governance/advisories/event-1/audit", expect.objectContaining({
      method: "POST",
      credentials: "same-origin",
      headers: expect.objectContaining({
        "X-XSRF-TOKEN": "csrf-mutation"
      })
    }));
    for (const [, options] of fetchMock.mock.calls) {
      expect(options.headers.Authorization).toBeUndefined();
    }
  });

  it("uses the latest active provider when switching from oidc to bff", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const oidcProvider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "oidc-token-123",
      session: { userId: "oidc-analyst", roles: ["ANALYST"] }
    }));
    const bffProvider = await refreshedBffProvider("X-CSRF-TOKEN", "csrf-current");

    resetApiClient(normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }), oidcProvider);
    await listAlerts();
    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), bffProvider);
    await listAlerts();

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer oidc-token-123");
    expect(fetchMock.mock.calls[1][1].credentials).toBe("same-origin");
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBeUndefined();
    expect(fetchMock.mock.calls[1][1].headers["X-CSRF-TOKEN"]).toBe("csrf-current");
  });

  it("does not leak stale bff csrf when switching back to oidc", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const bffProvider = await refreshedBffProvider("X-CSRF-TOKEN", "csrf-stale");
    const oidcProvider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "oidc-token-456",
      session: { userId: "oidc-analyst", roles: ["ANALYST"] }
    }));

    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), bffProvider);
    await listAlerts();
    resetApiClient(normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }), oidcProvider);
    await listAlerts();

    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe("Bearer oidc-token-456");
    expect(fetchMock.mock.calls[1][1].headers).not.toHaveProperty("X-CSRF-TOKEN");
    expect(fetchMock.mock.calls[1][1]).not.toHaveProperty("credentials");
  });

  it("passes AbortSignal to list endpoints", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    const abortController = new AbortController();

    await listAlerts({ page: 1, size: 5 }, { signal: abortController.signal });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts?page=1&size=5", expect.objectContaining({
      signal: abortController.signal
    }));
  });

  it("propagates AbortError without converting it to ApiError", async () => {
    const abortError = new DOMException("The operation was aborted.", "AbortError");
    vi.spyOn(globalThis, "fetch").mockRejectedValue(abortError);

    await expect(listScoredTransactions({}, { signal: new AbortController().signal })).rejects.toBe(abortError);
  });

  it("rejects invalid work queue local date filters before fetch", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));

    expect(() => listFraudCaseWorkQueue({ createdFrom: "not-a-date" })).toThrow("Invalid local date filter.");

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("converts all work queue datetime-local filters to UTC instants before building the request", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    const createdFrom = " 2026-05-13T08:15 ";
    const createdTo = "2026-05-13T09:15";
    const updatedFrom = "2026-05-13T10:15";
    const updatedTo = "2026-05-13T11:15";

    await listFraudCaseWorkQueue({
      createdFrom,
      createdTo,
      updatedFrom,
      updatedTo
    });

    const query = new URLSearchParams(fetchMock.mock.calls[0][0].split("?")[1]);
    expect(query.get("createdFrom")).toBe(new Date(createdFrom.trim()).toISOString());
    expect(query.get("createdTo")).toBe(new Date(createdTo.trim()).toISOString());
    expect(query.get("updatedFrom")).toBe(new Date(updatedFrom.trim()).toISOString());
    expect(query.get("updatedTo")).toBe(new Date(updatedTo.trim()).toISOString());
  });

  it("omits empty work queue datetime filters and exposes controlled conversion failures", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));

    expect(toUtcInstantParam(" 2026-05-13T08:15 ")).toBe(new Date("2026-05-13T08:15").toISOString());
    expect(toUtcInstantParam("   ")).toBeNull();
    expect(() => toUtcInstantParam("not-a-date")).toThrow("Invalid local date filter.");

    await listFraudCaseWorkQueue({
      createdFrom: "",
      createdTo: " ",
      updatedFrom: null,
      updatedTo: undefined
    });

    const url = fetchMock.mock.calls[0][0];
    expect(url).not.toContain("createdFrom=");
    expect(url).not.toContain("createdTo=");
    expect(url).not.toContain("updatedFrom=");
    expect(url).not.toContain("updatedTo=");
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

async function refreshedBffProvider(headerName = "X-CSRF-TOKEN", token = "csrf-1") {
  const authProvider = createBffAuthProvider(vi.fn().mockResolvedValue({
    authenticated: true,
    sessionStatus: "AUTHENTICATED",
    userId: "server-user-1",
    roles: ["FRAUD_OPS_ADMIN"],
    authorities: ["alert:read", "fraud-case:update", "governance-advisory:audit:write"],
    csrf: {
      headerName,
      token
    }
  }));
  await authProvider.refreshSession();
  return authProvider;
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
