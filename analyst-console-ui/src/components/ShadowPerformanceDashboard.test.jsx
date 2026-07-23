import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ShadowPerformanceDashboard } from "./ShadowPerformanceDashboard.jsx";

const REQUIRED_BANNER = "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.";
const MALFORMED_MESSAGE = "Shadow Performance Summary response was malformed. Do not use this view for model assessment.";

describe("ShadowPerformanceDashboard", () => {
  it("rendersShadowPerformanceDashboard", () => {
    renderDashboard();

    expect(screen.getByRole("heading", { name: "Shadow Performance Summary" })).toBeInTheDocument();
  });

  it("rendersDiagnosticOnlyBanner", () => {
    renderDashboard();

    expect(screen.getByText("Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.")).toBeInTheDocument();
  });

  it("rendersEvaluationSubject", () => {
    renderDashboard();

    expect(screen.getByText("PLATFORM_RECOMMENDATION")).toBeInTheDocument();
    expect(screen.getByText("ENGINE_INTELLIGENCE_PROJECTION")).toBeInTheDocument();
    expect(screen.getByText("ENGINE_INTELLIGENCE_PROJECTION_V1")).toBeInTheDocument();
    expect(screen.getByText("NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE")).toBeInTheDocument();
  });

  it("rendersGovernanceStatus", () => {
    renderDashboard();

    expect(screen.getByText("Governance status")).toBeInTheDocument();
    expect(screen.getByText("DIAGNOSTIC_ONLY")).toBeInTheDocument();
  });

  it("doesNotRenderLegacyApprovedForModes", () => {
    renderDashboard();

    expect(screen.queryByText("Allowed diagnostic modes")).not.toBeInTheDocument();
    expect(screen.queryByText("COMPARE, SHADOW")).not.toBeInTheDocument();
  });

  it("rendersEvaluationContext", () => {
    renderDashboard();

    expect(screen.getByText("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1")).toBeInTheDocument();
    expect(screen.getByText("FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1")).toBeInTheDocument();
    expect(screen.getByText("FDP-124")).toBeInTheDocument();
  });

  it("rendersEvaluationPopulation", () => {
    renderDashboard();

    expect(screen.getByText("Records evaluated")).toBeInTheDocument();
    expect(screen.getByText("Positive class count")).toBeInTheDocument();
    expect(screen.getByText("Negative class count")).toBeInTheDocument();
  });

  it("rendersPopulationContextNearMetrics", () => {
    const { container } = renderDashboard();
    const text = container.textContent;

    expect(text.indexOf("Evaluation population")).toBeGreaterThan(-1);
    expect(text.indexOf("Metrics")).toBeGreaterThan(text.indexOf("Evaluation population"));
    expect(text.indexOf("Metrics") - text.indexOf("Evaluation population")).toBeLessThan(400);
  });

  it("rendersAlertRecommendedPrecisionWithPopulationContext", () => {
    renderDashboard();

    expect(screen.getByText("Alert-recommended precision")).toBeInTheDocument();
    expect(screen.getByText("66.7%")).toBeInTheDocument();
    expect(screen.getByText("Metrics are shown with evaluation population context to avoid overclaiming performance on small samples.")).toBeInTheDocument();
  });

  it("rendersAlertRecommendedRecallWithPopulationContext", () => {
    renderDashboard();

    expect(screen.getByText("Alert-recommended recall")).toBeInTheDocument();
    expect(screen.getByText("50.0%")).toBeInTheDocument();
  });

  it("rendersFalsePositiveRateWithPopulationContext", () => {
    renderDashboard();

    expect(screen.getByText("Offline false-positive rate")).toBeInTheDocument();
    expect(screen.getByText("25.0%")).toBeInTheDocument();
  });

  it("rendersFalseNegativeRateWithPopulationContext", () => {
    renderDashboard();

    expect(screen.getByText("Offline false-negative rate")).toBeInTheDocument();
    expect(screen.getByText("20.0%")).toBeInTheDocument();
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
    expect(screen.queryByText("Alert-recommended precision")).not.toBeInTheDocument();
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
    expect(screen.getByText("The dashboard reached the authorized v2 read API, but no current validated Shadow Performance Summary is available.")).toBeInTheDocument();
    expect(screen.getByText("This is not a model quality result and it is not a failure of the dashboard. The UI does not display fake, zero, sample, fallback, or stale metrics when the API returns 404.")).toBeInTheDocument();
    expect(screen.getByText("Shadow performance metrics will appear here only after a valid Shadow Performance Summary v2 is available through the v2 endpoint.")).toBeInTheDocument();
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

    expect(screen.getByText("GET /api/v2/governance/shadow-performance/summary/current")).toBeInTheDocument();
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

    expect(screen.getByText("Authorized Shadow Performance Summary v2 read API")).toBeInTheDocument();
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

    expect(screen.getByText(MALFORMED_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByText("Alert-recommended precision")).not.toBeInTheDocument();
  });

  it("rejectsWrongSummaryType", () => {
    expectMalformedSummary((summary) => {
      summary.summaryType = "PLATFORM_RECOMMENDATION_EVALUATION_CARD";
    });
  });

  it("rejectsWrongSummaryVersion", () => {
    expectMalformedSummary((summary) => {
      summary.summaryVersion = "2.0";
    });
  });

  it("rejectsWrongBanner", () => {
    expectMalformedSummary((summary) => {
      summary.banner = "Model approved for production";
    });
  });

  it("rejectsMissingBannerOnSuccessPayload", () => {
    expectMalformedSummary((summary) => {
      delete summary.banner;
    });
  });

  it("rejectsProductionGovernanceStatus", () => {
    expectMalformedSummary((summary) => {
      summary.governance.governanceStatus = "PRODUCTION_APPROVED";
    });
  });

  it("rejectsApprovedForProductionDecisioning", () => {
    expectMalformedSummary((summary) => {
      summary.governance.approvedFor = ["COMPARE", "SHADOW", "PRODUCTION_DECISIONING"];
    });
  });

  it("rejectsApprovedForValuesOutsideCompareShadow", () => {
    expectMalformedSummary((summary) => {
      summary.governance.approvedFor = ["COMPARE", "RETRAINING"];
    });
  });

  it("rejectsMissingCompareApprovedFor", () => {
    expectMalformedSummary((summary) => {
      summary.governance.approvedFor = ["SHADOW"];
    });
  });

  it("rejectsMissingShadowApprovedFor", () => {
    expectMalformedSummary((summary) => {
      summary.governance.approvedFor = ["COMPARE"];
    });
  });

  it.each([
    ["rejectsDiagnosticOnlyFalse", "diagnosticOnly"],
    ["rejectsNotProductionApprovalFalse", "notProductionApproval"],
    ["rejectsNotPromotionApprovalFalse", "notPromotionApproval"],
    ["rejectsNotThresholdRecommendationFalse", "notThresholdRecommendation"],
    ["rejectsNotPaymentAuthorizationFalse", "notPaymentAuthorization"],
    ["rejectsNotAutomaticDecisioningFalse", "notAutomaticDecisioning"]
  ])("%s", (_name, field) => {
    expectMalformedSummary((summary) => {
      summary.governance[field] = false;
    });
  });

  it.each([
    ["rejectsWrongEvaluationCardType", "evaluationCardType", "MODEL_CARD_V1"],
    ["rejectsWrongEvaluationReportType", "evaluationReportType", "PLATFORM_RECOMMENDATION_EVALUATION_CARD"],
    ["rejectsWrongEvaluationReportVersion", "evaluationReportVersion", "FDP-103"],
    ["rejectsWrongDatasetTimeBasis", "datasetTimeBasis", "TRANSACTION_CREATED_AT"],
    ["rejectsWrongEvaluationPurpose", "evaluationPurpose", "PRODUCTION_APPROVAL"]
  ])("%s", (_name, field, value) => {
    expectMalformedSummary((summary) => {
      summary.evaluation[field] = value;
    });
  });

  it("rejectsWrongMetricBasis", () => {
    expectMalformedSummary((summary) => {
      summary.metricBasis = "production_threshold";
    });
  });

  it("rejectsNumericStringRates", () => {
    expectMalformedSummary((summary) => {
      summary.metrics.alertRecommendedPrecision.value = "0.9";
    });
  });

  it("rejectsUnavailableMetricWithNumericValue", () => {
    expectMalformedSummary((summary) => {
      summary.metrics.alertRecommendedPrecision = { available: false, value: 0, reason: "NO_POSITIVE_CLASS" };
    });
  });

  it("rejectsNaNMetricValues", () => {
    expectMalformedSummary((summary) => {
      summary.metrics.alertRecommendedRecall.value = Number.NaN;
    });
  });

  it("rejectsInfiniteMetricValues", () => {
    expectMalformedSummary((summary) => {
      summary.metrics.falsePositiveRate.value = Number.POSITIVE_INFINITY;
    });
  });

  it.each([
    ["rejectsPrecisionGreaterThanOne", "alertRecommendedPrecision", 1.1],
    ["rejectsPrecisionBelowZero", "alertRecommendedPrecision", -0.1],
    ["rejectsRecallGreaterThanOne", "alertRecommendedRecall", 1.1],
    ["rejectsRecallBelowZero", "alertRecommendedRecall", -0.1],
    ["rejectsFalsePositiveRateGreaterThanOne", "falsePositiveRate", 1.1],
    ["rejectsFalsePositiveRateBelowZero", "falsePositiveRate", -0.1]
  ])("%s", (_name, field, value) => {
    expectMalformedSummary((summary) => {
      summary.metrics[field].value = value;
    });
  });

  it("rejectsNegativePopulationCounts", () => {
    expectMalformedSummary((summary) => {
      summary.evaluationPopulation.recordsEvaluated = -1;
    });
  });

  it("rejectsPopulationCountsAboveMax", () => {
    expectMalformedSummary((summary) => {
      summary.evaluationPopulation.recordsEvaluated = 1001;
    });
  });

  it("rejectsPositivePlusNegativeNotEqualRecordsEvaluated", () => {
    expectMalformedSummary((summary) => {
      summary.evaluationPopulation.positiveClassCount = 4;
    });
  });

  it.each([
    ["doesNotRenderUnsafeProductionBanner", "Model approved for production", /Model approved for production/i],
    ["doesNotRenderUnsafePromotionBanner", "Promotion ready", /Promotion ready/i],
    ["doesNotRenderUnsafeThresholdBanner", "Recommended threshold: 0.8", /Recommended threshold/i],
    ["doesNotRenderUnsafePaymentAuthorizationBanner", "Payment authorization approved", /Payment authorization approved/i]
  ])("%s", (_name, banner, unsafeText) => {
    expectMalformedSummary((summary) => {
      summary.banner = banner;
    });

    expect(screen.queryByText(unsafeText)).not.toBeInTheDocument();
  });

  it.each([
    ["doesNotRenderMetricsOn401", { status: 401 }],
    ["doesNotRenderMetricsOn403", { status: 403 }],
    ["doesNotRenderFakeZeroMetricsOn404", { status: 404 }],
    ["doesNotRenderStaleFallbackMetricsOn503", { status: 503 }],
    ["doesNotFabricateMetricsWhenApiFails", new Error("network failed")]
  ])("%s", (_name, error) => {
    renderDashboard({ error, summary: null });

    expect(screen.queryByText("Alert-recommended precision")).not.toBeInTheDocument();
    expect(screen.queryByText("0.0%")).not.toBeInTheDocument();
  });

  it("doesNotTreat404AsHealthyModel", () => {
    renderDashboard404();

    expect(screen.queryByText("Evaluation subject")).not.toBeInTheDocument();
    expect(screen.getByText(/This is not a model quality result/)).toBeInTheDocument();
  });

  it("doesNotRenderMetricCardsOn404", () => {
    const { container } = renderDashboard404();

    expect(container.querySelector(".metricCard")).not.toBeInTheDocument();
  });

  it.each([
    ["doesNotRenderPrecisionOn404", "Alert-recommended precision"],
    ["doesNotRenderRecallOn404", "Alert-recommended recall"],
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

  it("doesNotRenderRawEvaluationCard", () => {
    expect(renderDashboardWithRawFields()).not.toContain("raw-platform-recommendation-evaluation-card-secret");
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

function expectMalformedSummary(mutateSummary) {
  const summary = cloneSummary();
  mutateSummary(summary);
  const { container } = renderDashboard({ summary });

  expect(screen.getByText(MALFORMED_MESSAGE)).toBeInTheDocument();
  expect(screen.queryByText("Alert-recommended precision")).not.toBeInTheDocument();
  expect(container.querySelector(".metricCard")).not.toBeInTheDocument();
  expect(screen.queryByText("Evaluation subject")).not.toBeInTheDocument();
  expect(screen.queryByText("Governance context")).not.toBeInTheDocument();
  expect(screen.queryByText("Evaluation context")).not.toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Warnings" })).not.toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Limitations" })).not.toBeInTheDocument();
  return container;
}

function cloneSummary() {
  return JSON.parse(JSON.stringify(shadowSummary()));
}

function rawFields() {
  return {
    rawEvaluationCard: "raw-platform-recommendation-evaluation-card-secret",
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
    alertRecommendedPrecision: metric(1),
    alertRecommendedRecall: metric(1),
    falsePositiveRate: metric(0),
    falseNegativeRate: metric(0)
  };
}

function shadowSummary(overrides = {}) {
  return {
    reportType: "SHADOW_PERFORMANCE_SUMMARY_V2",
    summaryVersion: "shadow-performance-summary-v2",
    generatedAt: "2026-06-13T02:00:00Z",
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
      evaluationReportGeneratedAt: "2026-06-10T00:00:00Z",
      evaluationCardGeneratedAt: "2026-06-12T00:00:00Z",
      evaluationArtifactSetVersion: "fdp123-report-artifact-set-v1",
      datasetVersion: "feedback-dataset-v1",
      datasetTimeBasis: "FEEDBACK_CREATED_AT",
      sourceManifestSha256: "a".repeat(64),
      sourceEvaluationCardManifestSha256: "b".repeat(64)
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
    limitations: ["OFFLINE_ONLY"],
    banner: REQUIRED_BANNER,
    ...overrides
  };
}

function metric(value) {
  return { available: true, value, reason: null };
}
