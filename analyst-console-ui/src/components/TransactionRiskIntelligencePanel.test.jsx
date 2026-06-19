import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TransactionRiskIntelligencePanel } from "./TransactionRiskIntelligencePanel.jsx";
import { useScoredTransactionDetail } from "../transactions/useScoredTransactionDetail.js";
import { transactionRiskIntelligencePanelId } from "../transactions/transactionRiskIntelligencePanelId.js";
import {
  absentDetail,
  availableDetail,
  comparisonNullDetail,
  degradedDetail,
  emptyArraysDetail,
  invalidResponseError,
  networkError,
  notFoundError,
  permissionDeniedError,
  unavailableDetail
} from "../transactions/transactionRiskIntelligenceFixtures.js";

vi.mock("../transactions/useScoredTransactionDetail.js", () => ({
  useScoredTransactionDetail: vi.fn()
}));

describe("TransactionRiskIntelligencePanel", () => {
  beforeEach(() => {
    useScoredTransactionDetail.mockReturnValue({ detail: availableDetail(), isLoading: false, error: null });
  });

  it("renders header and persistent diagnostic boundary banner", () => {
    renderPanel();

    expect(screen.getByRole("heading", { name: "Transaction Risk Intelligence" })).toBeInTheDocument();
    const boundary = screen.getByRole("region", { name: "Diagnostic Boundary" });
    expect(boundary).toHaveTextContent("read-only diagnostic view");
    expect(boundary).toHaveTextContent("does not approve");
    expect(boundary).toHaveTextContent("does not authorize payment");
    expect(boundary).toHaveTextContent("does not recommend analyst action");
  });

  it("uses a stable sanitized panel id", () => {
    renderPanel({ transactionId: "txn/unsafe id:1" });

    expect(screen.getByRole("region", { name: "Transaction Risk Intelligence" })).toHaveAttribute(
      "id",
      "transaction-risk-intelligence-txn-unsafe-id-1"
    );
    expect(transactionRiskIntelligencePanelId("txn/unsafe id:1")).toBe("transaction-risk-intelligence-txn-unsafe-id-1");
  });

  it("renders transaction summary and alertRecommended safe wording", () => {
    renderPanel();

    const summary = screen.getByRole("region", { name: "Transaction Summary" });
    expect(within(summary).getByText("txn-available-1")).toBeInTheDocument();
    expect(within(summary).getByText("corr-available-1")).toBeInTheDocument();
    expect(within(summary).getByText("0.91")).toBeInTheDocument();
    expect(within(summary).getByText("Alert recommendation flag")).toBeInTheDocument();
    expect(within(summary).getByText("Present")).toBeInTheDocument();
    expect(screen.getByText("alertRecommended is a scored transaction field, not a final payment decision.")).toBeInTheDocument();
  });

  it("does not enable detail fetch when panel reads are disabled", () => {
    renderPanel({ enabled: false });

    expect(useScoredTransactionDetail).toHaveBeenCalledWith(expect.objectContaining({
      transactionId: "txn-available-1",
      enabled: false
    }));
    expect(screen.getByText("Transaction risk intelligence is not available without transaction read permission.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Transaction Summary" })).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Diagnostic Boundary" })).toBeInTheDocument();
  });

  it.each([
    [availableDetail(), "AVAILABLE", "Engine Intelligence is available for this transaction."],
    [absentDetail(), "ABSENT", "No Engine Intelligence projection exists for this scored transaction."],
    [unavailableDetail(), "UNAVAILABLE", "The scored transaction was found, but Engine Intelligence could not be safely read."],
    [degradedDetail(), "DEGRADED", "Engine Intelligence is available with limitations."]
  ])("renders %s state with text label", (detail, status, copy) => {
    useScoredTransactionDetail.mockReturnValue({ detail, isLoading: false, error: null });

    renderPanel({ transactionId: detail.transactionId });

    const statusRegion = screen.getByRole("region", { name: "Engine Intelligence Status" });
    expect(within(statusRegion).getByText(status)).toBeInTheDocument();
    expect(within(statusRegion).getByText(copy, { exact: false })).toBeInTheDocument();
  });

  it("renders projected comparison and null comparison empty state", () => {
    const { rerender } = renderPanel();

    const comparison = screen.getByRole("region", { name: "Projected Comparison" });
    expect(within(comparison).getByText("PARTIAL")).toBeInTheDocument();
    expect(within(comparison).getByText("Agreement status describes projected engine comparison only.")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({
      detail: comparisonNullDetail(),
      isLoading: false,
      error: null
    });
    rerender(<TransactionRiskIntelligencePanel transactionId="txn-comparison-null-1" apiClient={{}} />);

    expect(screen.getByText("Projected engine comparison is not available for this transaction.")).toBeInTheDocument();
  });

  it("renders engine results diagnostic signals and warnings sections", () => {
    renderPanel();

    expect(screen.getByRole("region", { name: "Engine Results" })).toHaveTextContent("rules.primary");
    expect(screen.getByRole("region", { name: "Diagnostic Signals" })).toHaveTextContent("FRAUD_SIGNAL");
    expect(screen.getByRole("region", { name: "Warnings and Limitations" })).toHaveTextContent("ENGINE_RESULT_LIMIT_APPLIED");
    expect(screen.getByText("Warnings are not operational instructions.")).toBeInTheDocument();
  });

  it("renders empty bounded arrays safely", () => {
    useScoredTransactionDetail.mockReturnValue({
      detail: emptyArraysDetail(),
      isLoading: false,
      error: null
    });

    renderPanel({ transactionId: "txn-empty-arrays-1" });

    expect(screen.getByText("No bounded engine results are available in the projected diagnostics.")).toBeInTheDocument();
    expect(screen.getByText("No bounded diagnostic signals are available.")).toBeInTheDocument();
    expect(screen.getByText("No warnings are present in the projected diagnostics.")).toBeInTheDocument();
  });

  it.each([
    [{ status: 400 }, "This transaction identifier is invalid."],
    [permissionDeniedError(401), "You do not have permission to read this transaction risk intelligence."],
    [permissionDeniedError(403), "You do not have permission to read this transaction risk intelligence."],
    [notFoundError(), "Scored transaction not found."],
    [networkError(), "Transaction risk intelligence is temporarily unavailable."],
    [{ name: "AbortError" }, "Transaction risk intelligence loading was interrupted before diagnostics could be displayed."]
  ])("renders safe error state %#", (error, copy) => {
    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: false, error });

    renderPanel();

    expect(screen.getByText(copy)).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Diagnostic Boundary" })).toBeInTheDocument();
  });

  it("renders loading empty selection and invalid response states", () => {
    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: true, error: null });
    const { rerender } = renderPanel();
    expect(screen.getByText("Loading transaction risk intelligence...")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({ detail: null, isLoading: false, error: null });
    rerender(<TransactionRiskIntelligencePanel transactionId="" apiClient={{}} />);
    expect(screen.getByText("No transaction selected.")).toBeInTheDocument();

    useScoredTransactionDetail.mockReturnValue({
      detail: null,
      isLoading: false,
      error: invalidResponseError()
    });
    rerender(<TransactionRiskIntelligencePanel transactionId="txn-available-1" apiClient={{}} />);
    expect(screen.getByText("The transaction risk intelligence response is malformed or missing required safety fields.")).toBeInTheDocument();
  });

  it("does not render feedback controls action buttons positive decisioning wording or raw fields", () => {
    const { container } = renderPanel();
    const text = container.textContent.toLowerCase();

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(container.querySelector("form")).toBeNull();
    expect(text).not.toContain("safe to approve");
    expect(text).not.toContain("recommended action");
    expect(text).not.toContain("rawmlrequest");
    expect(text).not.toContain("rawmlresponse");
    expect(text).not.toContain("rawfeaturevector");
    expect(text).not.toContain("rawevidence");
    expect(text).not.toContain("groundtruth");
    expect(text).not.toContain("traininglabel");
  });
});

function renderPanel(props = {}) {
  return render(<TransactionRiskIntelligencePanel transactionId="txn-available-1" apiClient={{}} {...props} />);
}
