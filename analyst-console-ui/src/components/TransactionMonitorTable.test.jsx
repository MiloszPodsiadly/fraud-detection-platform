import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TransactionMonitorTable } from "./TransactionMonitorTable.jsx";

describe("TransactionMonitorTable alert recommendation", () => {
  it.each([
    ["true", true, "Review recommended", "classificationReview", false],
    ["false", false, "No review recommendation", "classificationNeutral", false],
    ["null", null, "Unknown", "classificationNeutral", false],
    ["undefined", undefined, "Unknown", "classificationNeutral", false],
    ["absent", undefined, "Unknown", "classificationNeutral", true]
  ])("renders %s without claiming a final decision", (_state, value, label, style, omitRecommendation) => {
    const item = transaction();
    if (!omitRecommendation) item.alertRecommended = value;

    render(<TransactionMonitorTable transactions={[item]} />);

    expect(screen.getByRole("columnheader", { name: "Alert recommendation" })).toBeInTheDocument();
    const row = screen.getByRole("row", { name: /txn-tristate/ });
    expect(within(row).getByText(label)).toHaveClass("classificationPill", style);
    expect(row).not.toHaveTextContent(/\bLegitimate\b|\bApproved\b|fraud decision|payment decision/i);
  });
});

function transaction() {
  return {
    transactionId: "txn-tristate",
    customerId: "customer-1",
    merchantInfo: { merchantName: "Merchant", channel: "ECOMMERCE" },
    transactionAmount: { amount: 10, currency: "USD" },
    scoredAt: "2026-11-04T10:00:00Z",
    fraudScore: 0.91,
    riskLevel: "CRITICAL"
  };
}
