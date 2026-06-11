import { render, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WORKSPACE_ROUTE_REGISTRY } from "./WorkspaceRouteRegistry.jsx";
import { WorkspaceRuntimeContext } from "./useWorkspaceRuntime.js";

const apiClient = {
  getFraudCaseWorkQueueSummary: vi.fn(),
  listFraudCaseWorkQueue: vi.fn(),
  listAlerts: vi.fn(),
  listScoredTransactions: vi.fn(),
  listSuspiciousTransactions: vi.fn(),
  getSuspiciousTransaction: vi.fn(),
  listGovernanceAdvisories: vi.fn(),
  getGovernanceAdvisoryAnalytics: vi.fn(),
  getCurrentShadowPerformanceSummary: vi.fn(),
  getGovernanceAdvisoryAudit: vi.fn(),
  recordGovernanceAdvisoryAudit: vi.fn()
};

describe("workspace runtime ownership", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.getFraudCaseWorkQueueSummary.mockResolvedValue({ totalFraudCases: 1 });
    apiClient.listFraudCaseWorkQueue.mockResolvedValue(workQueueSlice([{ caseId: "case-1" }]));
    apiClient.listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }]));
    apiClient.listScoredTransactions.mockResolvedValue(page([{ transactionId: "txn-1" }]));
    apiClient.listSuspiciousTransactions.mockResolvedValue(suspiciousSlice([{ suspiciousTransactionId: "suspicious-1" }]));
    apiClient.getSuspiciousTransaction.mockResolvedValue({ suspiciousTransactionId: "suspicious-1" });
    apiClient.listGovernanceAdvisories.mockResolvedValue({ status: "AVAILABLE", count: 0, advisory_events: [] });
    apiClient.getGovernanceAdvisoryAnalytics.mockResolvedValue(analytics(1));
    apiClient.getCurrentShadowPerformanceSummary.mockResolvedValue(shadowSummary());
    apiClient.getGovernanceAdvisoryAudit.mockResolvedValue({ status: "AVAILABLE", audit_events: [] });
    apiClient.recordGovernanceAdvisoryAudit.mockResolvedValue(undefined);
  });

  it("loads only analyst workspace data on initial analyst runtime mount", async () => {
    const stableRuntime = runtimeValue();
    const stableProps = runtimeProps();
    const { rerender } = renderRuntime("analyst", {}, stableRuntime, stableProps);

    await waitFor(() => expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(apiClient.getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    expect(apiClient.listAlerts).not.toHaveBeenCalled();
    expect(apiClient.listScoredTransactions).not.toHaveBeenCalled();
    expect(apiClient.listSuspiciousTransactions).not.toHaveBeenCalled();

    rerender(runtimeElement("analyst", {}, stableRuntime, stableProps));

    expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1);
    expect(apiClient.getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1);
  });

  it("mounts only the active runtime while switching workspaces", async () => {
    const { rerender } = renderRuntime("analyst");
    await waitFor(() => expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1));

    rerender(runtimeElement("fraudTransaction"));
    await waitFor(() => expect(apiClient.listAlerts).toHaveBeenCalledTimes(1));
    expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1);
    expect(apiClient.listScoredTransactions).not.toHaveBeenCalled();

    rerender(runtimeElement("transactionScoring"));
    await waitFor(() => expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1));
    expect(apiClient.listAlerts).toHaveBeenCalledTimes(1);

    rerender(runtimeElement("suspiciousTransactions"));
    await waitFor(() => expect(apiClient.listSuspiciousTransactions).toHaveBeenCalledTimes(1));
    expect(apiClient.getSuspiciousTransaction).not.toHaveBeenCalled();

    rerender(runtimeElement("shadowPerformance"));
    await waitFor(() => expect(apiClient.getCurrentShadowPerformanceSummary).toHaveBeenCalledTimes(1));
  });

  it("keeps governance queue and reports analytics as separate active owners", async () => {
    const { rerender } = renderRuntime("compliance");
    await waitFor(() => expect(apiClient.listGovernanceAdvisories).toHaveBeenCalledTimes(1));
    expect(apiClient.getGovernanceAdvisoryAnalytics).not.toHaveBeenCalled();

    rerender(runtimeElement("reports"));
    await waitFor(() => expect(apiClient.getGovernanceAdvisoryAnalytics).toHaveBeenCalledTimes(1));
    expect(apiClient.listGovernanceAdvisories).toHaveBeenCalledTimes(1);
  });

  it("does not fetch hidden workspace data when authority is missing", async () => {
    renderRuntime("analyst", { canReadFraudCases: false });

    await waitFor(() => expect(apiClient.listFraudCaseWorkQueue).not.toHaveBeenCalled());
    expect(apiClient.getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
  });

  it("does not fetch shadow performance data when shadow authority is missing", async () => {
    const onResult = vi.fn();

    renderRuntime("shadowPerformance", { canReadShadowPerformance: false }, runtimeValue({ canReadShadowPerformance: false }), {
      ...runtimeProps(),
      onResult
    });

    await waitFor(() => expect(lastRuntimeResult(onResult)).toBeDefined());
    expect(apiClient.getCurrentShadowPerformanceSummary).not.toHaveBeenCalled();
  });

  it.each([
    ["analyst", "listFraudCaseWorkQueue"],
    ["fraudTransaction", "listAlerts"],
    ["transactionScoring", "listScoredTransactions"],
    ["suspiciousTransactions", "listSuspiciousTransactions"],
    ["compliance", "listGovernanceAdvisories"],
    ["reports", "getGovernanceAdvisoryAnalytics"],
    ["shadowPerformance", "getCurrentShadowPerformanceSummary"]
  ])("surfaces %s runtime API failures without replacing them with fake empty state", async (routeKey, methodName) => {
    apiClient[methodName].mockRejectedValueOnce(new Error(`${routeKey} unavailable`));
    const onResult = vi.fn();

    renderRuntime(routeKey, {}, runtimeValue(), { ...runtimeProps(), onResult });

    await waitFor(() => expect(lastRuntimeResult(onResult)?.error?.message).toBe(`${routeKey} unavailable`));
  });

  it("does not hidden-fetch reports analytics from compliance runtime", async () => {
    renderRuntime("compliance");

    await waitFor(() => expect(apiClient.listGovernanceAdvisories).toHaveBeenCalledTimes(1));
    expect(apiClient.getGovernanceAdvisoryAnalytics).not.toHaveBeenCalled();
  });
});

