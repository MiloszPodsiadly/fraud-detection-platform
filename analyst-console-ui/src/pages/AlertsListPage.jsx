import { useDeferredValue, useMemo, useState } from "react";
import { AlertTable } from "../components/AlertTable.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { FilterBar } from "../components/FilterBar.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { PaginationControls } from "../components/PaginationControls.jsx";
import { TransactionMonitorTable } from "../components/TransactionMonitorTable.jsx";

export function AlertsListPage({
  alerts,
  transactionPage,
  isLoading,
  error,
  onRetry,
  onTransactionPageChange,
  onTransactionPageSizeChange,
  onOpenAlert
}) {
  const [filters, setFilters] = useState({
    query: "",
    riskLevel: "ALL",
    status: "ALL"
  });
  const deferredQuery = useDeferredValue(filters.query);

  const filteredAlerts = useMemo(() => {
    const query = deferredQuery.trim().toLowerCase();
    return alerts.filter((alert) => {
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
  }, [alerts, deferredQuery, filters.riskLevel, filters.status]);

  const transactions = transactionPage.content || [];

  return (
    <div className="dashboardGrid pageEnter">
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

        {isLoading && <LoadingPanel label="Loading scored transactions..." />}
        {!isLoading && error && <ErrorState message={error} onRetry={onRetry} />}
        {!isLoading && !error && transactions.length === 0 && (
          <EmptyState
            title="No scored transactions yet"
            message="Start synthetic replay to populate legitimate and suspicious transaction monitoring."
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
              onPageChange={onTransactionPageChange}
              onSizeChange={onTransactionPageSizeChange}
            />
          </>
        )}
      </section>

      <section className="panel">
        <div className="panelHeader">
          <div>
            <p className="eyebrow">Queue</p>
            <h2>Alert review queue</h2>
          </div>
        </div>

        <FilterBar filters={filters} onChange={setFilters} />

        {isLoading && <LoadingPanel label="Loading fraud alerts..." />}
        {!isLoading && error && <ErrorState message={error} onRetry={onRetry} />}
        {!isLoading && !error && filteredAlerts.length === 0 && (
          <EmptyState
            title="No alerts match this view"
            message="Adjust filters or generate synthetic high-risk traffic to populate the queue."
          />
        )}
        {!isLoading && !error && filteredAlerts.length > 0 && (
          <AlertTable alerts={filteredAlerts} onOpenAlert={onOpenAlert} />
        )}
      </section>
    </div>
  );
}
