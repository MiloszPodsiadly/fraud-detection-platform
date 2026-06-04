import { render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { EngineIntelligencePanel } from "./EngineIntelligencePanel.jsx";

describe("EngineIntelligencePanel", () => {
  it("rendersLoadingStateWithoutBlockingPage", () => {
    render(
      <>
        <p>Open case</p>
        <EngineIntelligencePanel transactionId="txn-1" getEngineIntelligence={() => new Promise(() => {})} />
      </>
    );

    expect(screen.getByText("Open case")).toBeInTheDocument();
    expect(screen.getByText("Loading engine intelligence...")).toBeInTheDocument();
  });

  it("rendersNotProjectedEmptyState", async () => {
    renderPanel({ state: "not-projected", available: false, reason: "NOT_PROJECTED" });

    expect(await screen.findByText("Engine intelligence is not available for this transaction.")).toBeInTheDocument();
    expect(screen.getByText("This may happen for older transactions or periods when diagnostic emission was disabled.")).toBeInTheDocument();
  });

  it.each([
    ["rendersTemporaryUnavailableStateFor503", { state: "unavailable" }],
    ["rendersNetworkErrorSafely", () => Promise.reject(new Error("network raw failure token stacktrace"))],
    ["rendersCorruptedResponseShapeAsSafeUnavailable", { state: "unavailable", rawEvidence: "secret" }]
  ])("%s", async (_name, result) => {
    const getEngineIntelligence = typeof result === "function"
      ? vi.fn().mockImplementation(result)
      : vi.fn().mockResolvedValue(result);
    render(<EngineIntelligencePanel transactionId="txn-1" getEngineIntelligence={getEngineIntelligence} />);

    expect(await screen.findByText("Engine intelligence is temporarily unavailable.")).toBeInTheDocument();
    expect(screen.queryByText(/raw failure|token|stacktrace|secret/i)).not.toBeInTheDocument();
  });

  it("rendersUnauthorizedAndNotFoundStates", async () => {
    const { rerender } = renderPanel({ state: "unauthorized" });

    expect(await screen.findByText("Engine intelligence access denied.")).toBeInTheDocument();

    rerender(<EngineIntelligencePanel transactionId="txn-1" getEngineIntelligence={vi.fn().mockResolvedValue({ state: "not-found" })} />);

    expect(await screen.findByText("Engine intelligence transaction was not found.")).toBeInTheDocument();
  });

  it("rendersAvailableEngineIntelligence", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(screen.getByText("Diagnostic engine output for this transaction.")).toBeInTheDocument();
    expect(screen.getByText("Diagnostic only. Operational statuses and disagreement are investigation context.")).toBeInTheDocument();
    expect(screen.getByText("rules.primary")).toBeInTheDocument();
    expect(screen.getAllByText("HIGH_VELOCITY").length).toBeGreaterThan(0);
  });

  it("rendersFeedbackControlsBelowEngineIntelligencePanelWhenSubmitClientIsProvided", async () => {
    render(
      <EngineIntelligencePanel
        transactionId="txn-1"
        getEngineIntelligence={vi.fn().mockResolvedValue(availableResult())}
        submitEngineIntelligenceFeedback={vi.fn().mockResolvedValue({ state: "saved" })}
        canSubmitFeedback
      />
    );

    const panel = await screen.findByTestId("engine-intelligence-panel");
    expect(within(panel).getByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(within(panel).getByRole("heading", { name: "Was this engine intelligence useful?" })).toBeInTheDocument();
    expect(within(panel).getByRole("button", { name: "Submit feedback" })).toBeDisabled();
  });

  it("rendersFeedbackControlsForNotProjectedStateWhenSubmitClientIsProvided", async () => {
    render(
      <EngineIntelligencePanel
        transactionId="txn-1"
        getEngineIntelligence={vi.fn().mockResolvedValue({ state: "not-projected", available: false, reason: "NOT_PROJECTED" })}
        submitEngineIntelligenceFeedback={vi.fn().mockResolvedValue({ state: "saved" })}
        canSubmitFeedback
      />
    );

    const panel = await screen.findByTestId("engine-intelligence-panel");
    expect(within(panel).getByText("Engine intelligence is not available for this transaction.")).toBeInTheDocument();
    expect(within(panel).getByRole("heading", { name: "Was this engine intelligence useful?" })).toBeInTheDocument();
  });

  it.each([
    ["unavailable", { state: "unavailable" }],
    ["unauthorized", { state: "unauthorized" }],
    ["not-found", { state: "not-found" }]
  ])("doesNotRenderFeedbackControlsFor%sState", async (_state, result) => {
    render(
      <EngineIntelligencePanel
        transactionId="txn-1"
        getEngineIntelligence={vi.fn().mockResolvedValue(result)}
        submitEngineIntelligenceFeedback={vi.fn().mockResolvedValue({ state: "saved" })}
        canSubmitFeedback
      />
    );

    const panel = await screen.findByTestId("engine-intelligence-panel");
    expect(within(panel).queryByRole("heading", { name: "Was this engine intelligence useful?" })).not.toBeInTheDocument();
    expect(within(panel).queryByRole("button", { name: "Submit feedback" })).not.toBeInTheDocument();
  });

  it("rendersDiagnosticOnlyDisclaimer", async () => {
    renderPanel(availableResult());

    expect(await screen.findByText("Diagnostic only. Operational statuses and disagreement are investigation context.")).toBeInTheDocument();
  });

  it("disclaimerDoesNotUseDecisioningActionLanguage", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(panelText()).not.toMatch(/recommended action|final result|decision source|approve|decline|block/i);
  });

  it("rendersComparisonSection", async () => {
    renderPanel(availableResult());

    const comparison = await screen.findByRole("heading", { name: "Diagnostic comparison" });
    const section = comparison.closest("section");
    expect(within(section).getByText("Engine agreement")).toBeInTheDocument();
    expect(within(section).getByText("DISAGREEMENT")).toBeInTheDocument();
    expect(within(section).getByText("Risk mismatch")).toBeInTheDocument();
    expect(within(section).getByText("MATERIAL_RISK_MISMATCH")).toBeInTheDocument();
    expect(within(section).getByText("Score delta")).toBeInTheDocument();
    expect(within(section).getByText("LARGE")).toBeInTheDocument();
  });

  it("rendersEngineResultsBoundedly", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine results" })).toBeInTheDocument();
    expect(screen.getByText("RULES")).toBeInTheDocument();
    expect(screen.getAllByText("AVAILABLE").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Score bucket").length).toBeGreaterThan(0);
    expect(screen.getAllByText("HIGH").length).toBeGreaterThan(0);
  });

  it("rendersDiagnosticSignalsBoundedly", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Diagnostic signals" })).toBeInTheDocument();
    expect(screen.getByText("FRAUD_SIGNAL")).toBeInTheDocument();
    expect(screen.getByText("OPERATIONAL_SIGNAL")).toBeInTheDocument();
  });

  it("rendersWarningsBoundedly", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Warnings" })).toBeInTheDocument();
    expect(screen.getByText("EVIDENCE_UNSAFE_DROPPED")).toBeInTheDocument();
    expect(screen.getByText("1")).toBeInTheDocument();
  });

  it("rendersTimeoutAsOperationalStatus", async () => {
    renderPanel(availableResult({ engineStatus: "TIMEOUT", engineRiskLevel: "", engineScoreBucket: "UNAVAILABLE" }));

    expect(await screen.findByText("Engine timed out")).toBeInTheDocument();
  });

  it("rendersUnavailableAsOperationalStatus", async () => {
    renderPanel(availableResult({ engineStatus: "UNAVAILABLE", engineRiskLevel: "", engineScoreBucket: "UNAVAILABLE" }));

    expect((await screen.findAllByText("Engine unavailable")).length).toBeGreaterThan(0);
  });

  it("rendersDegradedAsOperationalStatus", async () => {
    renderPanel(availableResult({ engineStatus: "DEGRADED", engineRiskLevel: "", engineScoreBucket: "UNAVAILABLE" }));

    expect(await screen.findByText("Engine response degraded")).toBeInTheDocument();
  });

  it("doesNotRenderSafeTransactionForOperationalFailure", async () => {
    renderPanel(availableResult({ engineStatus: "UNAVAILABLE", engineRiskLevel: "", engineScoreBucket: "UNAVAILABLE" }));

    expect((await screen.findAllByText("Engine unavailable")).length).toBeGreaterThan(0);
    expect(panelText()).not.toMatch(/LOW risk|\bsafe\b|no fraud|less severe|not suspicious|low risk because ML unavailable/i);
  });

  it("rendersDiagnosticDisagreementAsDiagnosticOnly", async () => {
    renderPanel(availableResult());

    expect(await screen.findByText("DISAGREEMENT")).toBeInTheDocument();
    expect(panelText()).not.toMatch(/decline recommendation|approve signal|platform verdict/i);
  });

  it("rendersOperationalSignalWithoutFraudSeverity", async () => {
    renderPanel(availableResult());

    const operationalSignal = await screen.findByText("OPERATIONAL_SIGNAL");
    const card = operationalSignal.closest("article");
    expect(within(card).getByText("Engine unavailable")).toBeInTheDocument();
    expect(within(card).queryByText("Risk level")).not.toBeInTheDocument();
  });

  it("operationalSignalUsesSignalCategory", async () => {
    renderPanel(availableResult());

    const operationalSignal = await screen.findByText("OPERATIONAL_SIGNAL");
    expect(operationalSignal.closest("article")).toHaveTextContent("Engine unavailable");
  });

  it.each([
    ["doesNotRenderUnknownFields", "unknownInternalField"],
    ["doesNotRenderRawJson", "{\"available\":true"],
    ["doesNotRenderRawEvidence", "rawEvidence"],
    ["doesNotRenderRawContribution", "rawContribution"],
    ["doesNotRenderFeatureVector", "featureVector"],
    ["doesNotRenderEndpointTokenSecretStacktrace", "token-secret"],
    ["doesNotRenderMongoMetadata", "_id"],
    ["doesNotRenderInternalProjectionClassNames", "EngineIntelligenceProjection"],
    ["doesNotRenderRawErrorResponseBody", "raw error response body"],
    ["doesNotRenderRawDebugPayload", "rawPayload"]
  ])("%s", async (_name, forbidden) => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(panelText()).not.toContain(forbidden);
  });

  it.each([
    ["doesNotRenderFinalDecision", /finalDecision|Final decision/i],
    ["doesNotRenderRecommendedAction", /recommendedAction|Recommended action/i],
    ["doesNotRenderApproveDeclineBlock", /\bApprove\b|\bDecline\b|\bBlock\b/],
    ["doesNotRenderPaymentAuthorization", /paymentAuthorization|Payment decision/i],
    ["doesNotRenderWinningEngine", /winningEngine|Winning engine/i],
    ["doesNotRenderPlatformRiskScore", /platformRiskScore|Platform risk score/i],
    ["doesNotRenderPlatformVerdict", /Platform verdict/i],
    ["doesNotRenderFraudConfirmed", /Fraud confirmed/i],
    ["doesNotRenderSafeTransaction", /Safe transaction/i]
  ])("%s", async (_name, forbidden) => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(panelText()).not.toMatch(forbidden);
  });

  it("doesNotRenderActionButtons", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(within(screen.getByTestId("engine-intelligence-panel")).queryByRole("button")).not.toBeInTheDocument();
  });

  it("doesNotRenderFeedbackForm", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(within(screen.getByTestId("engine-intelligence-panel")).queryByRole("form")).not.toBeInTheDocument();
    expect(panelText()).not.toMatch(/feedback submit|analyst action submit/i);
  });

  it("engineIntelligencePanelHasAccessibleHeading", async () => {
    renderPanel(availableResult());

    const panel = await screen.findByTestId("engine-intelligence-panel");
    expect(within(panel).getByRole("heading", { name: "Engine intelligence" })).toBeInTheDocument();
    expect(panel).toHaveAttribute("aria-labelledby");
  });

  it("statusLabelsAreTextualNotColorOnly", async () => {
    renderPanel(availableResult());

    expect((await screen.findAllByText("Status")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("AVAILABLE").length).toBeGreaterThan(0);
  });

  it.each([
    ["availableStateWithoutComparisonRendersUnavailable", { ...availableResult(), comparison: null }],
    ["availableStateWithMissingEnginesArrayRendersUnavailable", omitAvailableField("engines")],
    ["availableStateWithMissingDiagnosticSignalsArrayRendersUnavailable", omitAvailableField("diagnosticSignals")],
    ["availableStateWithMissingWarningsArrayRendersUnavailable", omitAvailableField("warnings")],
    ["unknownPanelStateRendersUnavailable", { state: "unexpected" }]
  ])("%s", async (_name, result) => {
    renderPanel(result);

    expect(await screen.findByText("Engine intelligence is temporarily unavailable.")).toBeInTheDocument();
    expect(panelText()).not.toContain("rawEvidence");
  });

  it("malformedAvailableStateDoesNotThrowRuntimeException", async () => {
    renderPanel({ state: "available", comparison: {}, rawEvidence: "rawEvidence" });

    expect(await screen.findByText("Engine intelligence is temporarily unavailable.")).toBeInTheDocument();
  });

  it("loadingStateIsAccessible", () => {
    render(<EngineIntelligencePanel transactionId="txn-1" getEngineIntelligence={() => new Promise(() => {})} />);

    expect(screen.getByText("Loading engine intelligence...").closest("[aria-live='polite']")).toBeInTheDocument();
  });

  it("errorStateIsAccessible", async () => {
    renderPanel({ state: "unavailable" });

    expect((await screen.findByText("Engine intelligence is temporarily unavailable.")).closest("[role='status']")).toBeInTheDocument();
  });

  it("emptyStateIsAccessible", async () => {
    renderPanel({ state: "not-projected" });

    expect((await screen.findByText("Engine intelligence is not available for this transaction.")).closest("[role='status']")).toBeInTheDocument();
  });

  it("panelIsKeyboardFriendly", async () => {
    renderPanel(availableResult());

    const panel = await screen.findByTestId("engine-intelligence-panel");
    expect(panel.querySelector("button")).toBeNull();
    expect(panel.querySelector("input")).toBeNull();
  });
});

