import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TransactionRiskIntelligencePanel } from "./TransactionRiskIntelligencePanel.jsx";
import { useScoredTransactionDetail } from "../transactions/useScoredTransactionDetail.js";

vi.mock("../transactions/useScoredTransactionDetail.js", () => ({
  useScoredTransactionDetail: vi.fn()
}));

describe("TransactionRiskIntelligencePanel", () => {
  beforeEach(() => {
    useScoredTransactionDetail.mockReturnValue({ detail: detail(), isLoading: false, error: null });
  });

  it("renders header and non-decisioning subtitle", () => {
    renderPanel();

    expect(screen.getByRole("heading", { name: "Transaction Risk Intelligence" })).toBeInTheDocument();
    expect(screen.getByText(/Read-only engine diagnostics for analyst review/)).toBeInTheDocument();
    expect(screen.getByText(/does not approve, decline, block/)).toBeInTheDocument();
  });

  it("renders scored transaction summary and alertRecommended safe wording", () => {
    renderPanel();

    const summary = screen.getByRole("region", { name: "Scored transaction summary" });
    expect(within(summary).getByText("txn-1")).toBeInTheDocument();
    expect(within(summary).getByText("corr-1")).toBeInTheDocument();
    expect(within(summary).getByText("0.91")).toBeInTheDocument();
    expect(screen.getByText("alertRecommended is a scored transaction field, not a final payment decision.")).toBeInTheDocument();
  });

  it.each([
    ["AVAILABLE", "Engine Intelligence is available for this transaction."],
    ["ABSENT", "No Engine Intelligence projection exists for this transaction."],
    ["UNAVAILABLE", "Engine Intelligence could not be safely read for this transaction."],
    ["DEGRADED", "Engine Intelligence is available with limitations."]
  ])("renders %s safely", (status, copy) => {
    useScoredTransactionDetail.mockReturnValue({
      detail: detail({ engineIntelligence: engineIntelligence({ status }) }),
      isLoading: false,
      error: null
    });

    renderPanel();

    expect(screen.getAllByText(status).length).toBeGreaterThan(0);
    expect(screen.getByText(copy)).toBeInTheDocument();
  });

  it("renders comparison summary when present and unavailable copy when null", () => {
    const { rerender } = renderPanel();

    expect(screen.getByText("PARTIAL")).toBeInTheDocument();
    expect(screen.getByText("Agreement status describes projected engine comparison only.")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({
      detail: detail({ engineIntelligence: engineIntelligence({ comparison: null }) }),
      isLoading: false,
      error: null
    });
    rerender(<TransactionRiskIntelligencePanel transactionId="txn-1" apiClient={{}} />);

    expect(screen.getByText("Comparison data is not available for this transaction.")).toBeInTheDocument();
  });

  it("renders engine results diagnostic signals and warnings", () => {
    renderPanel();

    expect(screen.getByRole("region", { name: "Engine results" })).toHaveTextContent("rules.primary");
    expect(screen.getByRole("region", { name: "Diagnostic signals" })).toHaveTextContent("FRAUD_SIGNAL");
    expect(screen.getByRole("region", { name: "Warnings" })).toHaveTextContent("ENGINE_RESULT_LIMIT_APPLIED");
    expect(screen.getByText("Warnings are not operational instructions.")).toBeInTheDocument();
  });

  it("renders empty arrays safely", () => {
    useScoredTransactionDetail.mockReturnValue({
      detail: detail({
        engineIntelligence: engineIntelligence({ engines: [], diagnosticSignals: [], warnings: [] })
      }),
      isLoading: false,
      error: null
    });

    renderPanel();

    expect(screen.getByText("No engine results are available in the projected diagnostics.")).toBeInTheDocument();
    expect(screen.getByText("No diagnostic signals are available.")).toBeInTheDocument();
    expect(screen.getByText("No warnings are present.")).toBeInTheDocument();
  });

  it.each([
    [400, "This transaction identifier is invalid."],
    [401, "You do not have permission to read this transaction risk intelligence."],
    [403, "You do not have permission to read this transaction risk intelligence."],
    [404, "Scored transaction not found."],
    [503, "Transaction risk intelligence is temporarily unavailable."]
  ])("renders %s error state", (status, copy) => {
    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: false, error: { status } });

    renderPanel();

    expect(screen.getByText(copy)).toBeInTheDocument();
  });

  it("renders loading disabled and invalid response states", () => {
    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: true, error: null });
    const { rerender } = renderPanel();
    expect(screen.getByText("Loading transaction risk intelligence...")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: false, error: null });
    rerender(<TransactionRiskIntelligencePanel transactionId="" apiClient={{}} />);
    expect(screen.getByText("No transaction selected.")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({
      detail: null,
      isLoading: false,
      error: new Error("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE")
    });
    rerender(<TransactionRiskIntelligencePanel transactionId="txn-1" apiClient={{}} />);
    expect(screen.getByText("The transaction risk intelligence response is malformed or missing required safety fields.")).toBeInTheDocument();
  });

  it("does not render feedback submit UI or mutation action buttons", () => {
    const { container } = renderPanel();

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(container.querySelector("form")).toBeNull();
    expect(container.textContent).not.toContain("safe to approve");
    expect(container.textContent).not.toContain("recommended action");
  });
});

function renderPanel() {
  return render(<TransactionRiskIntelligencePanel transactionId="txn-1" apiClient={{}} />);
}

function detail(overrides = {}) {
  return {
    transactionId: "txn-1",
    correlationId: "corr-1",
    transactionTimestamp: "2026-06-18T10:00:00Z",
    scoredAt: "2026-06-18T10:00:01Z",
    fraudScore: 0.91,
    riskLevel: "CRITICAL",
    alertRecommended: true,
    reasonCodes: ["HIGH_VELOCITY"],
    engineIntelligence: engineIntelligence(),
    ...overrides
  };
}

function engineIntelligence(overrides = {}) {
  return {
    status: "AVAILABLE",
    contractVersion: 1,
    generatedAt: "2026-06-18T10:00:02Z",
    comparison: {
      agreementStatus: "PARTIAL",
      riskMismatchStatus: "NOT_COMPARABLE",
      scoreDeltaBucket: "UNAVAILABLE"
    },
    engines: [{
      engineId: "rules.primary",
      engineType: "RULES",
      status: "AVAILABLE",
      riskLevel: "CRITICAL",
      scoreBucket: "HIGH",
      reasonCodes: ["HIGH_VELOCITY"]
    }],
    diagnosticSignals: [{
      engineId: "rules.primary",
      engineType: "RULES",
      engineStatus: "AVAILABLE",
      signalCategory: "FRAUD_SIGNAL",
      riskLevel: "CRITICAL",
      scoreBucket: "HIGH",
      reasonCode: "HIGH_VELOCITY"
    }],
    warnings: [{ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1 }],
    ...overrides
  };
}
