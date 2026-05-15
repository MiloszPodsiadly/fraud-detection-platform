import { useEffect, useState } from "react";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { PaginationControls } from "../components/PaginationControls.jsx";
import { TransactionMonitorTable } from "../components/TransactionMonitorTable.jsx";

export function TransactionScoringWorkspacePage({
  transactionPage,
  transactionPageRequest = { query: "", riskLevel: "ALL", status: "ALL" },
  isLoading,
  error,
  onRetry,
  onTransactionFiltersChange = () => {},
  onTransactionPageChange,
  onTransactionPageSizeChange
}) {
  const transactions = transactionPage.content || [];

  return (
    <section className="panel" id="transaction-stream" aria-labelledby="transaction-stream-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Monitor</p>
          <h2 id="transaction-stream-title" tabIndex="-1">Transaction scoring stream</h2>
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
    </section>
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
