import { afterEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  createAlertsApiClient,
  toUtcInstantParam,
} from "./alertsApi.js";
import { isValidPromotionReviewReadinessReport } from "../governance/promotionReviewReadinessReportValidation.js";
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
  const getScoredTransactionDetail = (...args) => apiClient.getScoredTransactionDetail(...args);
  const getFraudFeedback = (...args) => apiClient.getFraudFeedback(...args);
  const createFraudFeedback = (...args) => apiClient.createFraudFeedback(...args);
  const listSuspiciousTransactions = (...args) => apiClient.listSuspiciousTransactions(...args);
  const getSuspiciousTransactionSummary = (...args) => apiClient.getSuspiciousTransactionSummary(...args);
  const getSuspiciousTransaction = (...args) => apiClient.getSuspiciousTransaction(...args);
  const getSuspiciousTransactionLinkedAlertContext = (...args) => apiClient.getSuspiciousTransactionLinkedAlertContext(...args);
  const getCurrentShadowPerformanceSummary = (...args) => apiClient.getCurrentShadowPerformanceSummary(...args);
  const getCurrentPromotionReviewReadinessReport = (...args) => apiClient.getCurrentPromotionReviewReadinessReport(...args);
  const listGovernanceAdvisories = (...args) => apiClient.listGovernanceAdvisories(...args);
  const getGovernanceAdvisoryAnalytics = (...args) => apiClient.getGovernanceAdvisoryAnalytics(...args);
  const getGovernanceAdvisoryAudit = (...args) => apiClient.getGovernanceAdvisoryAudit(...args);
  const recordGovernanceAdvisoryAudit = (...args) => apiClient.recordGovernanceAdvisoryAudit(...args);
  const getAlert = (...args) => apiClient.getAlert(...args);
  const getAssistantSummary = (...args) => apiClient.getAssistantSummary(...args);
  const getFraudCase = (...args) => apiClient.getFraudCase(...args);
  const getFraudCaseEvidenceSummary = (...args) => apiClient.getFraudCaseEvidenceSummary(...args);
  const getFraudCaseEvidenceTimeline = (...args) => apiClient.getFraudCaseEvidenceTimeline(...args);
  const getEngineIntelligence = (...args) => apiClient.getEngineIntelligence(...args);
  const submitEngineIntelligenceFeedback = (...args) => apiClient.submitEngineIntelligenceFeedback(...args);
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

  it("fetchesCurrentShadowPerformanceSummary", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(shadowPerformanceSummary()));
    resetApiClient(normalizeSession({
      userId: "analyst-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["shadow-performance:read"]
    }), createDemoAuthProvider());

    const summary = await getCurrentShadowPerformanceSummary();

    expect(summary).toMatchObject({
      summaryType: "SHADOW_PERFORMANCE_SUMMARY_V1",
      model: { modelName: "python-logistic-fraud-model" }
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/governance/shadow-performance/summary/current",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Demo-User-Id": "analyst-1"
        })
      })
    );
  });

  it("fetchesCurrentPromotionReviewReadinessReport", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(promotionReviewReadinessReport()));
    resetApiClient(normalizeSession({
      userId: "analyst-1",
      roles: ["FRAUD_OPS_ADMIN"],
      authorities: ["promotion-readiness:read"]
    }), createDemoAuthProvider());

    const report = await getCurrentPromotionReviewReadinessReport();

    expect(report).toMatchObject({
      reportType: "PROMOTION_REVIEW_READINESS_REPORT_V1",
      governanceStatus: "DIAGNOSTIC_ONLY",
      readinessStatus: "REVIEWABLE"
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/governance/promotion-review-readiness/current",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Demo-User-Id": "analyst-1"
        })
      })
    );
  });

  it("promotionReviewReadinessSupportsAbortSignal", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(promotionReviewReadinessReport()));
    const signal = new AbortController().signal;

    await getCurrentPromotionReviewReadinessReport({ signal });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/governance/promotion-review-readiness/current",
      expect.objectContaining({ signal })
    );
  });

  it("promotionReviewReadinessCallsOnlyCurrentReadEndpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(promotionReviewReadinessReport()));

    await getCurrentPromotionReviewReadinessReport({
      signal: new AbortController().signal,
      modelVersion: "secret-model",
      from: "2026-01-01",
      search: "customer-secret",
      body: JSON.stringify({ transactionReference: "txn-secret" }),
      method: "POST"
    });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/governance/promotion-review-readiness/current");
    expect(url).not.toContain("?");
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("secret-model");
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("customer-secret");
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("txn-secret");
    expect(options).not.toHaveProperty("body");
    expect(options).not.toHaveProperty("method");
  });

  it.each([401, 403, 404, 503])("promotionReviewReadinessPropagates%s", async (status) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ message: "read failed" }, status));

    await expect(getCurrentPromotionReviewReadinessReport()).rejects.toMatchObject({ status });
  });

  it.each([
    ["doesNotUsePost", "POST"],
    ["doesNotUsePut", "PUT"],
    ["doesNotUsePatch", "PATCH"],
    ["doesNotUseDelete", "DELETE"]
  ])("promotionReviewReadiness%s", async (_name, method) => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(promotionReviewReadinessReport()));

    await getCurrentPromotionReviewReadinessReport({ method, body: "{}" });

    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("method");
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
  });

  it.each([
    ["doesNotCallReportGeneration", "/generate"],
    ["doesNotCallWorkflow", "/workflow"],
    ["doesNotCallModelRegistryMutation", "/model-registry"],
    ["doesNotCallThresholdEndpoint", "/threshold"],
    ["doesNotCallScoringEndpoint", "/scored"],
    ["doesNotCallPaymentEndpoint", "/payment"],
    ["doesNotCallAlertMutationEndpoint", "/alerts/alert-1/decision"],
    ["doesNotCallFraudCaseMutationEndpoint", "/fraud-cases/case-1"]
  ])("promotionReviewReadiness%s", async (_name, forbiddenPath) => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(promotionReviewReadinessReport()));

    await getCurrentPromotionReviewReadinessReport();

    expect(fetchMock.mock.calls[0][0]).not.toContain(forbiddenPath);
  });

  it("promotionReviewReadinessValidationAcceptsValidReport", () => {
    expect(isValidPromotionReviewReadinessReport(promotionReviewReadinessReport())).toBe(true);
  });

  it.each([
    ["invalidReportType", (report) => { report.reportType = "OTHER"; }],
    ["invalidReportVersion", (report) => { report.reportVersion = "2.0"; }],
    ["missingGeneratedAt", (report) => { delete report.generatedAt; }],
    ["invalidGeneratedAt", (report) => { report.generatedAt = "not-a-date"; }],
    ["invalidGovernanceStatus", (report) => { report.governanceStatus = "PRODUCTION"; }],
    ["invalidReadinessStatus", (report) => { report.readinessStatus = "APPROVED"; }],
    ["missingDiagnosticOnly", (report) => { delete report.diagnosticOnly; }],
    ["diagnosticOnlyFalse", (report) => { report.diagnosticOnly = false; }],
    ["missingNotPromotionApproval", (report) => { delete report.notPromotionApproval; }],
    ["notPromotionApprovalFalse", (report) => { report.notPromotionApproval = false; }],
    ["missingNotThresholdRecommendation", (report) => { delete report.notThresholdRecommendation; }],
    ["notThresholdRecommendationFalse", (report) => { report.notThresholdRecommendation = false; }],
    ["missingNotProductionDecisioning", (report) => { delete report.notProductionDecisioning; }],
    ["notProductionDecisioningFalse", (report) => { report.notProductionDecisioning = false; }],
    ["missingNotPaymentAuthorization", (report) => { delete report.notPaymentAuthorization; }],
    ["notPaymentAuthorizationFalse", (report) => { report.notPaymentAuthorization = false; }],
    ["missingNotAutomaticDecisioning", (report) => { delete report.notAutomaticDecisioning; }],
    ["notAutomaticDecisioningFalse", (report) => { report.notAutomaticDecisioning = false; }],
    ["missingNotAnalystRecommendation", (report) => { delete report.notAnalystRecommendation; }],
    ["notAnalystRecommendationFalse", (report) => { report.notAnalystRecommendation = false; }],
    ["missingBanner", (report) => { delete report.banner; }],
    ["oversizedBanner", (report) => { report.banner = "A".repeat(513); }],
    ["missingInputs", (report) => { delete report.inputs; }],
    ["missingShadowPerformanceSummaryInput", (report) => { delete report.inputs.shadowPerformanceSummary; }],
    ["rawShadowSummaryType", (report) => { report.inputs.shadowPerformanceSummary.summaryType = "rawPayload"; }],
    ["unsafeShadowSummaryVersion", (report) => { report.inputs.shadowPerformanceSummary.summaryVersion = "approved"; }],
    ["invalidShadowSummaryGeneratedAt", (report) => { report.inputs.shadowPerformanceSummary.generatedAt = "not-a-date"; }],
    ["missingMinimumDiagnosticEvidenceRecords", (report) => { delete report.inputs.minimumDiagnosticEvidenceRecords; }],
    ["zeroMinimumDiagnosticEvidenceRecords", (report) => { report.inputs.minimumDiagnosticEvidenceRecords = 0; }],
    ["oversizedMinimumDiagnosticEvidenceRecords", (report) => { report.inputs.minimumDiagnosticEvidenceRecords = 501; }],
    ["negativeRecordsAcceptedForEvaluation", (report) => { report.inputs.recordsAcceptedForEvaluation = -1; }],
    ["oversizedRecordsAcceptedForEvaluation", (report) => { report.inputs.recordsAcceptedForEvaluation = 501; }],
    ["missingChecks", (report) => { delete report.checks; }],
    ["checksNotArray", (report) => { report.checks = {}; }],
    ["emptyChecks", (report) => { report.checks = []; }],
    ["nullCheckItem", (report) => { report.checks = [null]; }],
    ["missingCheckName", (report) => { delete report.checks[0].name; }],
    ["missingCheckStatus", (report) => { delete report.checks[0].status; }],
    ["missingCheckSeverity", (report) => { delete report.checks[0].severity; }],
    ["unsupportedCheckStatus", (report) => { report.checks[0].status = "DONE"; }],
    ["unsupportedCheckSeverity", (report) => { report.checks[0].severity = "CRITICAL"; }],
    ["duplicateCheckNames", (report) => { report.checks = [report.checks[0], { ...report.checks[0] }]; }],
    ["reviewableWithFailCheck", (report) => { report.checks[0].status = "FAIL"; }],
    ["reasonCodesNotArray", (report) => { report.reasonCodes = {}; }],
    ["warningsNotArray", (report) => { report.warnings = {}; }],
    ["limitationsNotArray", (report) => { report.limitations = {}; }],
    ["oversizedChecks", (report) => { report.checks = Array.from({ length: 51 }, (_value, index) => ({ name: `CHECK_${index}`, status: "PASS", severity: "INFO" })); }],
    ["oversizedReasonCodes", (report) => { report.reasonCodes = Array.from({ length: 21 }, (_value, index) => `REASON_${index}`); }],
    ["oversizedWarnings", (report) => { report.warnings = Array.from({ length: 21 }, (_value, index) => `WARNING_${index}`); }],
    ["oversizedLimitations", (report) => { report.limitations = Array.from({ length: 21 }, (_value, index) => `LIMITATION_${index}`); }],
    ["oversizedCheckName", (report) => { report.checks[0].name = "A".repeat(129); }],
    ["oversizedReasonCode", (report) => { report.reasonCodes = ["A".repeat(129)]; }],
    ["invalidReasonCodeMachineFormat", (report) => { report.reasonCodes = ["not machine code"]; }]
  ])("promotionReviewReadinessValidationRejects%s", (_name, mutate) => {
    const report = promotionReviewReadinessReport();
    mutate(report);

    expect(isValidPromotionReviewReadinessReport(report)).toBe(false);
  });

  it.each([
    ["rawIdentifierInCheckName", (report) => { report.checks[0].name = "rawPayload"; }],
    ["rawIdentifierInReasonCode", (report) => { report.reasonCodes = ["RAW_PAYLOAD"]; }],
    ["transactionReferenceInWarning", (report) => { report.warnings = ["TRANSACTION_REFERENCE"]; }],
    ["filesystemPathInLimitation", (report) => { report.limitations = ["C:\\SECRET_PATH"]; }],
    ["stackTraceInCheckName", (report) => { report.checks[0].name = "STACK_TRACE"; }],
    ["secretInMachineCode", (report) => { report.reasonCodes = ["TOKEN_SECRET"]; }],
    ["approvedInCheckName", (report) => { report.checks[0].name = "MODEL_APPROVED"; }],
    ["promotedInReasonCode", (report) => { report.reasonCodes = ["PROMOTED"]; }],
    ["readyForProductionInWarning", (report) => { report.warnings = ["READY_FOR_PRODUCTION"]; }],
    ["deployableInLimitation", (report) => { report.limitations = ["DEPLOYABLE"]; }],
    ["recommendedThresholdInCheckName", (report) => { report.checks[0].name = "RECOMMENDED_THRESHOLD"; }],
    ["paymentAuthorizedInMachineCode", (report) => { report.reasonCodes = ["PAYMENT_AUTHORIZED"]; }],
    ["autoApproveInWarning", (report) => { report.warnings = ["AUTO_APPROVE"]; }],
    ["autoDeclineInWarning", (report) => { report.warnings = ["AUTO_DECLINE"]; }],
    ["blockTransactionInLimitation", (report) => { report.limitations = ["BLOCK_TRANSACTION"]; }],
    ["analystRecommendationInReasonCode", (report) => { report.reasonCodes = ["ANALYST_RECOMMENDATION"]; }]
  ])("promotionReviewReadinessValidationRejects%s", (_name, mutate) => {
    const report = promotionReviewReadinessReport();
    mutate(report);

    expect(isValidPromotionReviewReadinessReport(report)).toBe(false);
  });

  it("promotionReviewReadinessInvalidResponseReturnsSafeState", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(promotionReviewReadinessReport({
      notPaymentAuthorization: false
    })));

    await expect(getCurrentPromotionReviewReadinessReport()).resolves.toEqual({ state: "invalid-response" });
  });

  it("callsOnlyShadowPerformanceSummaryCurrentEndpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(shadowPerformanceSummary()));

    await getCurrentShadowPerformanceSummary({
      signal: new AbortController().signal,
      modelVersion: "secret-model",
      from: "2026-01-01",
      search: "customer-secret",
      body: JSON.stringify({ transactionReference: "txn-secret" }),
      method: "POST"
    });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/governance/shadow-performance/summary/current");
    expect(url).not.toContain("?");
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("secret-model");
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("customer-secret");
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain("txn-secret");
    expect(options).not.toHaveProperty("body");
    expect(options).not.toHaveProperty("method");
  });

  it.each([
    ["doesNotCallRawModelCardEndpoint", "/model-card"],
    ["doesNotCallRawEvaluationReportEndpoint", "/evaluation-report"],
    ["doesNotCallDatasetExportEndpoint", "/dataset"],
    ["doesNotCallPromotionEndpoint", "/promotion"],
    ["doesNotCallThresholdEndpoint", "/threshold"],
    ["doesNotCallDecisioningEndpoint", "/decisioning"],
    ["doesNotCallScoringEndpoint", "/scored"],
    ["doesNotCallModelRegistryEndpoint", "/model-registry"]
  ])("%s", async (_name, forbiddenPath) => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(shadowPerformanceSummary()));

    await getCurrentShadowPerformanceSummary();

    expect(fetchMock.mock.calls[0][0]).not.toContain(forbiddenPath);
  });

  it("doesNotSendMutationRequest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(shadowPerformanceSummary()));

    await getCurrentShadowPerformanceSummary({ method: "PATCH", body: "{}" });

    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("method");
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
  });

  it.each([
    ["doesNotCallRawModelCardEndpointOn404", "/model-card"],
    ["doesNotCallRawEvaluationReportEndpointOn404", "/evaluation-report"],
    ["doesNotCallDatasetExportEndpointOn404", "/dataset"],
    ["doesNotCallPromotionEndpointOn404", "/promotion"],
    ["doesNotCallThresholdEndpointOn404", "/threshold"],
    ["doesNotCallScoringEndpointOn404", "/scored"],
    ["doesNotCallModelRegistryEndpointOn404", "/model-registry"],
    ["doesNotCallHistoryEndpointOn404", "/history"],
    ["doesNotCallSearchEndpointOn404", "/search"],
    ["doesNotCallListAllEndpointOn404", "/summaries"]
  ])("%s", async (_name, forbiddenPath) => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ message: "not found" }, 404));

    await expect(getCurrentShadowPerformanceSummary()).rejects.toMatchObject({ status: 404 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/governance/shadow-performance/summary/current");
    expect(fetchMock.mock.calls[0][0]).not.toContain(forbiddenPath);
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

  it("prevents BFF callers from injecting Authorization headers or weakening credentials", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ content: [] }));
    const authProvider = await refreshedBffProvider("X-CSRF-TOKEN", "csrf-1");
    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), authProvider);

    await apiClient.getAlert("alert-1", {
      credentials: "omit",
      headers: {
        Authorization: "Bearer injected",
        authorization: "Bearer injected-lower",
        "X-Trace-Id": "trace-1"
      }
    });

    const options = fetchMock.mock.calls[0][1];
    expect(options.credentials).toBe("same-origin");
    expect(options.headers.Authorization).toBeUndefined();
    expect(options.headers.authorization).toBeUndefined();
    expect(options.headers["X-CSRF-TOKEN"]).toBe("csrf-1");
    expect(options.headers["X-Trace-Id"]).toBe("trace-1");
  });

  it("passes AbortSignal through detail read methods", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({})));
    const alertSignal = new AbortController().signal;
    const summarySignal = new AbortController().signal;
    const caseSignal = new AbortController().signal;
    const evidenceSummarySignal = new AbortController().signal;
    const evidenceTimelineSignal = new AbortController().signal;
    resetApiClient(normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] }));

    await getAlert("alert-1", { signal: alertSignal });
    await getAssistantSummary("alert-1", { signal: summarySignal });
    await getFraudCase("case-1", { signal: caseSignal });
    await getFraudCaseEvidenceSummary("case-1", { signal: evidenceSummarySignal });
    await getFraudCaseEvidenceTimeline("case-1", { signal: evidenceTimelineSignal });

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/alerts/alert-1", expect.objectContaining({ signal: alertSignal }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/alerts/alert-1/assistant-summary", expect.objectContaining({ signal: summarySignal }));
    expect(fetchMock).toHaveBeenNthCalledWith(3, "/api/v1/fraud-cases/case-1", expect.objectContaining({ signal: caseSignal }));
    expect(fetchMock).toHaveBeenNthCalledWith(4, fraudCaseEvidenceSummaryPath("case-1"), expect.objectContaining({ signal: evidenceSummarySignal }));
    expect(fetchMock).toHaveBeenNthCalledWith(5, fraudCaseEvidenceTimelinePath("case-1"), expect.objectContaining({ signal: evidenceTimelineSignal }));
  });

  it("FraudCaseEvidenceSummaryApiClientUsesFdp73EndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceSummary()));

    await getFraudCaseEvidenceSummary("case-1");

    expect(fetchMock).toHaveBeenCalledWith(
      fraudCaseEvidenceSummaryPath("case-1"),
      expect.objectContaining({ headers: expect.objectContaining({ "Content-Type": "application/json" }) })
    );
  });

  it("FraudCaseEvidenceSummaryApiClientEncodesCaseIdTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceSummary()));

    await getFraudCaseEvidenceSummary("case/with spaces");

    expect(fetchMock.mock.calls[0][0]).toBe(fraudCaseEvidenceSummaryPath("case%2Fwith%20spaces"));
  });

  it("FraudCaseEvidenceSummaryApiClientUsesCaseIdOnlyTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceSummary()));

    await getFraudCaseEvidenceSummary("case-1", {
      alertId: "alert-secret",
      linkedAlertId: "linked-secret",
      suspiciousTransactionId: "suspicious-secret",
      transactionId: "txn-secret",
      customerId: "customer-secret",
      accountId: "account-secret",
      evidenceId: "evidence-secret"
    });

    expect(fetchMock.mock.calls[0][0]).toBe(fraudCaseEvidenceSummaryPath("case-1"));
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain("alert-secret");
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain("suspicious-secret");
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain("customer-secret");
  });

  it("FraudCaseEvidenceSummaryApiClientDoesNotSendQuerySelectorsTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceSummary()));

    await getFraudCaseEvidenceSummary("case-1", {
      query: "customer-secret",
      params: { transactionId: "txn-secret" },
      headers: { "X-Transaction-Id": "txn-secret" }
    });

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(fraudCaseEvidenceSummaryPath("case-1"));
    expect(url).not.toContain("?");
    expect(options.headers).toEqual({ "Content-Type": "application/json" });
  });

  it("FraudCaseEvidenceSummaryApiClientDoesNotSendRequestBodyTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceSummary()));

    await getFraudCaseEvidenceSummary("case-1", {
      method: "POST",
      body: JSON.stringify({ customerId: "customer-secret" })
    });

    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("method");
    expect(JSON.stringify(fetchMock.mock.calls[0][1])).not.toContain("customer-secret");
  });

  it("FraudCaseEvidenceSummaryApiClientDoesNotCallAlertDetailsEndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceSummary()));

    await getFraudCaseEvidenceSummary("case-1");

    expect(fetchMock.mock.calls[0][0]).not.toBe(apiPath("api", "v1", "alerts", "case-1"));
  });

  it("FraudCaseEvidenceSummaryApiClientDoesNotCallSuspiciousTransactionEndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceSummary()));

    await getFraudCaseEvidenceSummary("case-1");

    expect(fetchMock.mock.calls[0][0]).not.toContain(apiPath("internal", "suspicious-transactions"));
  });

  it("FraudCaseEvidenceTimelineApiClientUsesFdp76EndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1");

    expect(fetchMock).toHaveBeenCalledWith(
      fraudCaseEvidenceTimelinePath("case-1"),
      expect.objectContaining({ headers: expect.objectContaining({ "Content-Type": "application/json" }) })
    );
  });

  it("FraudCaseEvidenceTimelineApiClientEncodesCaseIdTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case/with spaces");

    expect(fetchMock.mock.calls[0][0]).toBe(fraudCaseEvidenceTimelinePath("case%2Fwith%20spaces"));
  });

  it("FraudCaseEvidenceTimelineApiClientUsesCaseIdOnlyTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1", {
      alertId: "alert-secret",
      linkedAlertId: "linked-secret",
      suspiciousTransactionId: "suspicious-secret",
      transactionId: "txn-secret",
      customerId: "customer-secret",
      accountId: "account-secret",
      correlationId: "correlation-secret",
      sourceEventId: "source-event-secret",
      evidenceId: "evidence-secret",
      eventKey: "event-secret"
    });

    expect(fetchMock.mock.calls[0][0]).toBe(fraudCaseEvidenceTimelinePath("case-1"));
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain("alert-secret");
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain("suspicious-secret");
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain("customer-secret");
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain("event-secret");
  });

  it("FraudCaseEvidenceTimelineApiClientForwardsAbortSignalOnlyTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));
    const signal = new AbortController().signal;

    await getFraudCaseEvidenceTimeline("case-1", {
      signal,
      method: "POST",
      body: JSON.stringify({ customerId: "customer-secret" }),
      headers: { "X-Customer-Id": "customer-secret" }
    });

    expect(fetchMock).toHaveBeenCalledWith(
      fraudCaseEvidenceTimelinePath("case-1"),
      expect.objectContaining({ signal })
    );
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("method");
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
    expect(fetchMock.mock.calls[0][1].headers).toEqual({ "Content-Type": "application/json" });
  });

  it("FraudCaseEvidenceTimelineApiClientDoesNotSendBodyTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1", {
      body: JSON.stringify({ customerId: "customer-secret" })
    });

    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
    expect(JSON.stringify(fetchMock.mock.calls[0][1])).not.toContain("customer-secret");
  });

  it("FraudCaseEvidenceTimelineApiClientDoesNotSendQuerySelectorsTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1", {
      query: "customer-secret",
      params: { transactionId: "txn-secret" }
    });

    expect(fetchMock.mock.calls[0][0]).toBe(fraudCaseEvidenceTimelinePath("case-1"));
    expect(fetchMock.mock.calls[0][0]).not.toContain("?");
  });

  it("FraudCaseEvidenceTimelineApiClientDoesNotForwardCustomSelectorHeadersTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1", {
      headers: {
        "X-Alert-Id": "alert-secret",
        "X-Transaction-Id": "txn-secret"
      }
    });

    expect(fetchMock.mock.calls[0][1].headers).toEqual({ "Content-Type": "application/json" });
  });

  it("FraudCaseEvidenceTimelineApiClientDoesNotCallAuditEndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1");

    expect(fetchMock.mock.calls[0][0]).not.toContain("/audit");
  });

  it("FraudCaseEvidenceTimelineApiClientDoesNotCallAlertEndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1");

    expect(fetchMock.mock.calls[0][0]).not.toBe(apiPath("api", "v1", "alerts", "case-1"));
  });

  it("FraudCaseEvidenceTimelineApiClientDoesNotCallEvidenceSummaryEndpointAsFallbackTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("timeline failure"));

    await expect(getFraudCaseEvidenceTimeline("case-1")).rejects.toThrow("timeline failure");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).not.toBe(fraudCaseEvidenceSummaryPath("case-1"));
  });

  it("FraudCaseEvidenceTimelineApiClientDoesNotCallSuspiciousTransactionEndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(evidenceTimeline()));

    await getFraudCaseEvidenceTimeline("case-1");

    expect(fetchMock.mock.calls[0][0]).not.toContain(apiPath("internal", "suspicious-transactions"));
  });

  it("preserves AbortError from fetch", async () => {
    const abortError = new DOMException("aborted", "AbortError");
    vi.spyOn(globalThis, "fetch").mockRejectedValue(abortError);
    resetApiClient(normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] }));

    await expect(getAlert("alert-1")).rejects.toBe(abortError);
  });

  it("uses bff credentials without authorization for all FDP-48 read paths", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const authProvider = await refreshedBffProvider("X-CSRF-TOKEN", "csrf-read");
    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), authProvider);

    await listAlerts();
    await listFraudCaseWorkQueue();
    await getFraudCaseWorkQueueSummary();
    await listScoredTransactions();
    await getSuspiciousTransactionSummary();
    await listGovernanceAdvisories();
    await getGovernanceAdvisoryAnalytics();
    await getFraudCaseEvidenceTimeline("case-1");

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

  it("keeps explicit clients isolated from later provider and session switches", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const bffClient = createAlertsApiClient({
      session: normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }),
      authProvider: await refreshedBffProvider("X-CSRF-TOKEN", "csrf-original")
    });
    const oidcClient = createAlertsApiClient({
      session: normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }),
      authProvider: createOidcAuthProvider(createInMemoryOidcSessionSource({
        accessToken: "oidc-token-current",
        session: { userId: "oidc-analyst", roles: ["ANALYST"] }
      }))
    });

    await bffClient.listAlerts();
    await oidcClient.listAlerts();
    await bffClient.listAlerts();

    expect(fetchMock.mock.calls[0][1].headers["X-CSRF-TOKEN"]).toBe("csrf-original");
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe("Bearer oidc-token-current");
    expect(fetchMock.mock.calls[1][1].headers).not.toHaveProperty("X-CSRF-TOKEN");
    expect(fetchMock.mock.calls[2][1].headers["X-CSRF-TOKEN"]).toBe("csrf-original");
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBeUndefined();
  });

  it("uses refreshed OIDC bearer token for the same user and authorities", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const sessionSource = createInMemoryOidcSessionSource({
      accessToken: "token-old",
      session: { userId: "analyst-1", roles: ["ANALYST"] }
    });
    const authProvider = createOidcAuthProvider(sessionSource);

    await createAlertsApiClient({
      session: normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] }),
      authProvider
    }).listAlerts();
    sessionSource.replace({
      accessToken: "token-new",
      session: { userId: "analyst-1", roles: ["ANALYST"] },
      state: { status: "authenticated" }
    });
    await createAlertsApiClient({
      session: normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] }),
      authProvider
    }).listAlerts();

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer token-old");
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe("Bearer token-new");
  });

  it("uses refreshed BFF CSRF for the same user and authorities without Authorization", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const snapshots = [
      {
        authenticated: true,
        sessionStatus: "AUTHENTICATED",
        userId: "server-user-1",
        roles: ["FRAUD_OPS_ADMIN"],
        authorities: ["alert:read"],
        csrf: { headerName: "X-CSRF-TOKEN", token: "csrf-old" }
      },
      {
        authenticated: true,
        sessionStatus: "AUTHENTICATED",
        userId: "server-user-1",
        roles: ["FRAUD_OPS_ADMIN"],
        authorities: ["alert:read"],
        csrf: { headerName: "X-CSRF-TOKEN", token: "csrf-new" }
      }
    ];
    const authProvider = createBffAuthProvider(vi.fn()
      .mockResolvedValueOnce(snapshots[0])
      .mockResolvedValueOnce(snapshots[1]));

    const session = normalizeSession(await authProvider.refreshSession());
    await createAlertsApiClient({ session, authProvider }).listAlerts();
    const refreshedSession = normalizeSession(await authProvider.refreshSession());
    await createAlertsApiClient({ session: refreshedSession, authProvider }).listAlerts();

    expect(fetchMock.mock.calls[0][1].headers["X-CSRF-TOKEN"]).toBe("csrf-old");
    expect(fetchMock.mock.calls[1][1].headers["X-CSRF-TOKEN"]).toBe("csrf-new");
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBeUndefined();
  });

  it("does not persist BFF csrf headers to browser storage", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      operation_status: "COMMITTED"
    }));
    const authProvider = await refreshedBffProvider("X-CSRF-TOKEN", "csrf-storage-check");
    resetApiClient(normalizeSession({ userId: "server-user-1", roles: ["FRAUD_OPS_ADMIN"] }), authProvider);

    await submitAnalystDecision("alert-1", { decision: "MARKED_LEGITIMATE" }, { idempotencyKey: "alert-decision-alert-1-key" });

    expect(fetchMock.mock.calls[0][1].headers["X-CSRF-TOKEN"]).toBe("csrf-storage-check");
    expect(storageContains(window.localStorage, "csrf-storage-check")).toBe(false);
    expect(storageContains(window.sessionStorage, "csrf-storage-check")).toBe(false);
  });

  it("creates a logged-out client without stale Authorization or CSRF headers", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })));
    const oidcProvider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "stale-token",
      session: { userId: "oidc-analyst", roles: ["ANALYST"] }
    }));
    const loggedOutClient = createAlertsApiClient({
      session: normalizeSession({}),
      authProvider: createDemoAuthProvider()
    });

    await createAlertsApiClient({
      session: normalizeSession({ userId: "oidc-analyst", roles: ["ANALYST"] }),
      authProvider: oidcProvider
    }).listAlerts();
    await loggedOutClient.listAlerts();

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer stale-token");
    expect(fetchMock.mock.calls[1][1].headers).toEqual({ "Content-Type": "application/json" });
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

  it("loads scored transaction detail through the FDP-115 detail GET path only", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(scoredTransactionDetail()));

    const result = await getScoredTransactionDetail(" txn:1 ", {
      signal,
      method: "POST",
      body: JSON.stringify({ ignored: true }),
      search: "?ignored=true"
    });

    expect(result.transactionId).toBe("txn:1");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/transactions/scored/txn%3A1",
      expect.objectContaining({
        signal,
        headers: expect.objectContaining({ "Content-Type": "application/json" })
      })
    );
    expect(fetchMock.mock.calls[0][0]).not.toContain("/engine-intelligence");
    expect(fetchMock.mock.calls[0][0]).not.toContain("feedback");
    expect(fetchMock.mock.calls[0][1]).not.toEqual(expect.objectContaining({
      method: expect.any(String),
      body: expect.any(String)
    }));
  });

  it("uses existing auth headers for scored transaction detail", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(scoredTransactionDetail()));
    resetApiClient(normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] }), createDemoAuthProvider());

    await getScoredTransactionDetail("txn-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/transactions/scored/txn-1",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Demo-User-Id": "analyst-1",
          "Content-Type": "application/json"
        })
      })
    );
  });

  it("loads fraud feedback through the API client boundary", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ feedbackId: "feedback-1" }));

    await getFraudFeedback(" txn:1 ", { signal });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/transactions/scored/txn%3A1/feedback",
      expect.objectContaining({
        signal,
        headers: expect.objectContaining({ "Content-Type": "application/json" })
      })
    );
  });

  it("creates fraud feedback through the API client boundary", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ feedbackId: "feedback-1" }));
    const feedback = {
      analystDecision: "MARKED_FRAUD",
      feedbackLabel: "CONFIRMED_FRAUD",
      decisionReasonCodes: ["CUSTOMER_CONFIRMED_FRAUD"]
    };

    await createFraudFeedback("txn-1", feedback);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/transactions/scored/txn-1/feedback",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify(feedback),
        headers: expect.objectContaining({ "Content-Type": "application/json" })
      })
    );
  });

  it.each([400, 401, 403, 404, 503])("propagates scored transaction detail %s errors", async (status) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      error: `E${status}`,
      message: `status ${status}`,
      details: ["reason:test"]
    }, status));

    await expect(getScoredTransactionDetail("txn-1")).rejects.toMatchObject({
      status,
      message: expect.stringContaining(`status ${status}`)
    });
  });

  it("rejects invalid scored transaction detail identifiers without a request", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(scoredTransactionDetail()));

    await expect(getScoredTransactionDetail("approve transaction")).rejects.toMatchObject({
      status: 400,
      error: "INVALID_TRANSACTION_ID"
    });

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("uses cursor slice params for suspicious transaction reads without page or totals", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      content: [],
      size: 20,
      hasNext: false,
      nextCursor: null
    }));
    const cursor = "opaque.cursor/with+symbols==";

    await listSuspiciousTransactions({
      size: 250,
      cursor,
      status: "NEW",
      riskLevel: "CRITICAL",
      customerId: " customer-1 ",
      linkedAlertId: "",
      detectedFrom: "2026-05-13T08:15",
      detectedTo: null
    });

    const url = fetchMock.mock.calls[0][0];
    const query = new URLSearchParams(url.split("?")[1]);
    expect(url).toContain("/internal/suspicious-transactions?");
    expect(query.get("size")).toBe("100");
    expect(query.get("cursor")).toBe(cursor);
    expect(query.get("status")).toBe("NEW");
    expect(query.get("riskLevel")).toBe("CRITICAL");
    expect(query.get("customerId")).toBe("customer-1");
    expect(query.get("detectedFrom")).toBe(new Date("2026-05-13T08:15").toISOString());
    expect(url).not.toContain("page=");
    expect(url).not.toContain("totalElements");
    expect(url).not.toContain("totalPages");
    expect(url).not.toContain("linkedAlertId=");
  });

  it("loads a suspicious transaction detail through the internal GET path only", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      suspiciousTransactionId: "suspicious-1",
      transactionId: "txn-1"
    }));

    await getSuspiciousTransaction("suspicious/1", { signal });

    expect(fetchMock).toHaveBeenCalledWith(
      "/internal/suspicious-transactions/suspicious%2F1",
      expect.objectContaining({
        signal,
        headers: expect.objectContaining({ "Content-Type": "application/json" })
      })
    );
    expect(fetchMock.mock.calls[0][1]).not.toEqual(expect.objectContaining({ method: expect.any(String) }));
  });

  it("loads suspicious transaction global summary through the internal summary path only", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      totalSuspiciousTransactions: 98
    }));

    await getSuspiciousTransactionSummary({ signal });

    expect(fetchMock).toHaveBeenCalledWith(
      "/internal/suspicious-transactions/summary",
      expect.objectContaining({
        signal,
        headers: expect.objectContaining({ "Content-Type": "application/json" })
      })
    );
    expect(fetchMock.mock.calls[0][1]).not.toEqual(expect.objectContaining({ method: expect.any(String) }));
  });

  it("SuspiciousLinkedAlertClientCallsInternalResolverTest", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "LINKED_ALERT_AVAILABLE",
      alertId: "alert-1"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", { signal });

    expect(fetchMock).toHaveBeenCalledWith(
      "/internal/suspicious-transactions/suspicious-1/linked-alert",
      expect.objectContaining({
        signal,
        headers: expect.objectContaining({ "Content-Type": "application/json" })
      })
    );
  });

  it("SuspiciousLinkedAlertClientDoesNotForwardCustomHeadersTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", {
      headers: {
        "X-Alert-Id": "alert-secret",
        "X-Linked-Alert-Id": "linked-alert-secret",
        "X-Customer-Id": "customer-secret",
        "X-Transaction-Id": "txn-secret",
        "X-Correlation-Id": "correlation-secret"
      }
    });

    const options = fetchMock.mock.calls[0][1];
    expect(options.headers).toEqual({ "Content-Type": "application/json" });
    expect(JSON.stringify(options)).not.toContain("alert-secret");
    expect(JSON.stringify(options)).not.toContain("linked-alert-secret");
    expect(JSON.stringify(options)).not.toContain("customer-secret");
    expect(JSON.stringify(options)).not.toContain("txn-secret");
    expect(JSON.stringify(options)).not.toContain("correlation-secret");
  });

  it("SuspiciousLinkedAlertClientDoesNotDisableAuthTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", {
      includeAuth: false
    });

    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("includeAuth");
    expect(fetchMock.mock.calls[0][1]).not.toEqual(expect.objectContaining({ includeAuth: false }));
  });

  it("SuspiciousLinkedAlertClientOnlyForwardsAbortSignalTest", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", {
      signal,
      includeAuth: false,
      headers: { "X-Alert-Id": "alert-secret" },
      body: JSON.stringify({ alertId: "alert-secret" }),
      method: "POST"
    });

    const options = fetchMock.mock.calls[0][1];
    expect(options).toEqual({
      signal,
      headers: { "Content-Type": "application/json" }
    });
    expect(JSON.stringify(options)).not.toContain("includeAuth");
    expect(JSON.stringify(options)).not.toContain("alert-secret");
    expect(options).not.toHaveProperty("body");
    expect(options).not.toHaveProperty("method");
    expect(options).not.toHaveProperty("includeAuth");
  });

  it("SuspiciousLinkedAlertClientDoesNotForwardAlertSelectorHeadersTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));
    const forbiddenHeaders = [
      "X-Alert-Id",
      "X-Linked-Alert-Id",
      "X-Customer-Id",
      "X-Account-Id",
      "X-Transaction-Id",
      "X-Correlation-Id",
      "X-Score-Decision-Id"
    ];

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", {
      headers: Object.fromEntries(forbiddenHeaders.map((header) => [header, `${header}-secret`]))
    });

    const headers = fetchMock.mock.calls[0][1].headers;
    for (const header of forbiddenHeaders) {
      expect(headers).not.toHaveProperty(header);
    }
  });

  it("SuspiciousLinkedAlertClientStillSupportsAbortSignalTest", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", { signal });

    expect(fetchMock).toHaveBeenCalledWith(
      "/internal/suspicious-transactions/suspicious-1/linked-alert",
      expect.objectContaining({ signal })
    );
  });

  it("SuspiciousLinkedAlertClientDoesNotSendRequestBodyTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", {
      body: JSON.stringify({ alertId: "alert-secret" })
    });

    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
    expect(JSON.stringify(fetchMock.mock.calls[0][1])).not.toContain("alert-secret");
  });

  it("SuspiciousLinkedAlertClientSourceDoesNotForwardHeadersTest", () => {
    const linkedOptions = alertsApiSource().match(/function linkedAlertContextRequestOptions[\s\S]*?\n}/)?.[0] || "";

    expect(linkedOptions).not.toContain("headers");
    expect(linkedOptions).not.toContain("includeAuth");
    expect(linkedOptions).not.toContain("...requestOptions");
    expect(linkedOptions).not.toContain("...options");
  });

  it("SuspiciousLinkedAlertClientSourceDoesNotMentionAlertSelectorHeadersTest", () => {
    const source = alertsApiSource();
    const forbiddenHeaders = [
      "X-Alert-Id",
      "X-Linked-Alert-Id",
      "X-Customer-Id",
      "X-Transaction-Id",
      "X-Correlation-Id",
      "X-Score-Decision-Id"
    ];

    for (const header of forbiddenHeaders) {
      expect(source).not.toContain(header);
    }
  });

  it("SuspiciousLinkedAlertClientEncodesSuspiciousTransactionIdTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious/with spaces");

    expect(fetchMock.mock.calls[0][0]).toBe("/internal/suspicious-transactions/suspicious%2Fwith%20spaces/linked-alert");
  });

  it("SuspiciousLinkedAlertClientDoesNotAcceptAlertIdArgumentTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1", { alertId: "alert-secret" });

    expect(fetchMock.mock.calls[0][0]).not.toContain("alert-secret");
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("alertId");
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty("body");
  });

  it("SuspiciousLinkedAlertClientDoesNotAppendAlertIdQueryParamTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1");

    expect(fetchMock.mock.calls[0][0]).not.toContain("alertId=");
    expect(fetchMock.mock.calls[0][0]).not.toContain("linkedAlertId=");
  });

  it("SuspiciousLinkedAlertClientDoesNotCallGeneralAlertEndpointTest", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      state: "NO_LINKED_ALERT"
    }));

    await getSuspiciousTransactionLinkedAlertContext("suspicious-1");

    expect(fetchMock.mock.calls[0][0]).not.toContain(["", "api", "v1", "alerts"].join("/"));
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

  it("sends fraud case update with required idempotency header and signal", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      operation_status: "COMMITTED",
      updated_case: { caseId: "case-1", status: "CLOSED" }
    }));
    const signal = new AbortController().signal;
    await updateFraudCase("case-1", {
      status: "CLOSED",
      analystId: "analyst-1",
      decisionReason: "Reviewed",
      tags: ["reviewed"]
    }, { idempotencyKey: "fraud-case-update-case-1-key", signal });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/fraud-cases/case-1", expect.objectContaining({
      method: "PATCH",
      signal,
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "X-Idempotency-Key": "fraud-case-update-case-1-key"
      })
    }));
  });

  it("sends analyst decision with idempotency header and signal", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      resultingStatus: "RESOLVED"
    }));
    const signal = new AbortController().signal;
    await submitAnalystDecision("alert-1", {
      analystId: "analyst-1",
      decision: "MARKED_LEGITIMATE",
      decisionReason: "Reviewed",
      tags: ["manual-review"]
    }, { idempotencyKey: "alert-decision-alert-1-key", signal });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts/alert-1/decision", expect.objectContaining({
      method: "POST",
      signal,
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "X-Idempotency-Key": "alert-decision-alert-1-key"
      })
    }));
  });

  it("getEngineIntelligenceReturnsAvailableData", async () => {
    const signal = new AbortController().signal;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable()));

    const result = await getEngineIntelligence("txn.valid:001", { signal });

    expect(fetchMock).toHaveBeenCalledWith(
      engineIntelligenceEndpoint("txn.valid%3A001"),
      expect.objectContaining({ signal })
    );
    expect(result).toMatchObject({
      state: "available",
      available: true,
      comparison: {
        agreementStatus: "DISAGREEMENT",
        riskMismatchStatus: "MATERIAL_RISK_MISMATCH",
        scoreDeltaBucket: "LARGE"
      }
    });
    expect(result.engines[0]).toEqual({
      engineId: "rules.primary",
      engineType: "RULES",
      status: "AVAILABLE",
      scoreBucket: "HIGH",
      riskLevel: "HIGH",
      reasonCodes: ["HIGH_VELOCITY"]
    });
  });

  it("getEngineIntelligenceReturnsNotProjectedState", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      transactionId: "txn-old",
      available: false,
      reason: "NOT_PROJECTED"
    }));

    await expect(getEngineIntelligence("txn-old")).resolves.toEqual({
      state: "not-projected",
      available: false,
      transactionId: "txn-old",
      reason: "NOT_PROJECTED"
    });
  });

  it.each([
    [403, "unauthorized"],
    [401, "unauthorized"],
    [404, "not-found"],
    [503, "unavailable"]
  ])("getEngineIntelligenceHandles%s", async (status, state) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      message: ["raw backend failure", engineIntelligenceEndpoint("txn-1"), "token-secret stacktrace"].join(" ")
    }, status));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({ state, transactionId: "txn-1" });
  });

  it("getEngineIntelligenceHandlesNetworkFailureSafely", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("network raw failure with endpoint and token"));

    await expect(getEngineIntelligence("txn-1")).resolves.toEqual({
      state: "unavailable",
      available: false,
      transactionId: "txn-1"
    });
  });

  it("getEngineIntelligenceFailsClosedOnUnexpectedShape", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      transactionId: "txn-1",
      available: true,
      comparison: null,
      rawEvidence: "secret"
    }));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({ state: "unavailable" });
  });

  it("getEngineIntelligenceDoesNotExposeRawErrorBody", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      message: "raw backend failure",
      details: [`endpoint:${engineIntelligenceEndpoint("txn-1")}`, "token-secret", "stacktrace"]
    }, 503));

    const result = await getEngineIntelligence("txn-1");

    expect(JSON.stringify(result)).not.toContain("raw backend failure");
    expect(JSON.stringify(result)).not.toContain("endpoint");
    expect(JSON.stringify(result)).not.toContain("token-secret");
    expect(JSON.stringify(result)).not.toContain("stacktrace");
  });

  it.each([
    ["getEngineIntelligenceBlankTransactionIdDoesNotCallFetch", "   ", ""],
    ["getEngineIntelligenceNullTransactionIdDoesNotCallFetch", null, ""],
    ["getEngineIntelligenceOverlongTransactionIdDoesNotCallFetch", "a".repeat(129), "a".repeat(129)],
    ["getEngineIntelligenceControlCharTransactionIdDoesNotCallFetch", "txn-\n1", "txn-\n1"],
    ["getEngineIntelligenceInvalidPatternTransactionIdDoesNotCallFetch", "txn/with spaces", "txn/with spaces"]
  ])("%s", async (_name, transactionId, expectedTransactionId) => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable()));

    await expect(getEngineIntelligence(transactionId)).resolves.toEqual({
      state: "not-found",
      available: false,
      transactionId: expectedTransactionId
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("getEngineIntelligenceValidTransactionIdCallsFdp96Endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable()));

    await getEngineIntelligence("txn-1");

    expect(fetchMock).toHaveBeenCalledWith(
      engineIntelligenceEndpoint("txn-1"),
      expect.any(Object)
    );
  });

  it("submitFeedbackSendsStructuredPayloadOnlyWithIdempotencyKey", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceFeedbackResponse()));

    const result = await submitEngineIntelligenceFeedback("txn-1", {
      feedbackType: "ENGINE_INTELLIGENCE_USEFULNESS",
      usefulness: "HELPFUL",
      accuracyAssessment: "SIGNALS_LOOK_CORRECT",
      engineIntelligenceAvailable: true,
      selectedReasonCodes: ["HIGH_VELOCITY"],
      fraudCaseId: "case-1",
      submittedBy: "payload-user",
      submittedAt: "2026-01-01T00:00:00Z",
      rawEngineIntelligence: engineIntelligenceAvailable(),
      comment: "free text"
    }, { idempotencyKey: "feedback-key-1" });

    expect(result).toMatchObject({ state: "saved", operationStatus: "CREATED", feedbackId: "feedback-1" });
    expect(fetchMock).toHaveBeenCalledWith(
      engineIntelligenceFeedbackEndpoint("txn-1"),
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ "X-Idempotency-Key": "feedback-key-1" })
      })
    );
    const payload = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(payload).toEqual({
      feedbackType: "ENGINE_INTELLIGENCE_USEFULNESS",
      usefulness: "HELPFUL",
      accuracyAssessment: "SIGNALS_LOOK_CORRECT",
      engineIntelligenceAvailable: true,
      selectedReasonCodes: ["HIGH_VELOCITY"]
    });
    expect(payload).not.toHaveProperty("fraudCaseId");
    expect(JSON.stringify(payload)).not.toContain("submittedBy");
    expect(JSON.stringify(payload)).not.toContain("submittedAt");
    expect(JSON.stringify(payload)).not.toContain("rawEngineIntelligence");
    expect(JSON.stringify(payload)).not.toContain("comment");
  });

  it("submitFeedbackRejectsInvalidStructuredValuesWithoutCallingFetch", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceFeedbackResponse()));

    const result = await submitEngineIntelligenceFeedback("txn-1", {
      feedbackType: "finalDecision",
      usefulness: "HELPFUL",
      accuracyAssessment: "SIGNALS_LOOK_CORRECT",
      engineIntelligenceAvailable: true
    }, { idempotencyKey: "feedback-key-1" });

    expect(result).toEqual({ state: "validation-error" });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    [400, "validation-error"],
    [422, "validation-error"],
    [401, "unauthorized"],
    [403, "unauthorized"],
    [404, "not-found"],
    [409, "validation-error"],
    [503, "unavailable"]
  ])("submitFeedbackMaps%sSafely", async (status, state) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      message: "raw payload token stacktrace endpoint must not be shown"
    }, status));

    const result = await submitEngineIntelligenceFeedback("txn-1", {
      feedbackType: "ENGINE_INTELLIGENCE_USEFULNESS",
      usefulness: "HELPFUL",
      accuracyAssessment: "SIGNALS_LOOK_CORRECT",
      engineIntelligenceAvailable: true
    }, { idempotencyKey: "feedback-key-1" });

    expect(result).toMatchObject({ state });
    expect(JSON.stringify(result)).not.toMatch(/payload|token|stacktrace|endpoint/i);
  });

  it.each([
    ["getEngineIntelligenceFailsClosedForOversizedEngines", { engines: [engineResult(), engineResult({ engineId: "ml.python.primary", engineType: "ML_MODEL", status: "TIMEOUT", riskLevel: undefined, scoreBucket: "UNAVAILABLE", reasonCodes: ["ML_MODEL_TIMEOUT"] }), engineResult({ engineId: "rules.secondary" })] }],
    ["getEngineIntelligenceFailsClosedForOversizedDiagnosticSignals", { diagnosticSignals: Array.from({ length: 6 }, (_value, index) => diagnosticSignal({ engineId: `rules.${index}` })) }],
    ["getEngineIntelligenceFailsClosedForOversizedWarnings", { warnings: Array.from({ length: 11 }, () => warning()) }],
    ["getEngineIntelligenceFailsClosedForOversizedEngineReasonCodes", { engines: [engineResult({ reasonCodes: ["A", "B", "C", "D", "E", "F"] })] }],
    ["getEngineIntelligenceFailsClosedForOversizedSignalReasonCodes", { diagnosticSignals: [diagnosticSignal({ reasonCodes: ["A", "B", "C", "D", "E", "F"] })] }]
  ])("%s", async (_name, patch) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable(patch)));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({ state: "unavailable", available: false });
  });

  it.each([
    ["getEngineIntelligenceFailsClosedForUnknownAgreementStatus", { comparison: { ...comparison(), agreementStatus: "FINAL_DECLINE" } }],
    ["getEngineIntelligenceFailsClosedForUnknownRiskMismatchStatus", { comparison: { ...comparison(), riskMismatchStatus: "WINNING_ENGINE" } }],
    ["getEngineIntelligenceFailsClosedForUnknownScoreDeltaBucket", { comparison: { ...comparison(), scoreDeltaBucket: "PLATFORM_VERDICT" } }],
    ["getEngineIntelligenceFailsClosedForUnknownEngineStatus", { engines: [engineResult({ status: "RECOMMENDED_ACTION" })] }],
    ["getEngineIntelligenceFailsClosedForUnknownScoreBucket", { engines: [engineResult({ scoreBucket: "APPROVE" })] }],
    ["getEngineIntelligenceFailsClosedForUnknownSignalCategory", { diagnosticSignals: [diagnosticSignal({ signalCategory: "DECLINE" })] }],
    ["getEngineIntelligenceFailsClosedForDecisioningLikeEnumValues", { engines: [engineResult({ engineType: "BLOCK" })] }]
  ])("%s", async (_name, patch) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable(patch)));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({ state: "unavailable", available: false });
  });

  it.each([
    ["getEngineIntelligenceFailsClosedForRawEvidenceReasonCode", { engines: [engineResult({ reasonCodes: ["rawEvidence"] })] }],
    ["getEngineIntelligenceFailsClosedForTokenSecretReasonCode", { engines: [engineResult({ reasonCodes: ["token-secret-stacktrace"] })] }],
    ["getEngineIntelligenceFailsClosedForStacktraceWarningCode", { warnings: [warning({ warningCode: "stacktrace" })] }],
    ["getEngineIntelligenceFailsClosedForEndpointEngineId", { engines: [engineResult({ engineId: "endpoint-api-v1" })] }],
    ["getEngineIntelligenceFailsClosedForInternalProjectionClassName", { diagnosticSignals: [diagnosticSignal({ reasonCode: "EngineIntelligenceProjection" })] }],
    ["getEngineIntelligenceFailsClosedForDecisioningTermInsideAllowedField", { engines: [engineResult({ reasonCodes: ["recommendedAction"] })] }]
  ])("%s", async (_name, patch) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable(patch)));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({ state: "unavailable", available: false });
  });

  it.each([
    ["getEngineIntelligenceFailsClosedForTimeoutWithHighScoreBucket", { engines: [engineResult({ status: "TIMEOUT", riskLevel: undefined, scoreBucket: "HIGH" })] }],
    ["getEngineIntelligenceFailsClosedForUnavailableWithLowRiskLevel", { engines: [engineResult({ status: "UNAVAILABLE", riskLevel: "LOW", scoreBucket: "UNAVAILABLE" })] }],
    ["getEngineIntelligenceFailsClosedForDegradedWithRiskLevel", { engines: [engineResult({ status: "DEGRADED", riskLevel: "HIGH", scoreBucket: "UNAVAILABLE" })] }],
    ["getEngineIntelligenceFailsClosedForOperationalSignalWithRiskLevel", { diagnosticSignals: [diagnosticSignal({ signalCategory: "OPERATIONAL_SIGNAL", engineStatus: "TIMEOUT", riskLevel: "LOW", scoreBucket: "UNAVAILABLE" })] }]
  ])("%s", async (_name, patch) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable(patch)));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({ state: "unavailable", available: false });
  });

  it("getEngineIntelligenceAcceptsOperationalSignalWithoutRiskLevel", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable({
      diagnosticSignals: [diagnosticSignal({
        signalCategory: "OPERATIONAL_SIGNAL",
        engineId: "ml.python.primary",
        engineType: "ML_MODEL",
        engineStatus: "TIMEOUT",
        riskLevel: undefined,
        scoreBucket: "UNAVAILABLE",
        reasonCode: "ML_MODEL_TIMEOUT"
      })]
    })));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({
      state: "available",
      diagnosticSignals: [expect.objectContaining({ signalCategory: "OPERATIONAL_SIGNAL", riskLevel: "" })]
    });
  });

  it("getEngineIntelligenceUsesSignalCategoryFromFdp96Contract", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable({
      diagnosticSignals: [diagnosticSignal({ signalCategory: "FRAUD_SIGNAL" })]
    })));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({
      state: "available",
      diagnosticSignals: [expect.objectContaining({ signalCategory: "FRAUD_SIGNAL" })]
    });
  });

  it("getEngineIntelligenceFailsClosedForSignalTypeOnlyResponse", async () => {
    const signal = diagnosticSignal();
    delete signal.signalCategory;
    signal.signalType = "FRAUD_SIGNAL";
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(engineIntelligenceAvailable({
      diagnosticSignals: [signal]
    })));

    await expect(getEngineIntelligence("txn-1")).resolves.toMatchObject({ state: "unavailable", available: false });
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

function evidenceSummary() {
  return {
    caseId: "case-1",
    aggregateEvidenceStatus: "AVAILABLE",
    topReasonCodes: [],
    highestSeverityEvidence: [],
    evidenceBySource: [],
    evidenceByStatus: [],
    linkedAlertCount: 0,
    evidenceItemCount: 0,
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

function evidenceTimeline() {
  return {
    caseId: "case-1",
    generatedAt: "2026-05-23T10:00:00Z",
    events: [],
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

function engineIntelligenceAvailable(overrides = {}) {
  return {
    transactionId: "txn.valid:001",
    available: true,
    contractVersion: 1,
    generatedAt: "2026-06-02T10:00:00Z",
    comparison: comparison(),
    engines: [engineResult()],
    diagnosticSignals: [diagnosticSignal()],
    warnings: [warning()],
    ...overrides
  };
}

function shadowPerformanceSummary(overrides = {}) {
  return {
    summaryType: "SHADOW_PERFORMANCE_SUMMARY_V1",
    summaryVersion: "1.0",
    generatedAt: "2026-06-08T02:00:00Z",
    model: {
      modelName: "python-logistic-fraud-model",
      modelVersion: "2026-04-21.trained.v1",
      modelFamily: "LOGISTIC_REGRESSION",
      featureContractVersion: "2026-04-22.v1"
    },
    governance: {
      governanceStatus: "DIAGNOSTIC_ONLY",
      approvedFor: ["COMPARE", "SHADOW"],
      diagnosticOnly: true,
      notProductionApproval: true,
      notPromotionApproval: true,
      notThresholdRecommendation: true,
      notPaymentAuthorization: true,
      notAutomaticDecisioning: true
    },
    evaluation: {
      evaluationReportType: "PYTHON_ML_EVALUATION_FOUNDATION",
      evaluationReportVersion: "FDP-103",
      metricBasis: "bucket_ordered_offline_diagnostic",
      datasetTimeBasis: "FEEDBACK_SUBMITTED_AT",
      datasetDeduplicationPolicy: "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC"
    },
    evaluationPopulation: {
      datasetRecordsRead: 5,
      recordsAcceptedForEvaluation: 3,
      recordsExcludedNotEvaluationEligible: 1
    },
    metrics: {
      precisionAtBudget: 0.666667,
      recallAtTopK: 0.5,
      falsePositiveRate: 0.25,
      mlCaughtRulesMissedCount: 1,
      rulesCaughtMlMissedCount: 1,
      missingMlCount: 1,
      missingRulesCount: 1,
      missingProjectionCount: 1,
      notEvaluationEligibleCount: 1
    },
    disagreementSummary: {
      rulesHighMlHigh: 1,
      rulesHighMlLowOrMedium: 0,
      rulesLowOrMediumMlHigh: 1,
      rulesLowOrMediumMlLowOrMedium: 1,
      rulesMissingMlPresent: 0,
      mlMissingRulesPresent: 1,
      bothMissing: 0,
      notEvaluationEligibleExcluded: 1
    },
    warnings: ["MISSING_ML_SIGNAL_PRESENT"],
    limitations: ["DIAGNOSTIC_ONLY"],
    banner: "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.",
    ...overrides
  };
}

function promotionReviewReadinessReport(overrides = {}) {
  return {
    reportType: "PROMOTION_REVIEW_READINESS_REPORT_V1",
    reportVersion: "1.0",
    generatedAt: "2026-06-13T00:00:00Z",
    governanceStatus: "DIAGNOSTIC_ONLY",
    readinessStatus: "REVIEWABLE",
    diagnosticOnly: true,
    notPromotionApproval: true,
    notThresholdRecommendation: true,
    notProductionDecisioning: true,
    notPaymentAuthorization: true,
    notAutomaticDecisioning: true,
    notAnalystRecommendation: true,
    inputs: {
      shadowPerformanceSummary: {
        present: true,
        summaryType: "SHADOW_PERFORMANCE_SUMMARY_V1",
        summaryVersion: "1.0",
        generatedAt: "2026-06-08T02:00:00Z"
      },
      minimumDiagnosticEvidenceRecords: 1,
      recordsAcceptedForEvaluation: 3
    },
    checks: [
      { name: "CURRENT_SUMMARY_PRESENT", status: "PASS", severity: "INFO" }
    ],
    reasonCodes: [],
    warnings: ["MISSING_ML_SIGNAL_PRESENT"],
    limitations: ["OFFLINE_DIAGNOSTIC_AID_ONLY"],
    banner: "Promotion review readiness is an offline diagnostic aid only. It is not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.",
    ...overrides
  };
}

function comparison() {
  return {
    agreementStatus: "DISAGREEMENT",
    riskMismatchStatus: "MATERIAL_RISK_MISMATCH",
    scoreDeltaBucket: "LARGE"
  };
}

function scoredTransactionDetail(overrides = {}) {
  return {
    transactionId: "txn:1",
    correlationId: "corr-1",
    transactionTimestamp: "2026-06-18T10:00:00Z",
    scoredAt: "2026-06-18T10:00:01Z",
    fraudScore: 0.91,
    riskLevel: "CRITICAL",
    alertRecommended: true,
    reasonCodes: ["HIGH_VELOCITY"],
    engineIntelligence: {
      status: "AVAILABLE",
      contractVersion: 1,
      generatedAt: "2026-06-18T10:00:02Z",
      comparison: comparison(),
      engines: [{
        engineId: "rules.primary",
        engineType: "RULES",
        status: "AVAILABLE",
        riskLevel: "CRITICAL",
        scoreBucket: "HIGH",
        reasonCodes: ["HIGH_VELOCITY"]
      }],
      diagnosticSignals: [],
      warnings: []
    },
    ...overrides
  };
}

function engineResult(overrides = {}) {
  return {
    engineId: "rules.primary",
    engineType: "RULES",
    status: "AVAILABLE",
    riskLevel: "HIGH",
    scoreBucket: "HIGH",
    reasonCodes: ["HIGH_VELOCITY"],
    rawEvidence: "must not leak",
    ...overrides
  };
}

function diagnosticSignal(overrides = {}) {
  return {
    engineId: "rules.primary",
    engineType: "RULES",
    engineStatus: "AVAILABLE",
    signalCategory: "FRAUD_SIGNAL",
    scoreBucket: "HIGH",
    riskLevel: "HIGH",
    reasonCode: "HIGH_VELOCITY",
    ...overrides
  };
}

function warning(overrides = {}) {
  return {
    warningCode: "EVIDENCE_UNSAFE_DROPPED",
    count: 1,
    rawPayload: "must not leak",
    ...overrides
  };
}

function engineIntelligenceFeedbackResponse(overrides = {}) {
  return {
    feedbackId: "feedback-1",
    transactionId: "txn-1",
    engineIntelligenceAvailable: true,
    feedbackType: "ENGINE_INTELLIGENCE_USEFULNESS",
    usefulness: "HELPFUL",
    accuracyAssessment: "SIGNALS_LOOK_CORRECT",
    selectedReasonCodes: ["HIGH_VELOCITY"],
    submittedAt: "2026-06-03T10:00:00Z",
    operationStatus: "CREATED",
    ...overrides
  };
}

function engineIntelligenceEndpoint(encodedTransactionId) {
  return ["/api", "v1", "transactions", "scored", encodedTransactionId, "engine-intelligence"].join("/");
}

function engineIntelligenceFeedbackEndpoint(encodedTransactionId) {
  return [...engineIntelligenceEndpoint(encodedTransactionId).split("/"), "feedback"].join("/");
}

function fraudCaseEvidenceSummaryPath(caseId) {
  return apiPath("api", "v1", "fraud-cases", caseId, "evidence-summary");
}

function fraudCaseEvidenceTimelinePath(caseId) {
  return apiPath("api", "v1", "fraud-cases", caseId, "evidence-timeline");
}

function apiPath(...segments) {
  return `/${segments.join("/")}`;
}

function storageContains(storage, needle) {
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index);
    if (key?.includes(needle) || storage.getItem(key)?.includes(needle)) {
      return true;
    }
  }
  return false;
}

function alertsApiSource() {
  return readFileSync(resolve(process.cwd(), "src/api/alertsApi.js"), "utf8");
}
