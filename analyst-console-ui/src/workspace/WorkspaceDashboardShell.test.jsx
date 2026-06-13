import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { WorkspaceDashboardShell } from "./WorkspaceDashboardShell.jsx";
import { WorkspaceRuntimeContext } from "./useWorkspaceRuntime.js";

const shellSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "WorkspaceDashboardShell.jsx"), "utf8");

describe("WorkspaceDashboardShell FDP-53 composition", () => {
  it("renders the active workspace through WorkspaceRouteRegistry", () => {
    expect(shellSource).toContain("resolveWorkspaceRouteResult(workspacePage)");
    expect(shellSource).toContain("const ActiveWorkspaceRuntime = activeRoute.Runtime");
    expect(shellSource).toContain("<ActiveWorkspaceRuntime");
    expect(shellSource).toContain("workspaceRoutes={visibleWorkspaceRoutes(WORKSPACE_ROUTE_ENTRIES");
  });

  it("delegates refresh behavior to the single refresh contract", () => {
    expect(shellSource).toContain("createWorkspaceRefreshHandler");
    expect(shellSource).not.toContain("function refreshDashboard");
    expect(shellSource).not.toContain("refreshWorkspaceDashboard");
  });

  it("keeps workspace-specific runtime hooks out of the shell", () => {
    expect(shellSource).not.toMatch(/useAnalystWorkspaceRuntime|useTransactionWorkspaceRuntime|useGovernanceWorkspaceRuntime/);
    expect(shellSource).not.toMatch(/useFraudCaseWorkQueue|useFraudCaseWorkQueueSummary|useAlertQueue|useScoredTransactionStream/);
    expect(shellSource).not.toMatch(/useGovernanceQueue|useGovernanceAnalytics|useGovernanceAuditWorkflow/);
    expect(shellSource).not.toContain("AnalystWorkspaceContainer");
    expect(shellSource).not.toContain("FraudTransactionWorkspaceContainer");
    expect(shellSource).not.toContain("TransactionScoringWorkspaceContainer");
    expect(shellSource).not.toContain("GovernanceWorkspaceContainer");
    expect(shellSource).not.toContain("ReportsWorkspaceContainer");
  });

  it("keeps shared counters and detail routing single-owned by the shell", () => {
    expect(shellSource).toContain("useWorkspaceCounters");
    expect(shellSource).toContain("<WorkspaceDetailRouter");
    expect(shellSource.match(/useWorkspaceCounters\(/g)).toHaveLength(1);
    expect(shellSource.match(/<WorkspaceDetailRouter/g)).toHaveLength(1);
  });

  it("shadowPerformanceWorkspaceCallsOnlyCurrentSummaryEndpoint", async () => {
    const apiClient = renderShadowPerformanceShell();

    await waitFor(() => expect(apiClient.getCurrentShadowPerformanceSummary).toHaveBeenCalledTimes(1));
    expect(apiClient.getCurrentShadowPerformanceSummary.mock.calls[0][0]).toEqual(expect.objectContaining({
      signal: expect.any(AbortSignal)
    }));
  });

  it("shadowPerformanceWorkspaceLoadsSharedWorkspaceCounters", async () => {
    const apiClient = renderShadowPerformanceShell();

    await waitFor(() => expect(apiClient.getCurrentShadowPerformanceSummary).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(apiClient.listAlerts).toHaveBeenCalledTimes(1));
    expect(apiClient.getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1);
    expect(apiClient.getSuspiciousTransactionSummary).toHaveBeenCalledTimes(1);
    expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1);
  });

  it("shadowPerformanceWorkspaceRendersSharedWorkspaceCounters", async () => {
    const apiClient = renderShadowPerformanceShell();

    await waitFor(() => expect(apiClient.getCurrentShadowPerformanceSummary).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("link", { name: /Alerts\s*41/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Global fraud cases\s*42/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Workspace signal total 43/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Transactions\s*44/ })).toBeInTheDocument();
    expect(screen.queryByText("Some workspace counters are temporarily unavailable.")).not.toBeInTheDocument();
  });
});

function renderShadowPerformanceShell() {
  const apiClient = apiClientMock();
  render(
    <WorkspaceRuntimeContext.Provider value={runtimeValue(apiClient)}>
      <WorkspaceDashboardShell
        workspacePage="shadowPerformance"
        selectedAlertId={null}
        selectedFraudCaseId={null}
        selectedSuspiciousTransactionId={null}
        selectedLinkedAlertContext={null}
        clearSelection={vi.fn()}
        navigateWorkspace={vi.fn()}
        openAlert={vi.fn()}
        openSuspiciousLinkedAlertContext={vi.fn()}
        openFraudCase={vi.fn()}
        openSuspiciousTransaction={vi.fn()}
        invalidWorkspaceRoute={null}
        sessionState={{ status: "AUTHENTICATED" }}
        setSessionState={vi.fn()}
      />
    </WorkspaceRuntimeContext.Provider>
  );
  return apiClient;
}

function apiClientMock() {
  return {
    getCurrentShadowPerformanceSummary: vi.fn().mockResolvedValue(shadowSummary()),
    listAlerts: vi.fn().mockResolvedValue({ totalElements: 41 }),
    getFraudCaseWorkQueueSummary: vi.fn().mockResolvedValue({ totalFraudCases: 42 }),
    getSuspiciousTransactionSummary: vi.fn().mockResolvedValue({ totalSuspiciousTransactions: 43 }),
    listScoredTransactions: vi.fn().mockResolvedValue({ totalElements: 44 })
  };
}

function runtimeValue(apiClient) {
  return {
    session: { userId: "analyst-1", authorities: ["shadow-performance:read"] },
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
    runtimeStatus: "ready"
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
