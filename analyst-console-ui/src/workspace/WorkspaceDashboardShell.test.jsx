import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { WorkspaceDashboardShell } from "./WorkspaceDashboardShell.jsx";
import { WorkspaceRuntimeContext } from "./useWorkspaceRuntime.js";

const shellSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "WorkspaceDashboardShell.jsx"), "utf8");
const shadowDashboardSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "../components/ShadowPerformanceDashboard.jsx"), "utf8");
const shadowRuntimeSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "ShadowPerformanceWorkspaceRuntime.jsx"), "utf8");
const shadowContainerSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "ShadowPerformanceWorkspaceContainer.jsx"), "utf8");
const shadowPageSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "../pages/ShadowPerformanceDashboardPage.jsx"), "utf8");

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

  it("shadowPerformanceCountersRemainShellOwnedUiContext", () => {
    const shadowContentSources = [
      shadowDashboardSource,
      shadowRuntimeSource,
      shadowContainerSource,
      shadowPageSource
    ].join("\n");

    expect(shellSource).toContain("useWorkspaceCounters");
    expect(shellSource).toContain("workspaceCounters={workspaceCounterState.counters}");
    expect(shellSource).toContain("workspaceCountersStatus={workspaceCounterState}");
    expect(shadowContainerSource).toContain("summary={summaryState.summary}");
    expect(shadowContentSources).not.toContain("useWorkspaceCounters");
    expect(shadowContentSources).not.toContain("workspaceCounters");
    expect(shadowContentSources).not.toContain("workspaceCounterState");
    expect(shadowContentSources).not.toContain("totalFraudCases");
    expect(shadowContentSources).not.toContain("totalSuspiciousTransactions");
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
    reportType: "SHADOW_PERFORMANCE_SUMMARY_V2",
    summaryVersion: "shadow-performance-summary-v2",
    generatedAt: "2026-06-08T02:00:00Z",
    evaluationSubject: {
      subjectType: "PLATFORM_RECOMMENDATION",
      sourceComponent: "ENGINE_INTELLIGENCE_PROJECTION",
      sourceVersion: "ENGINE_INTELLIGENCE_PROJECTION_V1",
      featureContractVersion: "NOT_APPLICABLE",
      modelIdentity: "NOT_AVAILABLE",
      modelArtifactSha256: "NOT_AVAILABLE",
      identityCompleteness: "NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE"
    },
    metricBasis: "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK",
    governance: {
      governanceStatus: "DIAGNOSTIC_ONLY",
      diagnosticOnly: true,
      notProductionApproval: true,
      notPromotionApproval: true,
      notThresholdRecommendation: true,
      notPaymentAuthorization: true,
      notAutomaticDecisioning: true
    },
    evaluation: {
      evaluationCardType: "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1",
      evaluationCardVersion: "platform-recommendation-evaluation-card-v1",
      evaluationPurpose: "OFFLINE_DIAGNOSTIC",
      evaluationReportType: "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1",
      evaluationReportVersion: "FDP-124",
      evaluationArtifactSetVersion: "fdp123-report-artifact-set-v1",
      datasetVersion: "feedback-dataset-v1",
      datasetTimeBasis: "FEEDBACK_CREATED_AT",
      sourceManifestSha256: "a".repeat(64)
    },
    evaluationPopulation: {
      recordsEvaluated: 5,
      positiveClassCount: 3,
      negativeClassCount: 2
    },
    metrics: {
      alertRecommendedPrecision: metric(0.666667),
      alertRecommendedRecall: metric(0.5),
      falsePositiveRate: metric(0.25),
      falseNegativeRate: metric(0.2)
    },
    warnings: ["MISSING_ML_SIGNAL_PRESENT"],
    limitations: ["DIAGNOSTIC_ONLY"],
    banner: "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic."
  };
}

function metric(value) {
  return { available: true, value, reason: null };
}
