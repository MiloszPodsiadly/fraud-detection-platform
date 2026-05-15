import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AnalystWorkspaceContainer } from "./AnalystWorkspaceContainer.jsx";

describe("AnalystWorkspaceContainer", () => {
  it("renders only the analyst fraud-case workspace", () => {
    const { container } = render(
      <AnalystWorkspaceContainer
        canReadFraudCases
        workQueueState={workQueueState()}
        summaryState={{ error: null, retry: vi.fn() }}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Fraud Case Work Queue" })).toBeInTheDocument();
    expect(container.querySelector("[data-workspace-heading]")).toHaveTextContent("Fraud Case Work Queue");
    expect(screen.queryByRole("heading", { name: "Transaction scoring stream" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
  });

  it("keeps workspace-specific callback ownership in the container", () => {
    const retry = vi.fn();
    render(
      <AnalystWorkspaceContainer
        canReadFraudCases
        workQueueState={workQueueState()}
        summaryState={{ error: "summary unavailable", retry }}
        onOpenFraudCase={vi.fn()}
      />
    );

    screen.getByRole("button", { name: "Retry summary" }).click();

    expect(retry).toHaveBeenCalledTimes(1);
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

function workQueueState() {
  return {
    queue: emptyWorkQueue(),
    committedFilters: emptyWorkQueueRequest(),
    draftFilters: emptyWorkQueueRequest(),
    warning: null,
    filterError: null,
    lastRefreshedAt: null,
    isLoading: false,
    error: null,
    updateDraftFilter: vi.fn(),
    applyFilters: vi.fn(),
    resetFilters: vi.fn(),
    refreshFirstSlice: vi.fn(),
    loadMore: vi.fn()
  };
}
