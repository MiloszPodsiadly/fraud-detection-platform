import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SESSION_STATES } from "../auth/sessionState.js";
import { AlertsListPage } from "./AlertsListPage.jsx";

describe("AlertsListPage session lifecycle", () => {
  it("renders a session-required panel instead of dashboard data when unauthenticated", () => {
    render(
      <AlertsListPage
        workspacePage="fraudTransaction"
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
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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

    expect(screen.getAllByRole("heading", { name: "Session required" })).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Transaction scoring stream" })).not.toBeInTheDocument();
    expect(screen.queryByText("No alerts match this view")).not.toBeInTheDocument();
  });

  it("renders an expired session panel for provider-backed expiry states", () => {
    render(
      <AlertsListPage
        workspacePage="fraudTransaction"
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
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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

    expect(screen.getAllByRole("heading", { name: "Session expired" })).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Transaction scoring stream" })).not.toBeInTheDocument();
  });

  it("keeps the session gate closed after an unauthorized retry error", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
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
        error={{ status: 401 }}
        fraudCaseWorkQueueError={null}
        governanceError={null}
        analyticsError={null}
        sessionState={{ status: SESSION_STATES.UNAUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Session required" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Fraud Case Work Queue" })).not.toBeInTheDocument();
  });

  it("keeps fraud detection focused on alert review and moves transactions to a dedicated workspace", () => {
    const { rerender } = render(
      <AlertsListPage
        workspacePage="fraudTransaction"
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
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Alert review queue" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Transaction scoring stream" })).not.toBeInTheDocument();

    rerender(
      <AlertsListPage
        workspacePage="transactionScoring"
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
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Transaction scoring stream" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
  });

  it("shows the backend global fraud case total instead of the loaded work queue slice size", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        alertPage={emptyPage(10)}
        fraudCaseTotalElements={46}
        fraudCaseWorkQueue={{ ...emptyWorkQueue(), content: [{ caseId: "case-1" }] }}
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
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByRole("link", { name: /All fraud cases\s*46/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /All fraud cases\s*1/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/total queue cases/i)).not.toBeInTheDocument();
  });

  it("shows summary failure locally without hiding the loaded work queue", () => {
    render(
      <AlertsListPage
        workspacePage="analyst"
        alertPage={emptyPage(10)}
        fraudCaseTotalElements={46}
        fraudCaseSummaryError={{ status: 503, message: "summary unavailable" }}
        fraudCaseWorkQueue={{ ...emptyWorkQueue(), content: [workQueueItem("CASE-1")] }}
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
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        onRetry={vi.fn()}
        onFraudCaseSummaryRetry={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onAnalyticsRetry={vi.fn()}
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByRole("link", { name: /All fraud cases\s*Unavailable/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Global fraud case count unavailable." })).toBeInTheDocument();
    expect(screen.getAllByText("CASE-1").length).toBeGreaterThan(0);
  });

  it("sends transaction monitor filters to request state instead of filtering only the current page", () => {
    const onTransactionFiltersChange = vi.fn();
    render(
      <AlertsListPage
        workspacePage="transactionScoring"
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
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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

    fireEvent.change(screen.getByLabelText("Risk", { selector: "select" }), { target: { value: "CRITICAL" } });

    expect(onTransactionFiltersChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

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
        workspacePage="transactionScoring"
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
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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

    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "ab" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

    expect(screen.getByText("Use at least 3 characters or clear search.")).toBeInTheDocument();
    expect(onTransactionFiltersChange).not.toHaveBeenCalled();
  });

  it("blocks too-long transaction monitor searches before request state changes", () => {
    const onTransactionFiltersChange = vi.fn();
    render(
      <AlertsListPage
        workspacePage="transactionScoring"
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
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
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

    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "x".repeat(129) } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

    expect(screen.getByText("Search query must be 128 characters or less.")).toBeInTheDocument();
    expect(onTransactionFiltersChange).not.toHaveBeenCalled();
  });

  it("does not present filtered capped transaction totals as exact global totals", () => {
    render(
      <AlertsListPage
        workspacePage="transactionScoring"
        alertPage={emptyPage(10)}
        fraudCaseWorkQueue={emptyWorkQueue()}
        fraudCaseWorkQueueRequest={emptyWorkQueueRequest()}
        transactionPage={{
          content: [scoredTransaction("txn-1", "LOW", false)],
          totalElements: 10000,
          totalPages: 400,
          page: 0,
          size: 25
        }}
        transactionPageRequest={{ page: 0, size: 25, query: "customer-123", riskLevel: "ALL", status: "ALL" }}
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
        onFraudCaseWorkQueueDraftChange={vi.fn()}
        onFraudCaseWorkQueueApplyFilters={vi.fn()}
        onFraudCaseWorkQueueResetFilters={vi.fn()}
        onFraudCaseWorkQueueRetry={vi.fn()}
        onFraudCaseWorkQueueRefreshFirstSlice={vi.fn()}
        onFraudCaseWorkQueueLoadMore={vi.fn()}
        onAdvisoryQueueRequestChange={vi.fn()}
        onAnalyticsWindowDaysChange={vi.fn()}
        onRecordGovernanceAudit={vi.fn()}
        onTransactionFiltersChange={vi.fn()}
        onTransactionPageChange={vi.fn()}
        onTransactionPageSizeChange={vi.fn()}
        onAlertPageChange={vi.fn()}
        onAlertPageSizeChange={vi.fn()}
        onOpenAlert={vi.fn()}
        onOpenFraudCase={vi.fn()}
      />
    );

    expect(screen.getByText("capped filtered scored transactions")).toBeInTheDocument();
    expect(screen.queryByText("total scored transactions")).not.toBeInTheDocument();
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

function workQueueItem(caseNumber) {
  return {
    caseId: caseNumber,
    caseNumber,
    status: "OPEN",
    priority: "HIGH",
    riskLevel: "CRITICAL",
    assignedInvestigatorId: "",
    createdAt: "2026-05-11T10:00:00Z",
    updatedAt: "2026-05-11T11:00:00Z",
    caseAgeSeconds: 7200,
    lastUpdatedAgeSeconds: 600,
    slaStatus: "NEAR_BREACH",
    slaDeadlineAt: "2026-05-12T10:00:00Z",
    linkedAlertCount: 1
  };
}
