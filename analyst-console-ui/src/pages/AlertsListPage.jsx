import { useDeferredValue, useEffect, useMemo, useState } from "react";
import { AlertTable } from "../components/AlertTable.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { FilterBar } from "../components/FilterBar.jsx";
import { FraudCaseWorkQueuePanel } from "../components/FraudCaseWorkQueuePanel.jsx";
import { GovernanceAnalyticsPanel } from "../components/GovernanceAnalyticsPanel.jsx";
import { GovernanceReviewQueue } from "../components/GovernanceReviewQueue.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { PaginationControls } from "../components/PaginationControls.jsx";
import { SessionStatePanel } from "../components/SecurityStatePanels.jsx";
import { TransactionMonitorTable } from "../components/TransactionMonitorTable.jsx";
import { SESSION_STATES } from "../auth/sessionState.js";

export function AlertsListPage({
  workspacePage = "analyst",
  workspaceCounters = { alerts: 0, transactions: 0 },
  alertPage,
  fraudCaseSummary = { totalFraudCases: 0 },
  fraudCaseTotalElements,
  fraudCaseSummaryError,
  isFraudCaseSummaryLoading = false,
  fraudCaseWorkQueue,
  fraudCaseWorkQueueRequest,
  fraudCaseWorkQueueDraftFilters,
  fraudCaseWorkQueueWarning,
  fraudCaseWorkQueueFilterError,
  fraudCaseWorkQueueLastRefreshedAt,
  onWorkspaceChange,
  transactionPage,
  transactionPageRequest = { query: "", riskLevel: "ALL", status: "ALL" },
  advisoryQueue,
  advisoryQueueRequest,
  governanceAnalytics,
  analyticsWindowDays,
  isLoading,
  isFraudCaseWorkQueueLoading,
  isGovernanceLoading,
  isAnalyticsLoading,
  error,
  fraudCaseWorkQueueError,
  governanceError,
  analyticsError,
  governanceAuditHistories,
  session,
  sessionState,
  onRetry,
  onFraudCaseSummaryRetry,
  onGovernanceRetry,
  onAnalyticsRetry,
  onAdvisoryQueueRequestChange,
  onFraudCaseWorkQueueDraftChange,
  onFraudCaseWorkQueueApplyFilters,
  onFraudCaseWorkQueueResetFilters,
  onFraudCaseWorkQueueRetry,
  onFraudCaseWorkQueueRefreshFirstSlice,
  onFraudCaseWorkQueueLoadMore,
  onAnalyticsWindowDaysChange,
  onRecordGovernanceAudit,
  onTransactionFiltersChange = () => {},
  onTransactionPageChange,
  onTransactionPageSizeChange,
  onAlertPageChange,
  onAlertPageSizeChange,
  onOpenAlert,
  onOpenFraudCase
}) {
  const [filters, setFilters] = useState({
    query: "",
    riskLevel: "ALL",
    status: "ALL"
  });
  const deferredQuery = useDeferredValue(filters.query);

  const filteredAlerts = useMemo(() => {
    const query = deferredQuery.trim().toLowerCase();
    return (alertPage.content || []).filter((alert) => {
      const matchesRisk = filters.riskLevel === "ALL" || alert.riskLevel === filters.riskLevel;
      const matchesStatus = filters.status === "ALL" || alert.alertStatus === filters.status;
      const searchable = [
        alert.alertId,
        alert.transactionId,
        alert.customerId,
        alert.alertReason
      ].join(" ").toLowerCase();
      return matchesRisk && matchesStatus && (!query || searchable.includes(query));
    });
  }, [alertPage.content, deferredQuery, filters.riskLevel, filters.status]);

  const transactions = transactionPage.content || [];

  const sessionBlocksDashboard = shouldBlockDashboard(sessionState, error);
  const workQueueError = sessionBlocksDashboard ? workQueueErrorForSession(sessionState) : fraudCaseWorkQueueError;
  const showAnalyst = workspacePage === "analyst";
  const transactionGlobalCount = workspaceCounters.transactions ?? transactionPage.totalElements ?? 0;
  const alertGlobalCount = workspaceCounters.alerts ?? alertPage.totalElements ?? 0;
  const fraudCaseGlobalCount = fraudCaseSummary?.totalFraudCases ?? fraudCaseTotalElements ?? 0;
  const fraudCaseSummaryLabel = fraudCaseSummaryError
    ? "Unavailable"
    : String(fraudCaseGlobalCount);
  const fraudCaseSummaryGeneratedAt = fraudCaseSummary?.generatedAt
    ? new Date(fraudCaseSummary.generatedAt).toLocaleString()
    : null;
  const fraudCaseSummaryHint = [
    "Global point-in-time fraud case count.",
    "It is not filter-scoped, cursor-scoped, page-scoped, or pagination metadata.",
    fraudCaseSummary?.snapshotConsistentWithWorkQueue === false
      ? "It is not snapshot-consistent with the loaded work queue slice."
      : null,
    fraudCaseSummaryGeneratedAt ? `Generated at ${fraudCaseSummaryGeneratedAt}.` : null
  ].filter(Boolean).join(" ");
  const showFraudTransaction = workspacePage === "fraudTransaction";
  const showTransactionScoring = workspacePage === "transactionScoring";
  const showCompliance = workspacePage === "compliance";
  const showReports = workspacePage === "reports";

  return (
    <div className="dashboardGrid pageEnter">
      <nav className="workspaceTabs" aria-label="Analyst workspace sections">
        <a href="?workspace=transaction-scoring" onClick={(event) => openWorkspace(event, onWorkspaceChange, "transactionScoring")}>
          <span>Transactions</span>
          <strong>{transactionGlobalCount}</strong>
        </a>
        <a href="?workspace=fraud-transaction" onClick={(event) => openWorkspace(event, onWorkspaceChange, "fraudTransaction")}>
          <span>Alerts</span>
          <strong>{alertGlobalCount}</strong>
        </a>
        <a
          href="/"
          title={fraudCaseSummaryHint}
          aria-label={`Global fraud cases ${isFraudCaseSummaryLoading ? "Loading" : fraudCaseSummaryLabel}`}
          onClick={(event) => openWorkspace(event, onWorkspaceChange, "analyst")}
        >
          <span>Global fraud cases</span>
          <strong>{isFraudCaseSummaryLoading ? "..." : fraudCaseSummaryLabel}</strong>
        </a>
        <a href="?workspace=reports" onClick={(event) => openWorkspace(event, onWorkspaceChange, "reports")}>
          <span>Audit analytics</span>
          <strong>{governanceAnalytics?.totals?.advisories ?? 0}</strong>
        </a>
        <a href="?workspace=compliance" onClick={(event) => openWorkspace(event, onWorkspaceChange, "compliance")}>
          <span>Governance</span>
          <strong>{advisoryQueue.count || 0}</strong>
        </a>
      </nav>

      {sessionBlocksDashboard && <SessionStatePanel sessionState={sessionState} onRetry={onRetry} />}

      {!sessionBlocksDashboard && showAnalyst && (
        <FraudCaseWorkQueuePanel
          queue={fraudCaseWorkQueue}
          request={fraudCaseWorkQueueRequest}
          draftRequest={fraudCaseWorkQueueDraftFilters}
          isLoading={isFraudCaseWorkQueueLoading}
          error={workQueueError}
          warning={fraudCaseWorkQueueWarning}
          validationError={fraudCaseWorkQueueFilterError}
          lastRefreshedAt={fraudCaseWorkQueueLastRefreshedAt}
          onDraftChange={onFraudCaseWorkQueueDraftChange}
          onApplyFilters={onFraudCaseWorkQueueApplyFilters}
          onResetFilters={onFraudCaseWorkQueueResetFilters}
          onLoadMore={onFraudCaseWorkQueueLoadMore}
          onRetry={onFraudCaseWorkQueueRetry}
          onRefreshFirstSlice={onFraudCaseWorkQueueRefreshFirstSlice}
          onOpenCase={onOpenFraudCase}
        />
      )}

      {!sessionBlocksDashboard && showAnalyst && fraudCaseSummaryError && (
        <div className="statePanel warningPanel" role="status">
          <h3>Global fraud case count unavailable.</h3>
          <p>The work queue can still load. Retry only the global point-in-time summary.</p>
          <button className="secondaryButton" type="button" onClick={onFraudCaseSummaryRetry}>
            Retry summary
          </button>
        </div>
      )}

      {!sessionBlocksDashboard && showFraudTransaction && <section className="panel" id="alert-queue">
        <div className="panelHeader">
          <div>
            <p className="eyebrow">Queue</p>
            <h2>Alert review queue</h2>
          </div>
        </div>

        <FilterBar filters={filters} onChange={setFilters} />

        {isLoading && <LoadingPanel label="Loading fraud alerts..." />}
        {!isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
        {!isLoading && !error && filteredAlerts.length === 0 && (
          <EmptyState
            title="No alerts match this view"
            message="Adjust filters or generate synthetic high-risk traffic to populate the queue."
          />
        )}
        {!isLoading && !error && filteredAlerts.length > 0 && (
          <>
            <AlertTable alerts={filteredAlerts} onOpenAlert={onOpenAlert} />
            <PaginationControls
              label="alerts"
              page={alertPage.page}
              size={alertPage.size}
              totalPages={alertPage.totalPages}
              totalElements={alertPage.totalElements}
              onPageChange={onAlertPageChange}
              onSizeChange={onAlertPageSizeChange}
            />
          </>
        )}
      </section>}

      {!sessionBlocksDashboard && showTransactionScoring && <section className="panel" id="transaction-stream">
        <div className="panelHeader">
          <div>
            <p className="eyebrow">Monitor</p>
            <h2>Transaction scoring stream</h2>
            <p className="sectionCopy">
              Recent scored transactions include both legitimate traffic and suspicious cases.
            </p>
          </div>
          <button className="secondaryButton" type="button" onClick={onRetry}>
            Refresh
          </button>
        </div>

        <TransactionMonitorFilters
          request={transactionPageRequest}
          onApply={onTransactionFiltersChange}
        />

        {isLoading && <LoadingPanel label="Loading scored transactions..." />}
        {!isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
        {!isLoading && !error && transactions.length === 0 && (
          <EmptyState
            title="No scored transactions match this view"
            message="Adjust filters or generate synthetic traffic to populate transaction monitoring."
          />
        )}
        {!isLoading && !error && transactions.length > 0 && (
          <>
            <TransactionMonitorTable transactions={transactions} />
            <PaginationControls
              page={transactionPage.page}
              size={transactionPage.size}
              totalPages={transactionPage.totalPages}
              totalElements={transactionPage.totalElements}
              label={hasAppliedTransactionFilters(transactionPageRequest) ? "filtered scored transactions" : "scored transactions"}
              totalLabel={hasAppliedTransactionFilters(transactionPageRequest) ? "capped" : "total"}
              onPageChange={onTransactionPageChange}
              onSizeChange={onTransactionPageSizeChange}
            />
          </>
        )}
      </section>}

      {!sessionBlocksDashboard && showReports && (
        <GovernanceAnalyticsPanel
          analytics={governanceAnalytics}
          windowDays={analyticsWindowDays}
          isLoading={isAnalyticsLoading}
          error={analyticsError}
          onWindowDaysChange={onAnalyticsWindowDaysChange}
          onRetry={onAnalyticsRetry}
        />
      )}

      {!sessionBlocksDashboard && showCompliance && (
        <GovernanceReviewQueue
          advisoryQueue={advisoryQueue}
          filters={advisoryQueueRequest}
          isLoading={isGovernanceLoading}
          error={governanceError}
          auditHistories={governanceAuditHistories}
          session={session}
          onFiltersChange={onAdvisoryQueueRequestChange}
          onRetry={onGovernanceRetry}
          onRecordAudit={onRecordGovernanceAudit}
        />
      )}
    </div>
  );
}

function openWorkspace(event, onWorkspaceChange, page) {
  if (!onWorkspaceChange) {
    return;
  }
  event.preventDefault();
  onWorkspaceChange(page);
}

function TransactionMonitorFilters({ request, onApply }) {
  const [draft, setDraft] = useState(() => editableTransactionRequest(request));
  const [validationError, setValidationError] = useState(null);
  const draftChanged = !sameTransactionRequest(draft, editableTransactionRequest(request));

  useEffect(() => {
    setDraft(editableTransactionRequest(request));
    setValidationError(null);
  }, [request]);

  function updateField(field, value) {
    setDraft((current) => ({ ...current, [field]: value }));
    setValidationError(null);
  }

  function applyFilters() {
    const query = draft.query.trim();
    if (query && query.length < 3) {
      setValidationError("Use at least 3 characters or clear search.");
      return;
    }
    if (query.length > 128) {
      setValidationError("Search query must be 128 characters or less.");
      return;
    }
    onApply({
      ...request,
      ...draft,
      query,
      page: 0
    });
  }

  function resetFilters() {
    const nextRequest = {
      query: "",
      riskLevel: "ALL",
      status: "ALL"
    };
    setDraft(nextRequest);
    setValidationError(null);
    onApply({
      ...request,
      ...nextRequest,
      page: 0
    });
  }

  return (
    <>
      <div className="filterBar">
        <label>
          Search
          <input
            value={draft.query}
            onChange={(event) => updateField("query", event.target.value)}
            placeholder="Transaction, customer, merchant, currency"
            maxLength={128}
          />
        </label>
        <label>
          Risk
          <select value={draft.riskLevel} onChange={(event) => updateField("riskLevel", event.target.value)}>
            {["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"].map((riskLevel) => (
              <option key={riskLevel} value={riskLevel}>{riskLevel}</option>
            ))}
          </select>
        </label>
        <label>
          Classification
          <select value={draft.status} onChange={(event) => updateField("status", event.target.value)}>
            {["ALL", "LEGITIMATE", "SUSPICIOUS"].map((status) => (
              <option key={status} value={status}>{status}</option>
            ))}
          </select>
        </label>
      </div>
      {validationError && <p className="formError">{validationError}</p>}
      <div className="workQueueToolbar transactionFilterToolbar" aria-label="Transaction scoring stream filter actions">
        <div className="workQueueChips">
          <span className="tag">Backend-filtered stream</span>
          {request.query ? <span className="tag">Search filter set</span> : <span className="tag">No search query</span>}
          {request.riskLevel && request.riskLevel !== "ALL" && <span className="tag">Risk: {request.riskLevel}</span>}
          {request.status && request.status !== "ALL" && <span className="tag">Classification: {request.status}</span>}
        </div>
        <div className="workQueueToolbarActions">
          <button className="secondaryButton compactButton" type="button" onClick={applyFilters} disabled={!draftChanged}>
            Apply filters
          </button>
          <button className="secondaryButton compactButton" type="button" onClick={resetFilters} disabled={!draftChanged && !hasAppliedTransactionFilters(request)}>
            Reset filters
          </button>
        </div>
      </div>
    </>
  );
}

function editableTransactionRequest(request = {}) {
  return {
    query: request.query || "",
    riskLevel: request.riskLevel || "ALL",
    status: request.status || request.classification || "ALL"
  };
}

function sameTransactionRequest(left, right) {
  return JSON.stringify(editableTransactionRequest(left)) === JSON.stringify(editableTransactionRequest(right));
}

function hasAppliedTransactionFilters(request) {
  const editable = editableTransactionRequest(request);
  return Boolean(editable.query.trim()) || editable.riskLevel !== "ALL" || editable.status !== "ALL";
}

function workQueueErrorForSession(sessionState) {
  if (sessionState?.status === SESSION_STATES.ACCESS_DENIED) {
    return { status: 403 };
  }
  return { status: 401 };
}

function shouldBlockDashboard(sessionState, error) {
  const blockedSessionStates = [
    SESSION_STATES.UNAUTHENTICATED,
    SESSION_STATES.EXPIRED,
    SESSION_STATES.ACCESS_DENIED,
    SESSION_STATES.AUTH_ERROR
  ];

  return blockedSessionStates.includes(sessionState?.status) || [401, 403].includes(error?.status);
}
