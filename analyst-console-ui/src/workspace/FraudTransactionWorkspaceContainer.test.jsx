import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FraudTransactionWorkspaceContainer } from "./FraudTransactionWorkspaceContainer.jsx";

describe("FraudTransactionWorkspaceContainer", () => {
  it("renders only the alert review workspace", () => {
    render(
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
    expect(screen.queryByRole("heading", { name: "Fraud Case Work Queue" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Transaction scoring stream" })).not.toBeInTheDocument();
  });
});

function page() {
  return { content: [], totalElements: 0, totalPages: 0, page: 0, size: 10 };
}
