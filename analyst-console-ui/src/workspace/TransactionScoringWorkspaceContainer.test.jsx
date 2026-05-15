import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TransactionScoringWorkspaceContainer } from "./TransactionScoringWorkspaceContainer.jsx";

describe("TransactionScoringWorkspaceContainer", () => {
  it("renders only scored transactions and sends filters to request state", () => {
    const onTransactionFiltersChange = vi.fn();
    render(
      <TransactionScoringWorkspaceContainer
        transactionPage={{ content: [transaction("txn-1")], totalElements: 73, totalPages: 8, page: 0, size: 10 }}
        transactionPageRequest={{ page: 0, size: 10, query: "", riskLevel: "ALL", status: "ALL" }}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onTransactionFiltersChange={onTransactionFiltersChange}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Transaction scoring stream" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Risk", { selector: "select" }), { target: { value: "CRITICAL" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(onTransactionFiltersChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      query: "",
      riskLevel: "CRITICAL",
      status: "ALL"
    });
  });
});

function transaction(transactionId) {
  return {
    transactionId,
    customerId: "customer-1",
    transactionAmount: { amount: 120, currency: "PLN" },
    merchantInfo: { merchantName: "Merchant" },
    fraudScore: 0.1,
    riskLevel: "LOW",
    alertRecommended: false,
    scoredAt: "2026-05-11T10:00:00Z"
  };
}
