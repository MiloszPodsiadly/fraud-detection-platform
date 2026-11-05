import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SESSION_STATES } from "../auth/sessionState.js";
import { AlertsListPage } from "./AlertsListPage.jsx";

describe("AlertsListPage frame", () => {
  it("renders workspace navigation and child content for authenticated sessions", () => {
    render(
      <AlertsListPage
        workspacePage="fraudTransaction"
        alertPage={page(2)}
        transactionPage={page(5)}
        advisoryQueue={{ count: 1 }}
        governanceAnalytics={{ totals: { advisories: 3 } }}
        fraudCaseSummary={{ totalFraudCases: 8 }}
        canReadAlerts={true}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onWorkspaceChange={vi.fn()}
      >
        <section><h2>Alert review queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByRole("navigation", { name: "Analyst workspace sections" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Alerts\s*2/ })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { name: "Alert review queue" })).toBeInTheDocument();
  });

  it("renders a session-required panel instead of child workspace content when unauthenticated", () => {
    render(
      <AlertsListPage
        workspacePage="fraudTransaction"
        alertPage={page(2)}
        transactionPage={page(5)}
        advisoryQueue={{ count: 1 }}
        governanceAnalytics={{ totals: { advisories: 3 } }}
        sessionState={{ status: SESSION_STATES.UNAUTHENTICATED }}
        onRetry={vi.fn()}
      >
        <section><h2>Alert review queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByRole("heading", { name: "Session required" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
  });

  it("labels degraded counter timestamp as last successful refresh", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        workspaceCounters={{ alerts: 7, transactions: null }}
        workspaceCountersStatus={{
          degraded: true,
          stale: true,
          errorByCounter: { transactions: "transactions unavailable" },
          lastRefreshedAt: "2026-05-14T10:00:00Z",
          refresh: vi.fn()
        }}
        alertPage={page(7)}
        transactionPage={page(0)}
        advisoryQueue={{ count: 0 }}
        governanceAnalytics={{ totals: { advisories: 0 } }}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
      >
        <section><h2>Fraud Case Work Queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByText("Some workspace counters are temporarily unavailable.")).toBeInTheDocument();
    expect(screen.getByText(/Last successful refresh/)).toBeInTheDocument();
    expect(screen.queryByText(/Last refreshed/)).not.toBeInTheDocument();
  });

  it("keeps unknown-capability counters unavailable even when stale counts are supplied", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        workspaceCounters={{ alerts: 12, fraudCases: 13, suspiciousTransactions: 14, transactions: 15 }}
        workspaceCountersStatus={{ failedCounters: [], errorByCounter: {}, stale: false }}
        alertPage={page(16)}
        transactionPage={page(17)}
        fraudCaseSummary={{ totalFraudCases: 18 }}
        fraudCaseTotalElements={19}
        canReadAlerts={undefined}
        canReadTransactions={undefined}
        canReadFraudCases={undefined}
        canReadSuspiciousTransactions={undefined}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
      >
        <section><h2>Fraud Case Work Queue</h2></section>
      </AlertsListPage>
    );

    const navigation = screen.getByRole("navigation", { name: "Analyst workspace sections" });
    expect(screen.getByRole("link", { name: "Transactions Unavailable" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Alerts Unavailable" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Global fraud cases Unavailable" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Signal total unavailable" })).toHaveTextContent("Unavailable");
    for (const previousCount of [12, 13, 14, 15, 16, 17, 18, 19]) {
      expect(navigation).not.toHaveTextContent(String(previousCount));
    }
  });

  it("shows confirmed zero counts only when each read authority and zero response are explicit", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        workspaceCounters={{ alerts: 0, fraudCases: 0, suspiciousTransactions: 0, transactions: 0 }}
        workspaceCountersStatus={{ degraded: false, failedCounters: [], errorByCounter: {}, stale: false }}
        alertPage={page(0)}
        transactionPage={page(0)}
        fraudCaseSummary={{ totalFraudCases: 0 }}
        fraudCaseTotalElements={0}
        advisoryQueue={{ count: 0 }}
        governanceAnalytics={{ totals: { advisories: 0 } }}
        canReadTransactions={true}
        canReadAlerts={true}
        canReadFraudCases={true}
        canReadSuspiciousTransactions={true}
        canReadGovernanceAdvisories={true}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
      >
        <section><h2>Fraud Case Work Queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByRole("link", { name: "Transactions 0" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Alerts 0" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Global fraud cases 0" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Workspace signal total 0" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Audit analytics\s*0/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Governance\s*0/ })).toBeInTheDocument();
  });

  it("distinguishes missing authority from stale or unavailable counters", () => {
    render(
      <AlertsListPage
        workspacePage="fraudTransaction"
        workspaceCounters={{ alerts: 7, transactions: null }}
        workspaceCountersStatus={{ failedCounters: [], errorByCounter: {}, stale: false }}
        canReadAlerts={false}
        canReadTransactions
        fraudCaseSummary={{ totalFraudCases: 4 }}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
      >
        <section><h2>Alert review queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByRole("link", { name: /Alerts\s*No access\s*Access unavailable/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Alerts\s*7/ })).not.toBeInTheDocument();
  });

  it("surfaces invalid workspace route normalization", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        routeFallbackNotice={'Unknown workspace route "bad"; showing Fraud Case workspace.'}
        fraudCaseSummary={{ totalFraudCases: 4 }}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
      >
        <section><h2>Fraud Case Work Queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByText("Workspace route normalized.")).toBeInTheDocument();
    expect(screen.getByText('Unknown workspace route "bad"; showing Fraud Case workspace.')).toBeInTheDocument();
  });

  it("renders controlled refresh notices without entity identifiers", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        refreshNotice={{
          title: "Refresh could not start.",
          message: "Refresh could not be started. Try again."
        }}
        fraudCaseSummary={{ totalFraudCases: 4 }}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
      >
        <section><h2>Fraud Case Work Queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByText("Refresh could not start.")).toBeInTheDocument();
    expect(screen.getByText("Refresh could not be started. Try again.")).toBeInTheDocument();
    expect(screen.queryByText(/alert-/)).not.toBeInTheDocument();
  });
});

function page(totalElements) {
  return {
    content: [],
    totalElements,
    totalPages: 0,
    page: 0,
    size: 10
  };
}
