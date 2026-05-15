import { useDeferredValue, useMemo, useState } from "react";
import { AlertTable } from "../components/AlertTable.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { FilterBar } from "../components/FilterBar.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { PaginationControls } from "../components/PaginationControls.jsx";

export function FraudTransactionWorkspacePage({
  alertPage,
  isLoading,
  error,
  onRetry,
  onAlertPageChange,
  onAlertPageSizeChange,
  onOpenAlert,
  workspaceHeadingProps = {}
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

  return (
    <section className="panel" id="alert-queue" aria-labelledby="alert-review-queue-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Queue</p>
          <h2 id="alert-review-queue-title" tabIndex="-1" {...workspaceHeadingProps}>Alert review queue</h2>
        </div>
      </div>

      <FilterBar filters={filters} onChange={setFilters} />

      {isLoading && <LoadingPanel label="Loading fraud alerts..." />}
      {!isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
      {!isLoading && !error && filteredAlerts.length === 0 && (
        <EmptyState
          title="No alerts on this loaded page match the local filters."
          message={alertPage.totalPages > 1 ? "Change filters or load another page to continue reviewing." : "Change filters to continue reviewing the loaded page."}
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
    </section>
  );
}
