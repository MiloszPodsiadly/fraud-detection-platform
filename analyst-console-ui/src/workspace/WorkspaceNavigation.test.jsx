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

  it("summaryCounterUnavailableUsesSystemSignalCopy", () => {
    render(
      <WorkspaceNavigation
        workspacePage="suspiciousTransactions"
        workspaceRoutes={[WORKSPACE_ROUTE_REGISTRY.suspiciousTransactions]}
        workspaceCounters={{ alerts: null, fraudCases: null, suspiciousTransactions: null, transactions: null }}
        workspaceCountersStatus={{
          degraded: true,
          failedCounters: ["suspiciousTransactions"],
          errorByCounter: { suspiciousTransactions: "Summary temporarily unavailable" },
          stale: false
        }}
        canReadSuspiciousTransactions
      />
    );

    expect(screen.getByRole("link", { name: /Signal total unavailable/i })).toBeInTheDocument();
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText("Signal total unavailable")).toBeInTheDocument();
    expect(screen.queryByText(/raw backend/i)).not.toBeInTheDocument();
  });

  it("summaryCounterFailureDoesNotRenderRawError", () => {
    render(
      <WorkspaceNavigation
        workspacePage="suspiciousTransactions"
        workspaceRoutes={[WORKSPACE_ROUTE_REGISTRY.suspiciousTransactions]}
        workspaceCounters={{ alerts: null, fraudCases: null, suspiciousTransactions: null, transactions: null }}
        workspaceCountersStatus={{
          degraded: true,
          failedCounters: ["suspiciousTransactions"],
          errorByCounter: { suspiciousTransactions: "MongoTimeoutException customer-1 cursor-secret" },
          stale: false
        }}
        canReadSuspiciousTransactions
      />
    );

    expect(screen.getByRole("link", { name: /Signal total unavailable/i })).toBeInTheDocument();
    expect(screen.queryByText(/MongoTimeoutException/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/cursor-secret/i)).not.toBeInTheDocument();
  });

  it("does not turn unknown counter authority into zero or retain a supplied count", () => {
    render(
      <WorkspaceNavigation
        workspacePage="transactionScoring"
        workspaceRoutes={[
          WORKSPACE_ROUTE_REGISTRY.transactionScoring,
          WORKSPACE_ROUTE_REGISTRY.fraudTransaction,
          WORKSPACE_ROUTE_REGISTRY.suspiciousTransactions,
          WORKSPACE_ROUTE_REGISTRY.analyst
        ]}
        workspaceCounters={{ alerts: 9101, fraudCases: 9202, suspiciousTransactions: 9303, transactions: 9404 }}
        alertPage={{ totalElements: 9101 }}
        transactionPage={{ totalElements: 9404 }}
        fraudCaseSummary={{ totalFraudCases: 9202 }}
        fraudCaseTotalElements={9202}
      />
    );

    const navigation = screen.getByRole("navigation", { name: "Analyst workspace sections" });
    expect(navigation).toHaveTextContent("Unavailable");
    for (const ownerCount of ["9101", "9202", "9303", "9404"]) {
      expect(navigation).not.toHaveTextContent(ownerCount);
    }
    expect(navigation).not.toHaveTextContent(/\b0\b/);
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

describe("WorkspaceNavigation shadow performance diagnostic boundary", () => {
  it("shadowPerformanceRouteRendersGlobalPlatformCounters", () => {
    render(
      <WorkspaceNavigation
        workspacePage="shadowPerformance"
        workspaceRoutes={[
          WORKSPACE_ROUTE_REGISTRY.transactionScoring,
          WORKSPACE_ROUTE_REGISTRY.fraudTransaction,
          WORKSPACE_ROUTE_REGISTRY.suspiciousTransactions,
          WORKSPACE_ROUTE_REGISTRY.analyst,
          WORKSPACE_ROUTE_REGISTRY.shadowPerformance
        ]}
        workspaceCounters={{
          alerts: 41,
          fraudCases: 42,
          suspiciousTransactions: 43,
          transactions: 44
        }}
        fraudCaseTotalElements={42}
        canReadAlerts
        canReadFraudCases
        canReadSuspiciousTransactions
        canReadTransactions
        canReadShadowPerformance
      />
    );

    expect(screen.getByText("Model evidence")).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Transactions\s*44/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Alerts\s*41/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Workspace signal total 43/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Global fraud cases\s*42/ })).toBeInTheDocument();
  });
});
