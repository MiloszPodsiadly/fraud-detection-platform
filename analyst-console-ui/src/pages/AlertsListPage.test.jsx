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

  it("does not render not-mounted reports or compliance data as business zero", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        workspaceCounters={{ alerts: null, transactions: null }}
        workspaceCountersStatus={{ failedCounters: [], errorByCounter: {}, stale: false }}
        fraudCaseSummary={{ totalFraudCases: 4 }}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
      >
        <section><h2>Fraud Case Work Queue</h2></section>
      </AlertsListPage>
    );

    expect(screen.getByRole("link", { name: /Audit analytics\s*Unavailable/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Governance\s*Unavailable/ })).toBeInTheDocument();
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
