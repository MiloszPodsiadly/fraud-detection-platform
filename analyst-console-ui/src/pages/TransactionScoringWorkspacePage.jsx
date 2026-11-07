import { useEffect, useState } from "react";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { PaginationControls } from "../components/PaginationControls.jsx";
import { TransactionMonitorTable } from "../components/TransactionMonitorTable.jsx";
import { TransactionRiskIntelligencePanel } from "../components/TransactionRiskIntelligencePanel.jsx";

const INITIAL_FILTER_REQUEST = { query: "", riskLevel: "ALL", status: "ALL" };
const ALERT_RECOMMENDATION_FILTERS = [
  ["ALL", "All"],
  ["LEGITIMATE", "No review recommendation"],
  ["SUSPICIOUS", "Review recommended"]
];

export function TransactionScoringWorkspacePage({
  transactionPage,
  transactionPageRequest = INITIAL_FILTER_REQUEST,
  isLoading,
  isStale = false,
  error,
  onRetry,
  onRefreshTransactions,
  onTransactionFiltersChange = () => {},
  onTransactionPageChange,
  onTransactionPageSizeChange,
  apiClient,
  canReadTransactions,
  viewIdentity,
  workspaceHeadingProps = {}
}) {
  const canRead = canReadTransactions === true;
  const transactions = canRead && Array.isArray(transactionPage?.content) ? transactionPage.content : [];
  const [selection, setSelection] = useState({ viewIdentity, transactionId: null });
  const expandedTransactionId = canRead && selection.viewIdentity === viewIdentity
    && transactions.some((transaction) => transaction.transactionId === selection.transactionId)
    ? selection.transactionId : null;

  useEffect(() => {
    if (selection.transactionId && (!canRead || selection.viewIdentity !== viewIdentity
      || !transactions.some((transaction) => transaction.transactionId === selection.transactionId))) {
      setSelection({ viewIdentity, transactionId: null });
    }
  }, [canRead, selection, transactions, viewIdentity]);

  return (
    <section className="panel" id="transaction-stream" aria-labelledby="transaction-stream-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Monitor</p>
          <h2 id="transaction-stream-title" tabIndex="-1" {...workspaceHeadingProps}>Transaction scoring stream</h2>
          <p className="sectionCopy">
            Recent scored transactions include both legitimate traffic and suspicious cases.
          </p>
        </div>
        <button className="secondaryButton" type="button" onClick={canRead ? onRetry : undefined} disabled={!canRead || isLoading}>
          Refresh
        </button>
      </div>

      {!canRead && (
        <EmptyState title="Transaction reads unavailable" message="Transaction read access is not available in this runtime." />
      )}
      {canRead && (
        <>
          <TransactionMonitorFilters
            request={transactionPageRequest}
            viewIdentity={viewIdentity}
            onApply={onTransactionFiltersChange}
          />
          {isLoading && <LoadingPanel label={isStale ? "Refreshing scored transactions; showing the last loaded results." : "Loading scored transactions..."} />}
          {!isLoading && error && transactions.length === 0 && <ErrorState error={error} onRetry={onRetry} />}
          {!isLoading && error && transactions.length > 0 && (
            <div className="formError" role="alert">
              <p>Refresh failed. Previously loaded results are shown and may be out of date.</p>
              <button className="secondaryButton compactButton" type="button" onClick={onRetry}>Retry refresh</button>
            </div>
          )}
          {!isLoading && !error && transactions.length === 0 && (
            <EmptyState
              title="No scored transactions match this view"
              message="Adjust filters or generate synthetic traffic to populate transaction monitoring."
            />
          )}
          {transactions.length > 0 && (
            <TransactionMonitorTable
              transactions={transactions}
              expandedTransactionId={expandedTransactionId}
              onToggleTransaction={(transactionId) => setSelection({ viewIdentity, transactionId })}
              renderTransactionDetail={(transaction, detailPanelId) => (
                <TransactionRiskIntelligencePanel
                  transactionId={transaction.transactionId}
                  scoringOccurrenceToken={transaction.scoringOccurrenceToken}
                  apiClient={apiClient}
                  onRefreshTransactions={onRefreshTransactions}
                  enabled={canReadTransactions === true}
                  panelId={detailPanelId}
                />
              )}
            />
          )}
          {!isLoading && !error && transactionPage.page < transactionPage.totalPages
            && (transactionPage.totalPages > 1 || transactionPage.totalElements > 0) && (
              <PaginationControls
                page={transactionPage.page}
                size={transactionPage.size}
                totalPages={transactionPage.totalPages}
                totalElements={transactionPage.totalElements}
                totalElementsCapped={transactionPage.totalElementsCapped === true}
                label={transactionPage.totalElementsCapped === true
                  ? "filtered matches"
                  : hasAppliedTransactionFilters(transactionPageRequest) ? "filtered scored transactions" : "scored transactions"}
                totalLabel="total"
                onPageChange={onTransactionPageChange}
                onSizeChange={onTransactionPageSizeChange}
              />
          )}
        </>
      )}
    </section>
  );
}

function TransactionMonitorFilters({ request, viewIdentity, onApply }) {
  const [draftState, setDraftState] = useState(() => ({
    request, viewIdentity, draft: editableTransactionRequest(request), error: null
  }));
  const ownsDraft = draftState.request === request && draftState.viewIdentity === viewIdentity;
  const draft = ownsDraft ? draftState.draft : editableTransactionRequest(request);
  const validationError = ownsDraft ? draftState.error : null;
  const draftChanged = !sameTransactionRequest(draft, editableTransactionRequest(request));

  function updateField(field, value) {
    setDraftState({ request, viewIdentity, draft: { ...draft, [field]: value }, error: null });
  }

  function applyFilters() {
    const query = draft.query.trim();
    if (query && query.length < 3) {
      setDraftState({ request, viewIdentity, draft, error: "Use at least 3 characters or clear search." });
      return;
    }
    if (query.length > 128) {
      setDraftState({ request, viewIdentity, draft, error: "Search query must be 128 characters or less." });
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
    setDraftState({ request, viewIdentity, draft: nextRequest, error: null });
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
          Alert recommendation
          <select value={draft.status} onChange={(event) => updateField("status", event.target.value)}>
            {ALERT_RECOMMENDATION_FILTERS.map(([status, label]) => (
              <option key={status} value={status}>{label}</option>
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
          {request.status && request.status !== "ALL" && (
            <span className="tag">Alert recommendation: {alertRecommendationFilterLabel(request.status)}</span>
          )}
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

function alertRecommendationFilterLabel(status) {
  return ALERT_RECOMMENDATION_FILTERS.find(([value]) => value === status)?.[1] || status;
}

function sameTransactionRequest(left, right) {
  return JSON.stringify(editableTransactionRequest(left)) === JSON.stringify(editableTransactionRequest(right));
}

function hasAppliedTransactionFilters(request) {
  const editable = editableTransactionRequest(request);
  return Boolean(editable.query.trim()) || editable.riskLevel !== "ALL" || editable.status !== "ALL";
}
