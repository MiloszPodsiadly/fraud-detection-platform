import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { WorkspaceNavigation } from "./WorkspaceNavigation.jsx";
import { WORKSPACE_ROUTE_REGISTRY } from "./WorkspaceRouteRegistry.jsx";

describe("WorkspaceNavigation suspicious transaction counter copy", () => {
  it("summaryCounterIsLabeledAsWorkspaceAggregate", () => {
    renderSuspiciousNavigation(98);

    const link = screen.getByRole("link", { name: /Workspace signal total 98/i });
    expect(link).toHaveAttribute(
      "title",
      "Workspace signal total. Not page count, fraud count, case count, or analyst workload."
    );
  });

  it("suspiciousCounterDoesNotUseFraudCountWording", () => {
    renderSuspiciousNavigation(98);

    expect(screen.queryByRole("link", { name: /fraud count/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /confirmed fraud/i })).not.toBeInTheDocument();
  });

  it("suspiciousCounterDoesNotUsePaginationTotalWording", () => {
    renderSuspiciousNavigation(98);

    expect(screen.queryByRole("link", { name: /page total/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /total pages/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /pagination total/i })).not.toBeInTheDocument();
  });

  it("suspiciousCounterUsesSystemSignalLanguage", () => {
    renderSuspiciousNavigation(98);

    expect(screen.getByText("Suspicious signals")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Workspace signal total 98/i })).toBeInTheDocument();
  });

  function renderSuspiciousNavigation(total) {
    return render(
      <WorkspaceNavigation
        workspacePage="suspiciousTransactions"
        workspaceRoutes={[WORKSPACE_ROUTE_REGISTRY.suspiciousTransactions]}
        workspaceCounters={{ alerts: null, fraudCases: null, suspiciousTransactions: total, transactions: null }}
        canReadSuspiciousTransactions
      />
    );
  }
});
