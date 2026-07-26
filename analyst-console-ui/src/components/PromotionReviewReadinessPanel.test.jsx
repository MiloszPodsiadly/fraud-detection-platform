import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PromotionReviewReadinessPanel } from "./PromotionReviewReadinessPanel.jsx";

describe("PromotionReviewReadinessPanel", () => {
  it("renders loading state", () => {
    renderPanel({ isLoading: true, report: null });

    expect(screen.getByText("Loading Promotion Review Readiness...")).toBeInTheDocument();
  });

  it("renders REVIEWABLE as human review may begin", () => {
    renderPanel();

    expect(screen.getAllByText("REVIEWABLE").length).toBeGreaterThan(0);
    expect(screen.getByText("Human review may begin.")).toBeInTheDocument();
  });

  it("renders not model promotion approval near REVIEWABLE", () => {
    renderPanel();

    const text = screen.getByLabelText("Promotion Review Readiness status").textContent;
    expect(text).toContain("REVIEWABLE");
    expect(text).toContain("This is not model promotion approval.");
  });

  it("does not render REVIEWABLE as approved promoted or deployable", () => {
    renderPanel();

    expect(screen.queryByText("Approved")).not.toBeInTheDocument();
    expect(screen.queryByText("Promoted")).not.toBeInTheDocument();
    expect(screen.queryByText("Ready for production")).not.toBeInTheDocument();
    expect(screen.queryByText("Deploy model")).not.toBeInTheDocument();
  });

  it("renders INSUFFICIENT_DATA", () => {
    renderPanel({ report: reportWithCheckStatus("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", "FAIL") });

    expect(screen.getAllByText("INSUFFICIENT_DATA").length).toBeGreaterThan(0);
    expect(screen.getByText("Not enough diagnostic evidence for human review.")).toBeInTheDocument();
  });

  it("renders NOT_REVIEWABLE", () => {
    renderPanel({ report: reportWithCheckStatus("EVALUATION_CARD_PRESENT", "FAIL") });

    expect(screen.getAllByText("NOT_REVIEWABLE").length).toBeGreaterThan(0);
    expect(screen.getByText("Diagnostic checks failed. Human review should not begin yet.")).toBeInTheDocument();
  });

  it("renders INCONCLUSIVE", () => {
    renderPanel({ report: reportWithCheckStatus("ALERT_RECOMMENDED_RECALL_AVAILABLE", "INCONCLUSIVE") });

    expect(screen.getAllByText("INCONCLUSIVE").length).toBeGreaterThan(0);
    expect(screen.getByText("Diagnostic evidence is present but one or more metrics are unavailable.")).toBeInTheDocument();
  });

  it("renders diagnostic banner", () => {
    renderPanel();

    expect(screen.getByRole("status")).toHaveTextContent("Promotion review readiness is an offline diagnostic aid only.");
  });

  it("renders all non-decisioning booleans", () => {
    renderPanel();

    expect(screen.getByText("Diagnostic only")).toBeInTheDocument();
    expect(screen.getByText("Not promotion approval")).toBeInTheDocument();
    expect(screen.getByText("Not threshold recommendation")).toBeInTheDocument();
    expect(screen.getByText("Not production decisioning")).toBeInTheDocument();
    expect(screen.getByText("Not payment authorization")).toBeInTheDocument();
    expect(screen.getByText("Not automatic decisioning")).toBeInTheDocument();
    expect(screen.getByText("Not analyst recommendation")).toBeInTheDocument();
  });

  it("renders input summary", () => {
    renderPanel();

    expect(screen.getByText("Shadow summary present")).toBeInTheDocument();
    expect(screen.getByText("SHADOW_PERFORMANCE_SUMMARY_V2")).toBeInTheDocument();
    expect(screen.getByText("Minimum diagnostic evidence records")).toBeInTheDocument();
    expect(screen.getByText("Records evaluated")).toBeInTheDocument();
  });

  it("renders checks table", () => {
    renderPanel();

    expect(screen.getByRole("heading", { name: "Checks" })).toBeInTheDocument();
    expect(screen.getByText("CURRENT_SUMMARY_PRESENT")).toBeInTheDocument();
    expect(screen.getAllByText("PASS").length).toBeGreaterThan(0);
    expect(screen.getAllByText("INFO").length).toBeGreaterThan(0);
  });

  it("renders warnings limitations and reason codes", () => {
    renderPanel();

    expect(screen.getByRole("heading", { name: "Reason codes" })).toBeInTheDocument();
    expect(screen.getByText("No reason codes reported.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Warnings" })).toBeInTheDocument();
    expect(screen.getByText("MISSING_ML_SIGNAL_PRESENT")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Limitations" })).toBeInTheDocument();
    expect(screen.getByText("OFFLINE_DIAGNOSTIC_AID_ONLY")).toBeInTheDocument();
  });

  it("renders valid device merchant and customer machine codes in warnings and limitations", () => {
    renderPanel({
      report: report({
        warnings: ["MERCHANT_SEGMENT_COVERAGE"],
        limitations: [
          "CUSTOMER_SEGMENT_COVERAGE",
          "DOES_NOT_AUTHORIZE_PAYMENTS",
          "DOES_NOT_CHANGE_SCORING",
          "DOES_NOT_RECOMMEND_THRESHOLDS",
          "HUMAN_REVIEW_START_ONLY",
          "OFFLINE_DIAGNOSTIC_AID_ONLY"
        ]
      })
    });

    expect(screen.getByText("MERCHANT_SEGMENT_COVERAGE")).toBeInTheDocument();
    expect(screen.getByText("CUSTOMER_SEGMENT_COVERAGE")).toBeInTheDocument();
  });

  it.each([
    [401, "You are not authenticated."],
    [403, "You do not have permission to read Promotion Review Readiness diagnostics."],
    [404, "No current Promotion Review Readiness report is configured. Generate and expose the diagnostic report first."],
    [503, "Promotion Review Readiness report is unavailable or invalid. Do not use this report for assessment."]
  ])("renders %s state", (status, message) => {
    renderPanel({ report: null, error: { status } });

    expect(screen.getByText(message)).toBeInTheDocument();
  });

  it("renders network error state without raw details", () => {
    renderPanel({ report: null, error: new Error("endpoint token secret stacktrace") });

    expect(screen.getByText("Could not reach the Promotion Review Readiness API.")).toBeInTheDocument();
    expect(screen.queryByText(/token secret stacktrace/i)).not.toBeInTheDocument();
  });

  it("renders invalid response state", () => {
    renderPanel({ report: null, error: { state: "invalid-response" } });

    expect(screen.getByText("The Promotion Review Readiness response is malformed or missing required safety fields.")).toBeInTheDocument();
  });

  it.each([
    ["rawPayload term", { checks: [{ name: "RAW_PAYLOAD", status: "PASS", severity: "INFO" }] }],
    ["transactionReference term", { reasonCodes: ["TRANSACTION_REFERENCE"] }],
    ["filesystem path", { warnings: ["C:\\SECRET_PATH"] }],
    ["stacktrace term", { limitations: ["STACKTRACE"] }],
    ["secret term", { reasonCodes: ["TOKEN_SECRET"] }]
  ])("renders invalid response state for %s", (_name, patch) => {
    renderPanel({ report: report(patch) });

    expect(screen.getByText("The Promotion Review Readiness response is malformed or missing required safety fields.")).toBeInTheDocument();
    expect(screen.queryByText("CURRENT_SUMMARY_PRESENT")).not.toBeInTheDocument();
  });

  it("renders malformed response state", () => {
    renderPanel({ report: report({ diagnosticOnly: false }) });

    expect(screen.getByText("The Promotion Review Readiness response is malformed or missing required safety fields.")).toBeInTheDocument();
    expect(screen.queryByText("CURRENT_SUMMARY_PRESENT")).not.toBeInTheDocument();
  });

  it("does not expose raw exception details filesystem paths stack traces secrets or raw artifact content", () => {
    const { container } = renderPanel({
      report: report({
        rawArtifactContent: "raw artifact content secret",
        checks: [{ name: "C:/Users/mpods/secret/path stacktrace token", status: "PASS", severity: "INFO" }]
      })
    });

    expect(container.textContent).not.toMatch(/C:\/Users|secret|stacktrace|token|raw artifact/i);
  });

  it("does not expose raw FDP artifact data or entity identifiers", () => {
    const { container } = renderPanel({
      report: report({
        fdp102: "raw FDP-102 data",
        fdp103: "raw FDP-103 data",
        fdp104: "raw FDP-104 data",
        transactionReference: "transaction-reference-secret",
        customerId: "customer-secret",
        accountId: "account-secret",
        cardId: "card-secret",
        deviceId: "device-secret",
        merchantId: "merchant-secret"
      })
    });

    expect(container.textContent).not.toMatch(/raw FDP-10[234]|transaction-reference-secret|customer-secret|account-secret|card-secret|device-secret|merchant-secret/i);
  });

  it("does not add action buttons", () => {
    renderPanel();

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("renders retry diagnostics for 503 state", () => {
    renderPanel({ report: null, error: { status: 503 }, onRetry: vi.fn() });

    expect(screen.getByRole("button", { name: "Retry diagnostics" })).toBeInTheDocument();
  });

  it("retry diagnostics calls onRetry once", () => {
    const onRetry = vi.fn();
    renderPanel({ report: null, error: { status: 503 }, onRetry });

    fireEvent.click(screen.getByRole("button", { name: "Retry diagnostics" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("does not render retry diagnostics on valid success", () => {
    renderPanel({ onRetry: vi.fn() });

    expect(screen.queryByRole("button", { name: "Retry diagnostics" })).not.toBeInTheDocument();
  });

  it("retry diagnostics is not approval workflow threshold payment or analyst action wording", () => {
    renderPanel({ report: null, error: { status: 503 }, onRetry: vi.fn() });

    const button = screen.getByRole("button", { name: "Retry diagnostics" });
    expect(button).toBeInTheDocument();
    expect(button).not.toHaveTextContent(/approve|workflow|threshold|payment|analyst action/i);
  });

  it("renders safe no permission state", () => {
    renderPanel({ canReadPromotionReadiness: false, report: null });

    expect(screen.getByText("You do not have permission to read Promotion Review Readiness diagnostics. Required permission: promotion-readiness:read.")).toBeInTheDocument();
    expect(screen.queryByText("CURRENT_SUMMARY_PRESENT")).not.toBeInTheDocument();
  });
});

function renderPanel(overrides = {}) {
  return render(
    <PromotionReviewReadinessPanel
      report={report()}
      isLoading={false}
      error={null}
      canReadPromotionReadiness
      {...overrides}
    />
  );
}

function report(overrides = {}) {
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
        reportType: "SHADOW_PERFORMANCE_SUMMARY_V2",
        summaryVersion: "shadow-performance-summary-v2",
        generatedAt: "2026-06-08T02:00:00Z"
      },
      minimumDiagnosticEvidenceRecords: 1,
      recordsEvaluated: 3
    },
    checkInputs: promotionReadinessCheckInputs(),
    checks: promotionReadinessChecks(),
    reasonCodes: [],
    warnings: ["MISSING_ML_SIGNAL_PRESENT"],
    limitations: [
      "DOES_NOT_AUTHORIZE_PAYMENTS",
      "DOES_NOT_CHANGE_SCORING",
      "DOES_NOT_RECOMMEND_THRESHOLDS",
      "HUMAN_REVIEW_START_ONLY",
      "OFFLINE_DIAGNOSTIC_AID_ONLY"
    ],
    banner: "Promotion review readiness is an offline diagnostic aid only. It is not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.",
    ...overrides
  };
}

function reportWithCheckStatus(checkName, status) {
  let inputs = report().inputs;
  let checkInputs = promotionReadinessCheckInputs();
  if (checkName === "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS" && status === "FAIL") {
    inputs = { ...inputs, minimumDiagnosticEvidenceRecords: 4 };
    checkInputs = { ...checkInputs, minimumDiagnosticEvidenceRecords: 4 };
  }
  if (checkName === "EVALUATION_CARD_PRESENT" && status === "FAIL") {
    checkInputs = {
      ...checkInputs,
      evaluation: { ...checkInputs.evaluation, evaluationCardType: "LEGACY_EVALUATION_CARD" }
    };
  }
  if (checkName === "ALERT_RECOMMENDED_RECALL_AVAILABLE" && status === "INCONCLUSIVE") {
    checkInputs = {
      ...checkInputs,
      metrics: {
        ...checkInputs.metrics,
        alertRecommendedRecall: { available: false, value: null, reason: "NO_ACTUAL_POSITIVES" }
      }
    };
  }
  const checks = promotionReadinessChecks().map((item) => (
    item.name === checkName ? { ...item, status } : item
  ));
  return report({
    readinessStatus: deriveReadinessStatus(checks),
    inputs,
    checkInputs,
    checks,
    reasonCodes: derivedReasonCodes(checks)
  });
}

function promotionReadinessCheckInputs() {
  return {
    sourceShadowSummaryManifestSha256: "a".repeat(64),
    shadowPerformanceSummary: {
      present: true,
      reportType: "SHADOW_PERFORMANCE_SUMMARY_V2",
      summaryVersion: "shadow-performance-summary-v2",
      generatedAt: "2026-06-08T02:00:00Z",
      sourceEvaluationCardManifestSha256: "b".repeat(64)
    },
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
      evaluationReportType: "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
    },
    metricBasis: "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK",
    minimumDiagnosticEvidenceRecords: 1,
    recordsEvaluated: 3,
    metrics: {
      alertRecommendedPrecision: metric(0.8),
      alertRecommendedRecall: metric(0.75),
      falsePositiveRate: metric(0.1),
      falseNegativeRate: metric(0.2)
    }
  };
}

function promotionReadinessChecks() {
  return [
    check("CURRENT_SUMMARY_PRESENT"),
    check("CURRENT_SUMMARY_VERSION_SUPPORTED"),
    check("EVALUATION_CARD_PRESENT"),
    check("EVALUATION_CARD_VERSION_SUPPORTED"),
    check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY"),
    check("NOT_PRODUCTION_APPROVAL_TRUE"),
    check("NOT_PROMOTION_APPROVAL_TRUE"),
    check("NOT_THRESHOLD_RECOMMENDATION_TRUE"),
    check("NOT_PAYMENT_AUTHORIZATION_TRUE"),
    check("NOT_AUTOMATIC_DECISIONING_TRUE"),
    check("EVALUATION_REPORT_TYPE_SUPPORTED"),
    check("METRIC_BASIS_SUPPORTED"),
    check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", "PASS", "HIGH"),
    check("ALERT_RECOMMENDED_PRECISION_AVAILABLE", "PASS", "MEDIUM"),
    check("ALERT_RECOMMENDED_RECALL_AVAILABLE", "PASS", "MEDIUM"),
    check("FALSE_POSITIVE_RATE_AVAILABLE", "PASS", "MEDIUM"),
    check("FALSE_NEGATIVE_RATE_AVAILABLE", "PASS", "MEDIUM")
  ];
}

function check(name, status = "PASS", severity = "INFO") {
  return { name, status, severity };
}

function metric(value) {
  return { available: true, value, reason: null };
}

function deriveReadinessStatus(checks) {
  const failedChecks = checks.filter((item) => item.status === "FAIL");
  if (failedChecks.some((item) => item.name !== "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS")) {
    return "NOT_REVIEWABLE";
  }
  if (failedChecks.some((item) => item.name === "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS")) {
    return "INSUFFICIENT_DATA";
  }
  return checks.some((item) => item.status === "INCONCLUSIVE") ? "INCONCLUSIVE" : "REVIEWABLE";
}

function derivedReasonCodes(checks) {
  return checks.flatMap((item) => {
    if (item.status === "FAIL") {
      return [`${item.name}_FAILED`];
    }
    if (item.status === "INCONCLUSIVE") {
      return [`${item.name}_INCONCLUSIVE`];
    }
    return [];
  }).sort();
}
