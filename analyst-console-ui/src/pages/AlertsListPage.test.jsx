import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SESSION_STATES } from "../auth/sessionState.js";
import { AlertsListPage } from "./AlertsListPage.jsx";

describe("AlertsListPage session lifecycle", () => {
  it("renders a session-required panel instead of dashboard data when unauthenticated", () => {
    render(
      <AlertsListPage
        alertPage={emptyPage(10)}
        fraudCaseWorkQueue={emptyWorkQueue()}
        fraudCaseWorkQueueRequest={emptyWorkQueueRequest()}
        transactionPage={emptyPage(25)}
        advisoryQueue={emptyAdvisoryQueue()}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        governanceAnalytics={emptyAnalytics()}
        analyticsWindowDays={7}
        isLoading={false}
        isFraudCaseWorkQueueLoading={false}
        isGovernanceLoading={false}
        isAnalyticsLoading={false}
        error={null}
        fraudCaseWorkQueueError={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.UNAUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueRequestChange={vi.fn()}
        onFraudCaseWorkQueueRetry={vi.fn()}
        onFraudCaseWorkQueueRefreshFirstSlice={vi.fn()}
        onFraudCaseWorkQueueLoadMore={vi.fn()}
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
        fraudCaseWorkQueue={emptyWorkQueue()}
        fraudCaseWorkQueueRequest={emptyWorkQueueRequest()}
        transactionPage={emptyPage(25)}
        advisoryQueue={emptyAdvisoryQueue()}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        governanceAnalytics={emptyAnalytics()}
        analyticsWindowDays={7}
        isLoading={false}
        isFraudCaseWorkQueueLoading={false}
        isGovernanceLoading={false}
        isAnalyticsLoading={false}
        error={null}
        fraudCaseWorkQueueError={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.EXPIRED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueRequestChange={vi.fn()}
        onFraudCaseWorkQueueRetry={vi.fn()}
        onFraudCaseWorkQueueRefreshFirstSlice={vi.fn()}
        onFraudCaseWorkQueueLoadMore={vi.fn()}
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

  it("sends transaction monitor filters to request state instead of filtering only the current page", () => {
    const onTransactionFiltersChange = vi.fn();
    render(
      <AlertsListPage
        alertPage={emptyPage(10)}
        fraudCaseWorkQueue={emptyWorkQueue()}
        fraudCaseWorkQueueRequest={emptyWorkQueueRequest()}
        transactionPage={{
          content: [scoredTransaction("txn-1", "LOW", false)],
          totalElements: 73,
          totalPages: 8,
          page: 0,
          size: 10
        }}
        transactionPageRequest={{ page: 0, size: 10, query: "", riskLevel: "ALL", status: "ALL" }}
        advisoryQueue={emptyAdvisoryQueue()}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        governanceAnalytics={emptyAnalytics()}
        analyticsWindowDays={7}
        isLoading={false}
        isFraudCaseWorkQueueLoading={false}
        isGovernanceLoading={false}
        isAnalyticsLoading={false}
        error={null}
        fraudCaseWorkQueueError={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueRequestChange={vi.fn()}
        onFraudCaseWorkQueueRetry={vi.fn()}
        onFraudCaseWorkQueueRefreshFirstSlice={vi.fn()}
        onFraudCaseWorkQueueLoadMore={vi.fn()}
        onAdvisoryQueueRequestChange={vi.fn()}
        onAnalyticsWindowDaysChange={vi.fn()}
        onRecordGovernanceAudit={vi.fn()}
        onTransactionFiltersChange={onTransactionFiltersChange}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    fireEvent.change(screen.getAllByLabelText("Risk", { selector: "select" })[2], { target: { value: "CRITICAL" } });

    expect(onTransactionFiltersChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getAllByRole("button", { name: "Apply filters" })[1]);

    expect(onTransactionFiltersChange).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      query: "",
      riskLevel: "CRITICAL",
      status: "ALL"
    });
    expect(screen.getByText("txn-1")).toBeInTheDocument();
  });

  it("blocks too-short transaction monitor searches before request state changes", () => {
    const onTransactionFiltersChange = vi.fn();
    render(
      <AlertsListPage
        alertPage={emptyPage(10)}
        fraudCaseWorkQueue={emptyWorkQueue()}
        fraudCaseWorkQueueRequest={emptyWorkQueueRequest()}
        transactionPage={emptyPage(25)}
        transactionPageRequest={{ page: 0, size: 10, query: "", riskLevel: "ALL", status: "ALL" }}
        advisoryQueue={emptyAdvisoryQueue()}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        governanceAnalytics={emptyAnalytics()}
        analyticsWindowDays={7}
        isLoading={false}
        isFraudCaseWorkQueueLoading={false}
        isGovernanceLoading={false}
        isAnalyticsLoading={false}
        error={null}
        fraudCaseWorkQueueError={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueRequestChange={vi.fn()}
        onFraudCaseWorkQueueRetry={vi.fn()}
        onFraudCaseWorkQueueRefreshFirstSlice={vi.fn()}
        onFraudCaseWorkQueueLoadMore={vi.fn()}
        onAdvisoryQueueRequestChange={vi.fn()}
        onAnalyticsWindowDaysChange={vi.fn()}
        onRecordGovernanceAudit={vi.fn()}
        onTransactionFiltersChange={onTransactionFiltersChange}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    fireEvent.change(screen.getAllByLabelText("Search")[1], { target: { value: "ab" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Apply filters" })[1]);

    expect(screen.getByText("Use at least 3 characters or clear search.")).toBeInTheDocument();
    expect(onTransactionFiltersChange).not.toHaveBeenCalled();
  });

  it("blocks too-long transaction monitor searches before request state changes", () => {
    const onTransactionFiltersChange = vi.fn();
    render(
      <AlertsListPage
        alertPage={emptyPage(10)}
        fraudCaseWorkQueue={emptyWorkQueue()}
        fraudCaseWorkQueueRequest={emptyWorkQueueRequest()}
        transactionPage={emptyPage(25)}
        transactionPageRequest={{ page: 0, size: 10, query: "", riskLevel: "ALL", status: "ALL" }}
        advisoryQueue={emptyAdvisoryQueue()}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        governanceAnalytics={emptyAnalytics()}
        analyticsWindowDays={7}
        isLoading={false}
        isFraudCaseWorkQueueLoading={false}
        isGovernanceLoading={false}
        isAnalyticsLoading={false}
        error={null}
        fraudCaseWorkQueueError={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueRequestChange={vi.fn()}
        onFraudCaseWorkQueueRetry={vi.fn()}
        onFraudCaseWorkQueueRefreshFirstSlice={vi.fn()}
        onFraudCaseWorkQueueLoadMore={vi.fn()}
        onAdvisoryQueueRequestChange={vi.fn()}
        onAnalyticsWindowDaysChange={vi.fn()}
        onRecordGovernanceAudit={vi.fn()}
        onTransactionFiltersChange={onTransactionFiltersChange}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    fireEvent.change(screen.getAllByLabelText("Search")[1], { target: { value: "x".repeat(129) } });
    fireEvent.click(screen.getAllByRole("button", { name: "Apply filters" })[1]);

    expect(screen.getByText("Search query must be 128 characters or less.")).toBeInTheDocument();
    expect(onTransactionFiltersChange).not.toHaveBeenCalled();
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

function emptyWorkQueue() {
  return {
    content: [],
    size: 20,
    hasNext: false,
    nextCursor: null,
    sort: "createdAt,desc"
  };
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
    sort: "createdAt,desc",
    cursor: null
  };
}

function emptyAnalytics() {
  return {
    status: "AVAILABLE",
    window: { from: null, to: null, days: 7 },
    totals: { advisories: 0, reviewed: 0, open: 0 },
    decision_distribution: {},
    lifecycle_distribution: {},
    review_timeliness: { status: "LOW_CONFIDENCE" }
  };
}

function scoredTransaction(transactionId, riskLevel, alertRecommended) {
  return {
    transactionId,
    customerId: "customer-1",
    transactionAmount: { amount: 120, currency: "PLN" },
    merchantInfo: { merchantName: "Merchant" },
    fraudScore: 0.1,
    riskLevel,
    alertRecommended,
    scoredAt: "2026-05-11T10:00:00Z"
  };
}
