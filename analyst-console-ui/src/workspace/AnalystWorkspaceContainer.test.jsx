import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AnalystWorkspaceContainer } from "./AnalystWorkspaceContainer.jsx";

describe("AnalystWorkspaceContainer", () => {
  it("renders only the analyst fraud-case workspace", () => {
    const { container } = render(
      <AnalystWorkspaceContainer
        canReadFraudCases
        fraudCaseWorkQueue={emptyWorkQueue()}
        fraudCaseWorkQueueRequest={emptyWorkQueueRequest()}
        fraudCaseWorkQueueDraftFilters={emptyWorkQueueRequest()}
        isFraudCaseWorkQueueLoading={false}
        onFraudCaseSummaryRetry={vi.fn()}
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
        onFraudCaseWorkQueueRetry={vi.fn()}
        onFraudCaseWorkQueueRefreshFirstSlice={vi.fn()}
        onFraudCaseWorkQueueLoadMore={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Fraud Case Work Queue" })).toBeInTheDocument();
    expect(container.querySelector("[data-workspace-heading]")).toHaveTextContent("Fraud Case Work Queue");
    expect(screen.queryByRole("heading", { name: "Transaction scoring stream" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
  });
});

function emptyWorkQueue() {
  return { content: [], size: 20, hasNext: false, nextCursor: null, sort: "createdAt,desc" };
}

function emptyWorkQueueRequest() {
  return {
    size: 20,
    status: "ALL",
    priority: "ALL",
    riskLevel: "ALL",
    assignee: "",
    createdFrom: "",
    createdTo: "",
    updatedFrom: "",
    updatedTo: "",
    linkedAlertId: "",
    sort: "createdAt,desc"
  };
}