function renderRuntime(routeKey, runtimeOverrides = {}, runtimeContext = runtimeValue(runtimeOverrides), props = runtimeProps()) {
  return render(runtimeElement(routeKey, runtimeOverrides, runtimeContext, props));
}

function runtimeElement(routeKey, runtimeOverrides = {}, runtimeContext = runtimeValue(runtimeOverrides), props = runtimeProps()) {
  const route = WORKSPACE_ROUTE_REGISTRY[routeKey];
  const Runtime = route.Runtime;
  return (
    <WorkspaceRuntimeContext.Provider value={runtimeContext}>
      <Runtime
        route={route}
        sharedWorkspaceReadsEnabled
        setCounterValue={props.setCounterValue}
        setSessionState={props.setSessionState}
        onOpenAlert={props.onOpenAlert}
        onOpenFraudCase={props.onOpenFraudCase}
      >
        {(result) => {
          props.onResult?.(result);
          return <div>{result.workspaceContent}</div>;
        }}
      </Runtime>
    </WorkspaceRuntimeContext.Provider>
  );
}

function runtimeProps() {
  return {
    setCounterValue: vi.fn(),
    setSessionState: vi.fn(),
    onOpenAlert: vi.fn(),
    onOpenFraudCase: vi.fn(),
    onResult: null
  };
}

function lastRuntimeResult(onResult) {
  return onResult.mock.calls.at(-1)?.[0];
}

function runtimeValue(overrides = {}) {
  return {
    session: { userId: "analyst-1", authorities: [] },
    authProvider: { kind: "demo" },
    apiClient,
    canReadFraudCases: true,
    canReadAlerts: true,
    canReadTransactions: true,
    canReadSuspiciousTransactions: true,
    canReadGovernanceAdvisories: true,
    canReadShadowPerformance: true,
    canWriteGovernanceAudit: false,
    workspaceSessionResetKey: "demo:analyst-1",
    runtimeStatus: "ready",
    ...overrides
  };
}

function suspiciousSlice(content) {
  return {
    content,
    size: content.length,
    hasNext: false,
    nextCursor: null
  };
}

function page(content, overrides = {}) {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    page: 0,
    size: 25,
    ...overrides
  };
}

function workQueueSlice(cases) {
  return {
    cases,
    nextCursor: null,
    hasNext: false,
    size: cases.length,
    sort: "createdAt,desc"
  };
}

function analytics(advisories) {
  return {
    status: "AVAILABLE",
    window: { days: 7 },
    totals: { advisories, reviewed: advisories, open: 0 },
    decision_distribution: {},
    lifecycle_distribution: {},
    review_timeliness: { status: "LOW_CONFIDENCE" }
  };
}

function shadowSummary() {
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
    banner: "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic."
  };
}
