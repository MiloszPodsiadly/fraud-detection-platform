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
    expect(screen.getByText("rules-engine")).toBeInTheDocument();
    expect(screen.getAllByText("VELOCITY_SPIKE").length).toBeGreaterThan(0);
  });

  it("rendersComparisonSection", async () => {
    renderPanel(availableResult());

    const comparison = await screen.findByRole("heading", { name: "Diagnostic comparison" });
    const section = comparison.closest("section");
    expect(within(section).getByText("Engine agreement")).toBeInTheDocument();
    expect(within(section).getByText("DISAGREEMENT")).toBeInTheDocument();
    expect(within(section).getByText("Risk mismatch")).toBeInTheDocument();
    expect(within(section).getByText("MISMATCH")).toBeInTheDocument();
    expect(within(section).getByText("Score delta")).toBeInTheDocument();
    expect(within(section).getByText("WIDE")).toBeInTheDocument();
  });

  it("rendersEngineResultsBoundedly", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Engine results" })).toBeInTheDocument();
    expect(screen.getByText("RULES")).toBeInTheDocument();
    expect(screen.getAllByText("COMPLETED").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Score bucket").length).toBeGreaterThan(0);
    expect(screen.getAllByText("HIGH").length).toBeGreaterThan(0);
  });

  it("rendersDiagnosticSignalsBoundedly", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Diagnostic signals" })).toBeInTheDocument();
    expect(screen.getByText("RULE_SIGNAL")).toBeInTheDocument();
    expect(screen.getByText("MODEL_SIGNAL")).toBeInTheDocument();
  });

  it("rendersWarningsBoundedly", async () => {
    renderPanel(availableResult());

    expect(await screen.findByRole("heading", { name: "Warnings" })).toBeInTheDocument();
    expect(screen.getByText("PARTIAL_ENGINE_OUTPUT")).toBeInTheDocument();
    expect(screen.getByText("1")).toBeInTheDocument();
  });

  it("rendersTimeoutAsOperationalStatus", async () => {
    renderPanel(availableResult({ engineStatus: "TIMEOUT" }));

    expect(await screen.findByText("Engine timed out")).toBeInTheDocument();
  });

  it("rendersUnavailableAsOperationalStatus", async () => {
    renderPanel(availableResult({ engineStatus: "UNAVAILABLE" }));

    expect((await screen.findAllByText("Engine unavailable")).length).toBeGreaterThan(0);
  });

  it("rendersDegradedAsOperationalStatus", async () => {
    renderPanel(availableResult({ engineStatus: "DEGRADED" }));

    expect(await screen.findByText("Engine response degraded")).toBeInTheDocument();
  });

  it("doesNotRenderSafeTransactionForOperationalFailure", async () => {
    renderPanel(availableResult({ engineStatus: "UNAVAILABLE", engineRiskLevel: "LOW" }));

    expect((await screen.findAllByText("Engine unavailable")).length).toBeGreaterThan(0);
    expect(panelText()).not.toMatch(/LOW risk|safe|no fraud|less severe|not suspicious|low risk because ML unavailable/i);
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
    expect(screen.getAllByText("COMPLETED").length).toBeGreaterThan(0);
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

function availableResult({ engineStatus = "COMPLETED", engineRiskLevel = "HIGH" } = {}) {
  return {
    state: "available",
    available: true,
    transactionId: "txn-1",
    comparison: {
      agreementStatus: "DISAGREEMENT",
      riskMismatchStatus: "MISMATCH",
      scoreDeltaBucket: "WIDE"
    },
    engines: [{
      engineId: "rules-engine",
      engineType: "RULES",
      status: engineStatus,
      scoreBucket: "HIGH",
      riskLevel: engineRiskLevel,
      reasonCodes: ["VELOCITY_SPIKE"],
      rawEvidence: "rawEvidence"
    }],
    diagnosticSignals: [{
      signalType: "RULE_SIGNAL",
      engineStatus: "COMPLETED",
      scoreBucket: "HIGH",
      riskLevel: "HIGH",
      reasonCodes: ["VELOCITY_SPIKE"]
    }, {
      signalType: "MODEL_SIGNAL",
      engineStatus: "COMPLETED",
      scoreBucket: "MEDIUM",
      reasonCodes: ["MODEL_DRIFT"]
    }, {
      signalType: "OPERATIONAL_SIGNAL",
      engineStatus: "UNAVAILABLE",
      scoreBucket: "UNAVAILABLE",
      riskLevel: "LOW",
      reasonCodes: ["MODEL_TIMEOUT"]
    }],
    warnings: [{
      warningCode: "PARTIAL_ENGINE_OUTPUT",
      count: 1,
      rawPayload: "rawPayload"
    }]
  };
}

function panelText() {
  return screen.getByTestId("engine-intelligence-panel").textContent;
}
