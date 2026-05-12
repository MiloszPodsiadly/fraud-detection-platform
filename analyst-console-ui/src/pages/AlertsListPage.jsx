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
  alertPage,
  fraudCaseWorkQueue,
  fraudCaseWorkQueueRequest,
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
  onGovernanceRetry,
  onAnalyticsRetry,
  onAdvisoryQueueRequestChange,
  onFraudCaseWorkQueueRequestChange,
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

  return (
    <div className="dashboardGrid pageEnter">
      <FraudCaseWorkQueuePanel
        queue={fraudCaseWorkQueue}
        request={fraudCaseWorkQueueRequest}
        isLoading={isFraudCaseWorkQueueLoading}
        error={workQueueError}
        onRequestChange={onFraudCaseWorkQueueRequestChange}
        onLoadMore={onFraudCaseWorkQueueLoadMore}
        onRetry={onFraudCaseWorkQueueRetry}
        onRefreshFirstSlice={onFraudCaseWorkQueueRefreshFirstSlice}
        onOpenCase={onOpenFraudCase}
      />

      <section className="panel">
        <div className="panelHeader">
          <div>
            <p className="eyebrow">Queue</p>
            <h2>Alert review queue</h2>
          </div>
        </div>

        <FilterBar filters={filters} onChange={setFilters} />

        {sessionBlocksDashboard && <SessionStatePanel sessionState={sessionState} onRetry={onRetry} />}
        {isLoading && <LoadingPanel label="Loading fraud alerts..." />}
        {!sessionBlocksDashboard && !isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
        {!sessionBlocksDashboard && !isLoading && !error && filteredAlerts.length === 0 && (
          <EmptyState
            title="No alerts match this view"
            message="Adjust filters or generate synthetic high-risk traffic to populate the queue."
          />
        )}
        {!sessionBlocksDashboard && !isLoading && !error && filteredAlerts.length > 0 && (
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
      </section>

      <section className="panel">
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

        {sessionBlocksDashboard && <SessionStatePanel sessionState={sessionState} onRetry={onRetry} />}
        {isLoading && <LoadingPanel label="Loading scored transactions..." />}
        {!sessionBlocksDashboard && !isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
        {!sessionBlocksDashboard && !isLoading && !error && transactions.length === 0 && (
          <EmptyState
            title="No scored transactions match this view"
            message="Adjust filters or generate synthetic traffic to populate transaction monitoring."
          />
        )}
        {!sessionBlocksDashboard && !isLoading && !error && transactions.length > 0 && (
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
      </section>

      <GovernanceAnalyticsPanel
        analytics={governanceAnalytics}
        windowDays={analyticsWindowDays}
        isLoading={isAnalyticsLoading}
        error={analyticsError}
        onWindowDaysChange={onAnalyticsWindowDaysChange}
        onRetry={onAnalyticsRetry}
      />

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
    </div>
  );
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
  if (error) {
    return false;
  }

  return [
    SESSION_STATES.UNAUTHENTICATED,
    SESSION_STATES.EXPIRED,
    SESSION_STATES.ACCESS_DENIED,
    SESSION_STATES.AUTH_ERROR
  ].includes(sessionState?.status);
}
