import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TransactionScoringWorkspacePage } from "./TransactionScoringWorkspacePage.jsx";

vi.mock("../components/TransactionRiskIntelligencePanel.jsx", () => ({
  TransactionRiskIntelligencePanel: ({ transactionId }) => (
    <section aria-label="Mock transaction risk intelligence">Risk intelligence for {transactionId}</section>
  )
}));

describe("TransactionScoringWorkspacePage", () => {
  it("integrates Transaction Risk Intelligence into one expanded scored transaction row", () => {
    render(
      <TransactionScoringWorkspacePage
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

    fireEvent.click(screen.getAllByRole("button", { name: "Details" })[1]);

    expect(screen.getByRole("region", { name: "Mock transaction risk intelligence" })).toHaveTextContent("txn-2");
    expect(screen.queryByText("Risk intelligence for txn-1")).not.toBeInTheDocument();
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
