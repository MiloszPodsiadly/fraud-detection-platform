import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ShadowPerformanceDashboard } from "./ShadowPerformanceDashboard.jsx";

describe("ShadowPerformanceDashboard", () => {
  it("rendersShadowPerformanceDashboard", () => {
    renderDashboard();

    expect(screen.getByRole("heading", { name: "Shadow Performance Summary" })).toBeInTheDocument();
  });

  it("rendersDiagnosticOnlyBanner", () => {
    renderDashboard();

    expect(screen.getByText("Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.")).toBeInTheDocument();
  });

  it("rendersModelIdentity", () => {
    renderDashboard();

    expect(screen.getByText("python-logistic-fraud-model")).toBeInTheDocument();
    expect(screen.getByText("2026-04-21.trained.v1")).toBeInTheDocument();
    expect(screen.getByText("LOGISTIC_REGRESSION")).toBeInTheDocument();
    expect(screen.getByText("2026-04-22.v1")).toBeInTheDocument();
  });

  it("rendersGovernanceStatus", () => {
    renderDashboard();

    expect(screen.getByText("Governance status")).toBeInTheDocument();
    expect(screen.getByText("DIAGNOSTIC_ONLY")).toBeInTheDocument();
  });

  it("rendersApprovedForShadowCompareOnly", () => {
    renderDashboard();

    expect(screen.getByText("Approved diagnostic modes")).toBeInTheDocument();
    expect(screen.getByText("COMPARE, SHADOW")).toBeInTheDocument();
  });

  it("rendersEvaluationContext", () => {
    renderDashboard();

    expect(screen.getByText("PYTHON_ML_EVALUATION_FOUNDATION")).toBeInTheDocument();
    expect(screen.getByText("FDP-103")).toBeInTheDocument();
    expect(screen.getByText("bucket_ordered_offline_diagnostic")).toBeInTheDocument();
  });

  it("rendersEvaluationPopulation", () => {
    renderDashboard();

    expect(screen.getByText("Dataset records read")).toBeInTheDocument();
    expect(screen.getByText("Records accepted for evaluation")).toBeInTheDocument();
    expect(screen.getByText("Records excluded not evaluation eligible")).toBeInTheDocument();
  });

  it("rendersPopulationContextNearMetrics", () => {
    const { container } = renderDashboard();
    const text = container.textContent;

    expect(text.indexOf("Evaluation population")).toBeGreaterThan(-1);
    expect(text.indexOf("Metrics")).toBeGreaterThan(text.indexOf("Evaluation population"));
    expect(text.indexOf("Metrics") - text.indexOf("Evaluation population")).toBeLessThan(400);
  });

  it("rendersPrecisionAtBudgetWithPopulationContext", () => {
    renderDashboard();

    expect(screen.getByText("Offline precision at budget")).toBeInTheDocument();
    expect(screen.getByText("66.7%")).toBeInTheDocument();
    expect(screen.getByText("Metrics are shown with evaluation population context to avoid overclaiming performance on small samples.")).toBeInTheDocument();
  });

  it("rendersRecallAtTopKWithPopulationContext", () => {
    renderDashboard();

    expect(screen.getByText("Offline recall at top K")).toBeInTheDocument();
    expect(screen.getByText("50.0%")).toBeInTheDocument();
  });

  it("rendersFalsePositiveRateWithPopulationContext", () => {
    renderDashboard();

    expect(screen.getByText("Offline false-positive rate")).toBeInTheDocument();
    expect(screen.getByText("25.0%")).toBeInTheDocument();
  });

  it("rendersMlCaughtRulesMissedCount", () => {
    renderDashboard();

    expect(screen.getByText("ML caught / rules missed")).toBeInTheDocument();
  });

  it("rendersRulesCaughtMlMissedCount", () => {
    renderDashboard();

    expect(screen.getByText("Rules caught / ML missed")).toBeInTheDocument();
  });

  it("rendersMissingSignalCounts", () => {
    renderDashboard();

    expect(screen.getByText("Missing ML count")).toBeInTheDocument();
    expect(screen.getByText("Missing rules count")).toBeInTheDocument();
    expect(screen.getByText("Missing projection count")).toBeInTheDocument();
  });

  it("rendersDisagreementSummary", () => {
    renderDashboard();

    expect(screen.getByRole("heading", { name: "Rule vs ML diagnostic disagreement" })).toBeInTheDocument();
    expect(screen.getByText("Rules high / ML high")).toBeInTheDocument();
    expect(screen.getByText("ML missing / rules present")).toBeInTheDocument();
  });

  it("rendersWarnings", () => {
    renderDashboard();

    expect(screen.getByRole("heading", { name: "Warnings" })).toBeInTheDocument();
    expect(screen.getByText("MISSING_ML_SIGNAL_PRESENT")).toBeInTheDocument();
  });

  it("rendersLimitations", () => {
    renderDashboard();

    expect(screen.getByRole("heading", { name: "Limitations" })).toBeInTheDocument();
    expect(screen.getByText("OFFLINE_ONLY")).toBeInTheDocument();
  });

  it("rendersLoadingState", () => {
    render(<ShadowPerformanceDashboard isLoading canReadShadowPerformance />);

    expect(screen.getByText("Loading Shadow Performance Summary...")).toBeInTheDocument();
    expect(screen.queryByText("Offline precision at budget")).not.toBeInTheDocument();
  });

  it("rendersUnauthenticatedStateFor401", () => {
    renderDashboard({ error: { status: 401 }, summary: null });

    expect(screen.getByText("You must be signed in to view Shadow Performance Summary.")).toBeInTheDocument();
  });

  it("rendersForbiddenStateFor403", () => {
    renderDashboard({ error: { status: 403 }, summary: null });

    expect(screen.getByText("You do not have permission to view Shadow Performance Summary. Required permission: shadow-performance:read.")).toBeInTheDocument();
  });

  it("rendersNoCurrentSummaryStateFor404", () => {
    renderDashboard404();

    expect(screen.getByRole("heading", { name: "No current Shadow Performance Summary" })).toBeInTheDocument();
    expect(screen.getByText("The dashboard reached the authorized FDP-106 read API, but no current validated Shadow Performance Summary is available.")).toBeInTheDocument();
    expect(screen.getByText("This is not a model quality result and it is not a failure of the dashboard. The UI does not display fake, zero, sample, fallback, or stale metrics when the API returns 404.")).toBeInTheDocument();
    expect(screen.getByText("Shadow performance metrics will appear here only after a valid FDP-105 Shadow Performance Summary is available through the FDP-106 endpoint.")).toBeInTheDocument();
  });

  it("rendersRichEmptyStateOn404", () => {
    renderDashboard404();

    expect(screen.getByRole("heading", { name: "No current Shadow Performance Summary" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Technical context" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "What this means" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "What needs to happen next" })).toBeInTheDocument();
  });

  it("showsEndpointNameOn404", () => {
    renderDashboard404();

    expect(screen.getByText("GET /api/v1/governance/shadow-performance/summary/current")).toBeInTheDocument();
  });

  it("showsStatusCodeOn404", () => {
    renderDashboard404();

    expect(screen.getByText("404 Not Found")).toBeInTheDocument();
  });

  it("explainsNoCurrentValidatedSummaryOn404", () => {
    renderDashboard404();

    expect(screen.getByText("No current validated Shadow Performance Summary is configured yet.")).toBeInTheDocument();
  });

  it("explains404IsNotModelQualityResult", () => {
    renderDashboard404();

    expect(screen.getByText(/This is not a model quality result/)).toBeInTheDocument();
  });

  it("showsFdp106DataSourceOn404", () => {
    renderDashboard404();

    expect(screen.getByText("FDP-106 Authorized Read API")).toBeInTheDocument();
  });

  it("showsFallbackMetricsDisabledOn404", () => {
    renderDashboard404();

    expect(screen.getByText("Fallback metrics")).toBeInTheDocument();
    expect(screen.getAllByText("Disabled").length).toBeGreaterThanOrEqual(2);
  });

  it("showsSampleMetricsDisabledOn404", () => {
    renderDashboard404();

    expect(screen.getByText("Demo/sample metrics")).toBeInTheDocument();
    expect(screen.getAllByText("Disabled").length).toBeGreaterThanOrEqual(2);
  });

  it("showsReadOnlyDiagnosticModeOn404", () => {
    renderDashboard404();

    expect(screen.getByText("Read-only diagnostic view")).toBeInTheDocument();
  });

  it("keepsTryAgainActionOn404", () => {
    renderDashboard404();

    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("tryAgainCallsCurrentSummaryEndpointAgain", () => {
    const onRetry = vi.fn();
    renderDashboard404({ onRetry });

    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("rendersDiagnosticOnlyBannerOn404", () => {
    renderDashboard404();

    expect(screen.getByText("Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.")).toBeInTheDocument();
  });

  it("doesNotHideDiagnosticOnlyBannerOn404", () => {
    renderDashboard404();

    expect(screen.getByRole("status")).toHaveTextContent("Shadow performance metrics are offline diagnostics only.");
  });

  it.each([
    ["explainsNoProductionApprovalOn404", /not production approval/i],
    ["explainsNoPromotionReadinessOn404", /not promotion readiness/i],
    ["explainsNoThresholdRecommendationOn404", /not threshold recommendation/i],
    ["explainsNoPaymentAuthorizationOn404", /not payment authorization/i],
    ["explainsNoAutomaticDecisioningOn404", /not automatic decisioning/i],
    ["explainsNoAnalystRecommendationOn404", /not analyst recommendation logic/i]
  ])("%s", (_name, copy) => {
    renderDashboard404();

    expect(screen.getAllByText(copy).length).toBeGreaterThan(0);
  });

  it("rendersUnavailableStateFor503", () => {
    renderDashboard({ error: { status: 503 }, summary: null });

    expect(screen.getByText("Shadow Performance Summary is currently unavailable or failed validation. Do not use this view for model assessment.")).toBeInTheDocument();
  });

  it("rendersNetworkErrorState", () => {
    renderDashboard({ error: new Error("endpoint token secret stacktrace"), summary: null });

    expect(screen.getByText("Shadow Performance Summary could not be loaded. Retry the diagnostic read.")).toBeInTheDocument();
    expect(screen.queryByText(/endpoint token secret stacktrace/i)).not.toBeInTheDocument();
  });

  it("rendersMalformedResponseError", () => {
    renderDashboard({ summary: { ...shadowSummary(), metrics: null } });

    expect(screen.getByText("Shadow Performance Summary response was malformed. Do not use this view for model assessment.")).toBeInTheDocument();
    expect(screen.queryByText("Offline precision at budget")).not.toBeInTheDocument();
  });

  it.each([
    ["doesNotRenderMetricsOn401", { status: 401 }],
    ["doesNotRenderMetricsOn403", { status: 403 }],
    ["doesNotRenderFakeZeroMetricsOn404", { status: 404 }],
    ["doesNotRenderStaleFallbackMetricsOn503", { status: 503 }],
    ["doesNotFabricateMetricsWhenApiFails", new Error("network failed")]
  ])("%s", (_name, error) => {
    renderDashboard({ error, summary: null });

    expect(screen.queryByText("Offline precision at budget")).not.toBeInTheDocument();
    expect(screen.queryByText("0.0%")).not.toBeInTheDocument();
  });

  it("doesNotTreat404AsHealthyModel", () => {
    renderDashboard404();

    expect(screen.queryByText("Model identity")).not.toBeInTheDocument();
    expect(screen.getByText(/This is not a model quality result/)).toBeInTheDocument();
  });

  it("doesNotRenderMetricCardsOn404", () => {
    const { container } = renderDashboard404();

    expect(container.querySelector(".metricCard")).not.toBeInTheDocument();
  });

  it.each([
    ["doesNotRenderPrecisionOn404", "Offline precision at budget"],
    ["doesNotRenderRecallOn404", "Offline recall at top K"],
    ["doesNotRenderFalsePositiveRateOn404", "Offline false-positive rate"],
    ["doesNotRenderSampleSummaryOn404", "sample summary"],
    ["doesNotRenderStaleMetricsOn404", "last known summary"],
    ["doesNotRenderCachedMetricsOn404", "cached metrics"],
    ["doesNotRenderFallbackMetricsOn404", "Fallback metric value"],
    ["doesNotRenderEmptyChartsAsValidDataOn404", "chart"]
  ])("%s", (_name, forbiddenText) => {
    renderDashboard404();

    expect(screen.queryByText(forbiddenText)).not.toBeInTheDocument();
  });

  it.each([
    ["doesNotRenderProductionReadyOn404", "Production ready"],
    ["doesNotRenderModelApprovedOn404", "Approved model"],
    ["doesNotRenderPromotionReadyOn404", "Promotion ready"],
    ["doesNotRenderRecommendedThresholdOn404", "Recommended threshold"],
    ["doesNotRenderChampionCandidateOn404", "Champion candidate"],
    ["doesNotRenderDeployRecommendationOn404", "Deploy recommendation"],
    ["doesNotRenderPaymentApprovedOn404", "Payment approved"],
    ["doesNotRenderAnalystRecommendationOn404", "Analyst recommendation"],
    ["doesNotRenderAutoApproveDeclineBlockOn404", /Auto approve|Auto decline|Auto block/]
  ])("%s", (_name, forbiddenText) => {
    renderDashboard404();

    expect(screen.queryByText(forbiddenText)).not.toBeInTheDocument();
  });

  it("doesNotTreat503AsZeroMetrics", () => {
    renderDashboard({ error: { status: 503 }, summary: null });

    expect(screen.queryByText("0.0%")).not.toBeInTheDocument();
    expect(screen.getByText("Shadow Performance Summary is currently unavailable or failed validation. Do not use this view for model assessment.")).toBeInTheDocument();
  });

  it.each([
    ["doesNotRenderProductionApproved", "Production approved"],
    ["doesNotRenderPromotionApproved", "Promotion approved"],
    ["doesNotRenderPromotionReady", "Promotion ready"],
    ["doesNotRenderThresholdRecommendation", "Threshold recommendation"],
    ["doesNotRenderRecommendedThreshold", "Recommended threshold"],
    ["doesNotRenderChampionCandidate", "Champion candidate"],
    ["doesNotRenderDeployRecommendation", "Deploy recommendation"],
    ["doesNotRenderFinalDecision", "Final decision"],
    ["doesNotRenderPaymentAuthorization", "Payment authorization"],
    ["doesNotRenderAnalystRecommendation", "Analyst recommendation"],
    ["excellentMetricsDoNotRenderPromotionReadiness", "Promotion readiness"],
    ["excellentMetricsDoNotRenderThresholdSuggestion", "Threshold suggestion"],
    ["excellentMetricsDoNotRenderProductionApproval", "Production approval"]
  ])("%s", (_name, forbiddenText) => {
    renderDashboard({ summary: shadowSummary({ metrics: excellentMetrics() }) });

    expect(screen.queryByText(forbiddenText)).not.toBeInTheDocument();
  });

  it.each([
    ["doesNotRenderApproveDeclineBlockActions", /approve|decline|block/i],
    ["doesNotRenderPromoteButton", /promote/i],
    ["doesNotRenderDeployButton", /deploy/i],
    ["doesNotRenderChangeThresholdButton", /change threshold/i]
  ])("%s", (_name, buttonName) => {
    renderDashboard();

    expect(screen.queryByRole("button", { name: buttonName })).not.toBeInTheDocument();
  });

  it("doesNotRenderRawModelCard", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-model-card-secret");
  });

  it("doesNotRenderRawEvaluationReport", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-evaluation-report-secret");
  });

  it("doesNotRenderRawDataset", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-dataset-secret");
  });

  it("doesNotRenderTransactionReference", () => {
    expect(renderDashboardWithRawFields()).not.toContain("transaction-reference-secret");
  });

  it("doesNotRenderEvaluationRecordId", () => {
    expect(renderDashboardWithRawFields()).not.toContain("evaluation-record-secret");
  });

  it("doesNotRenderCustomerAccountCardDeviceMerchantIds", () => {
    const text = renderDashboardWithRawFields();

    expect(text).not.toContain("customer-secret");
    expect(text).not.toContain("account-secret");
    expect(text).not.toContain("card-secret");
    expect(text).not.toContain("device-secret");
    expect(text).not.toContain("merchant-secret");
  });

  it("doesNotRenderAnalystIdentifiers", () => {
    expect(renderDashboardWithRawFields()).not.toContain("analyst-secret");
  });

  it("doesNotRenderRawPayload", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-payload-secret");
  });

  it("doesNotRenderRawFeatureVector", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-feature-vector-secret");
  });

  it("doesNotRenderRawMlRequest", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-ml-request-secret");
  });

  it("doesNotRenderRawMlResponse", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-ml-response-secret");
  });

  it("doesNotRenderEndpointTokenSecretStacktrace", () => {
    renderDashboard({ error: new Error("endpoint token-secret stacktrace raw payload"), summary: null });

    expect(screen.queryByText(/token-secret/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/stacktrace/i)).not.toBeInTheDocument();
  });

  it("dashboardDirectForbiddenStateDoesNotPretendNoData", () => {
    renderDashboard({ canReadShadowPerformance: false });

    expect(screen.getByText("You do not have permission to view Shadow Performance Summary. Required permission: shadow-performance:read.")).toBeInTheDocument();
    expect(screen.queryByText(/No current Shadow Performance Summary/)).not.toBeInTheDocument();
  });

  it("dashboardDirectForbiddenStateDoesNotOfferRetry", () => {
    renderDashboard({ canReadShadowPerformance: false });

    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });
});