function renderPanel(result) {
  const getEngineIntelligence = vi.fn().mockResolvedValue(result);
  return render(<EngineIntelligencePanel transactionId="txn-1" getEngineIntelligence={getEngineIntelligence} />);
}

function availableResult({ engineStatus = "AVAILABLE", engineRiskLevel = "HIGH", engineScoreBucket = "HIGH" } = {}) {
  return {
    state: "available",
    available: true,
    transactionId: "txn-1",
    comparison: {
      agreementStatus: "DISAGREEMENT",
      riskMismatchStatus: "MATERIAL_RISK_MISMATCH",
      scoreDeltaBucket: "LARGE"
    },
    engines: [{
      engineId: "rules.primary",
      engineType: "RULES",
      status: engineStatus,
      scoreBucket: engineScoreBucket,
      riskLevel: engineRiskLevel,
      reasonCodes: ["HIGH_VELOCITY"],
      rawEvidence: "rawEvidence"
    }],
    diagnosticSignals: [{
      signalCategory: "FRAUD_SIGNAL",
      engineStatus: "AVAILABLE",
      scoreBucket: "HIGH",
      riskLevel: "HIGH",
      reasonCodes: ["HIGH_VELOCITY"]
    }, {
      signalCategory: "OPERATIONAL_SIGNAL",
      engineStatus: "UNAVAILABLE",
      scoreBucket: "UNAVAILABLE",
      reasonCodes: ["MODEL_TIMEOUT"]
    }],
    warnings: [{
      warningCode: "EVIDENCE_UNSAFE_DROPPED",
      count: 1,
      rawPayload: "rawPayload"
    }]
  };
}

function omitAvailableField(fieldName) {
  const result = availableResult();
  delete result[fieldName];
  return result;
}

function panelText() {
  return screen.getByTestId("engine-intelligence-panel").textContent;
}
