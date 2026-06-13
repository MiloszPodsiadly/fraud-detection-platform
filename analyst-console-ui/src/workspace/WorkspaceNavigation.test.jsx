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

    expect(screen.getByText("Shadow diagnostics")).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Transactions\s*44/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Alerts\s*41/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Workspace signal total 43/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Global fraud cases\s*42/ })).toBeInTheDocument();
  });
});
