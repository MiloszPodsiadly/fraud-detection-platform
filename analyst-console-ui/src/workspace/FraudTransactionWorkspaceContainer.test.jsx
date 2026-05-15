import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FraudTransactionWorkspaceContainer } from "./FraudTransactionWorkspaceContainer.jsx";

describe("FraudTransactionWorkspaceContainer", () => {
  it("renders only the alert review workspace", () => {
    const { container } = render(
      <FraudTransactionWorkspaceContainer
        alertPage={page()}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Alert review queue" })).toBeInTheDocument();
    expect(container.querySelector("[data-workspace-heading]")).toHaveTextContent("Alert review queue");
    expect(screen.queryByRole("heading", { name: "Fraud Case Work Queue" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Transaction scoring stream" })).not.toBeInTheDocument();
  });

  it("uses loaded-page wording when local filters hide alerts", () => {
    render(
      <FraudTransactionWorkspaceContainer
        alertPage={{ content: [alert("alert-1")], totalElements: 20, totalPages: 2, page: 0, size: 10 }}
        isLoading={false}
        error={null}
        onRetry={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
      />
    );

    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "missing" } });

    expect(screen.getByText("No alerts on this loaded page match the local filters.")).toBeInTheDocument();
    expect(screen.getByText("Change filters or load another page to continue reviewing.")).toBeInTheDocument();
    expect(screen.queryByText(/all alerts/i)).not.toBeInTheDocument();
  });
});

function page() {
  return { content: [], totalElements: 0, totalPages: 0, page: 0, size: 10 };
}

function alert(alertId) {
  return {
    alertId,
    transactionId: "txn-1",
    customerId: "customer-1",
    alertReason: "High risk",
    riskLevel: "HIGH",
    alertStatus: "OPEN"
  };
}