function renderDashboard(overrides = {}) {
  return render(
    <ShadowPerformanceDashboard
      summary={shadowSummary()}
      isLoading={false}
      error={null}
      canReadShadowPerformance
      onRetry={vi.fn()}
      {...overrides}
    />
  );
}

function renderDashboard404(overrides = {}) {
  return renderDashboard({
    error: { status: 404 },
    summary: null,
    ...overrides
  });
}

function renderDashboardWithRawFields() {
  const { container } = renderDashboard({ summary: shadowSummary(rawFields()) });
  return container.textContent;
}

function rawFields() {
  return {
    rawModelCard: "raw-model-card-secret",
    rawEvaluationReport: "raw-evaluation-report-secret",
    rawDataset: "raw-dataset-secret",
    transactionReference: "transaction-reference-secret",
    evaluationRecordId: "evaluation-record-secret",
    customerId: "customer-secret",
    accountId: "account-secret",
    cardId: "card-secret",
    deviceId: "device-secret",
    merchantId: "merchant-secret",
    analystId: "analyst-secret",
    rawPayload: "raw-payload-secret",
    rawFeatureVector: "raw-feature-vector-secret",
    rawMlRequest: "raw-ml-request-secret",
    rawMlResponse: "raw-ml-response-secret"
  };
}

function excellentMetrics() {
  return {
    precisionAtBudget: 1,
    recallAtTopK: 1,
    falsePositiveRate: 0,
    mlCaughtRulesMissedCount: 0,
    rulesCaughtMlMissedCount: 0,
    missingMlCount: 0,
    missingRulesCount: 0,
    missingProjectionCount: 0,
    notEvaluationEligibleCount: 0
  };
}

function shadowSummary(overrides = {}) {
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
    limitations: ["OFFLINE_ONLY"],
    banner: "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.",
    ...overrides
  };
}
