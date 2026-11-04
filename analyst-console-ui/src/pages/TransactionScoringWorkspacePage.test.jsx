import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TransactionScoringWorkspacePage } from "./TransactionScoringWorkspacePage.jsx";

vi.mock("../components/TransactionRiskIntelligencePanel.jsx", () => ({
  TransactionRiskIntelligencePanel: ({ transactionId, enabled, panelId }) => (
    <section id={panelId} aria-label="Mock transaction risk intelligence">Risk intelligence for {transactionId}; enabled: {String(enabled)}</section>
  ),
  transactionRiskIntelligencePanelId: (transactionId) => `transaction-risk-intelligence-${String(transactionId).replace(/[^A-Za-z0-9_-]+/g, "-")}`
}));

describe("TransactionScoringWorkspacePage", () => {
  it("integrates Transaction Risk Intelligence into one expanded scored transaction row with aria wiring", () => {
    render(
      <TransactionScoringWorkspacePage
        canReadTransactions
        transactionPage={{
          content: [
            transaction("txn-1"),
            transaction("txn-2")
          ],
          page: 0,
          size: 25,
          totalPages: 1,
          totalElements: 2
        }}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        apiClient={{ getScoredTransactionDetail: vi.fn() }}
      />
    );

    expect(screen.queryByRole("region", { name: "Mock transaction risk intelligence" })).not.toBeInTheDocument();

    const detailButtons = screen.getAllByRole("button", { name: "Details" });
    expect(detailButtons[0]).toHaveAttribute("aria-expanded", "false");
    expect(detailButtons[1]).toHaveAttribute("aria-expanded", "false");
    expect(detailButtons[1]).toHaveAttribute("aria-controls", "transaction-risk-intelligence-txn-2");

    fireEvent.click(detailButtons[1]);

    expect(screen.getByRole("region", { name: "Mock transaction risk intelligence" })).toHaveTextContent("txn-2");
    expect(screen.getByRole("region", { name: "Mock transaction risk intelligence" })).toHaveTextContent("enabled: true");
    expect(screen.getByRole("region", { name: "Mock transaction risk intelligence" })).toHaveAttribute("id", "transaction-risk-intelligence-txn-2");
    expect(screen.getByRole("button", { name: "Hide details" })).toHaveAttribute("aria-expanded", "true");
    expect(screen.queryByText("Risk intelligence for txn-1")).not.toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "Details" })[0]);

    expect(screen.getByRole("region", { name: "Mock transaction risk intelligence" })).toHaveTextContent("txn-1");
    expect(screen.queryByText("Risk intelligence for txn-2")).not.toBeInTheDocument();
  });

  it("keeps the details button keyboard reachable and closes when selected transaction disappears", () => {
    const { rerender } = render(
      <TransactionScoringWorkspacePage
        canReadTransactions
        transactionPage={{
          content: [
            transaction("txn-1"),
            transaction("txn-2")
          ],
          page: 0,
          size: 25,
          totalPages: 1,
          totalElements: 2
        }}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        apiClient={{ getScoredTransactionDetail: vi.fn() }}
      />
    );

    const firstDetailsButton = screen.getAllByRole("button", { name: "Details" })[0];
    firstDetailsButton.focus();
    expect(firstDetailsButton).toHaveFocus();
    fireEvent.keyDown(firstDetailsButton, { key: "Enter", code: "Enter" });
    fireEvent.click(firstDetailsButton);
    expect(screen.getByRole("button", { name: "Hide details" })).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(screen.getByRole("button", { name: "Hide details" }));
    expect(screen.queryByRole("region", { name: "Mock transaction risk intelligence" })).not.toBeInTheDocument();

    const secondDetailsButton = screen.getAllByRole("button", { name: "Details" })[1];
    secondDetailsButton.focus();
    fireEvent.keyDown(secondDetailsButton, { key: " ", code: "Space" });
    fireEvent.click(secondDetailsButton);
    expect(screen.getByRole("region", { name: "Mock transaction risk intelligence" })).toHaveTextContent("txn-2");

    rerender(
      <TransactionScoringWorkspacePage
        canReadTransactions
        transactionPage={{
          content: [transaction("txn-1")],
          page: 0,
          size: 25,
          totalPages: 1,
          totalElements: 1
        }}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        apiClient={{ getScoredTransactionDetail: vi.fn() }}
      />
    );

    expect(screen.queryByRole("region", { name: "Mock transaction risk intelligence" })).not.toBeInTheDocument();
  });

  it.each([false, undefined])("closes the whole page when read capability is %s even with a stale page", (capability) => {
    const retry = vi.fn();
    render(
      <TransactionScoringWorkspacePage
        transactionPage={{
          content: [transaction("txn-1")],
          page: 0,
          size: 25,
          totalPages: 1,
          totalElements: 1
        }}
        isLoading={false}
        error={null}
        onRetry={retry}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        apiClient={{ getScoredTransactionDetail: vi.fn() }}
        canReadTransactions={capability}
      />
    );

    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Details" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Search")).not.toBeInTheDocument();
    expect(screen.queryByText("txn-1")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Page size")).not.toBeInTheDocument();
    expect(screen.getByText("Transaction reads unavailable")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(retry).not.toHaveBeenCalled();
  });
});

function transaction(transactionId) {
  return {
    transactionId,
    customerId: "customer-1",
    merchantInfo: { merchantName: "Merchant", channel: "ECOMMERCE" },
    transactionAmount: { amount: 10, currency: "USD" },
    alertRecommended: true,
    scoredAt: "2026-06-18T10:00:00Z",
    fraudScore: 0.91,
    riskLevel: "CRITICAL"
  };
}
