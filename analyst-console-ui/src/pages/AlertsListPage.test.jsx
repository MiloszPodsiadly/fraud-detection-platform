import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SESSION_STATES } from "../auth/sessionState.js";
import { AlertsListPage } from "./AlertsListPage.jsx";

describe("AlertsListPage session lifecycle", () => {
  it("renders a session-required panel instead of dashboard data when unauthenticated", () => {
    render(
      <AlertsListPage
        alertPage={emptyPage(10)}
        fraudCasePage={emptyPage(4)}
        transactionPage={emptyPage(25)}
        advisoryQueue={emptyAdvisoryQueue()}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        governanceAnalytics={emptyAnalytics()}
        analyticsWindowDays={7}
        isLoading={false}
        isGovernanceLoading={false}
        isAnalyticsLoading={false}
        error={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.UNAUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onAdvisoryQueueRequestChange={vi.fn()}
        onAnalyticsWindowDaysChange={vi.fn()}
        onRecordGovernanceAudit={vi.fn()}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onFraudCasePageChange={vi.fn()}
        onFraudCasePageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getAllByRole("heading", { name: "Session required" })).toHaveLength(2);
    expect(screen.queryByText("No alerts match this view")).not.toBeInTheDocument();
  });

  it("renders an expired session panel for provider-backed expiry states", () => {
    render(
      <AlertsListPage
        alertPage={emptyPage(10)}
        fraudCasePage={emptyPage(4)}
        transactionPage={emptyPage(25)}
        advisoryQueue={emptyAdvisoryQueue()}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        governanceAnalytics={emptyAnalytics()}
        analyticsWindowDays={7}
        isLoading={false}
        isGovernanceLoading={false}
        isAnalyticsLoading={false}
        error={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.EXPIRED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onAdvisoryQueueRequestChange={vi.fn()}
        onAnalyticsWindowDaysChange={vi.fn()}
        onRecordGovernanceAudit={vi.fn()}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onFraudCasePageChange={vi.fn()}
        onFraudCasePageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getAllByRole("heading", { name: "Session expired" })).toHaveLength(2);
  });
});

function emptyPage(size) {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size
  };
}

function emptyAdvisoryQueue() {
  return {
    status: "AVAILABLE",
    count: 0,
    retention_limit: 200,
    advisory_events: []
  };
}

function emptyAnalytics() {
  return {
    status: "AVAILABLE",
    window: { from: null, to: null, days: 7 },
    totals: { advisories: 0, reviewed: 0, open: 0 },
    decision_distribution: {},
    lifecycle_distribution: {},
    review_timeliness: {}
  };
}
