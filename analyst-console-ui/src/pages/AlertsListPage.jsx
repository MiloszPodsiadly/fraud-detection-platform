import { useDeferredValue, useMemo, useState } from "react";
import { AlertTable } from "../components/AlertTable.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { FilterBar } from "../components/FilterBar.jsx";
import { FraudCasePanel } from "../components/FraudCasePanel.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { PaginationControls } from "../components/PaginationControls.jsx";
import { TransactionMonitorTable } from "../components/TransactionMonitorTable.jsx";

export function AlertsListPage({
  alertPage,
  fraudCasePage,
  transactionPage,
  isLoading,
  error,
  onRetry,
  onTransactionPageChange,
  onTransactionPageSizeChange,
  onAlertPageChange,
  onAlertPageSizeChange,
  onFraudCasePageChange,
  onFraudCasePageSizeChange,
  onOpenAlert,
  onOpenFraudCase
}) {
  const [filters, setFilters] = useState({
    query: "",
    riskLevel: "ALL",
    status: "ALL"
  });
  const [transactionFilters, setTransactionFilters] = useState({
    query: "",
    riskLevel: "ALL",
    status: "ALL"
  });
  const [fraudCaseFilters, setFraudCaseFilters] = useState({
    query: "",
    status: "ALL"
  });
  const deferredQuery = useDeferredValue(filters.query);
  const deferredTransactionQuery = useDeferredValue(transactionFilters.query);
  const deferredFraudCaseQuery = useDeferredValue(fraudCaseFilters.query);

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
  const filteredTransactions = useMemo(() => {
    const query = deferredTransactionQuery.trim().toLowerCase();
    return transactions.filter((transaction) => {
      const matchesRisk = transactionFilters.riskLevel === "ALL" || transaction.riskLevel === transactionFilters.riskLevel;
      const classification = transaction.alertRecommended ? "SUSPICIOUS" : "LEGITIMATE";
      const matchesClassification = transactionFilters.status === "ALL" || classification === transactionFilters.status;
      const searchable = [
        transaction.transactionId,
        transaction.customerId,
        transaction.merchantInfo?.merchantName,
        transaction.merchantInfo?.merchantId,
        transaction.transactionAmount?.currency
      ].filter(Boolean).join(" ").toLowerCase();
      return matchesRisk && matchesClassification && (!query || searchable.includes(query));
    });
  }, [transactions, deferredTransactionQuery, transactionFilters.riskLevel, transactionFilters.status]);

  const filteredFraudCases = useMemo(() => {
    const query = deferredFraudCaseQuery.trim().toLowerCase();
    return (fraudCasePage.content || []).filter((fraudCase) => {
      const matchesStatus = fraudCaseFilters.status === "ALL" || fraudCase.status === fraudCaseFilters.status;
      const searchable = [
        fraudCase.caseId,
        fraudCase.customerId,
        fraudCase.suspicionType,
        fraudCase.reason,
        ...(fraudCase.transactionIds || [])
      ].filter(Boolean).join(" ").toLowerCase();
      return matchesStatus && (!query || searchable.includes(query));
    });
  }, [fraudCasePage.content, deferredFraudCaseQuery, fraudCaseFilters.status]);

  return (
    <div className="dashboardGrid pageEnter">
      <FraudCasePanel
        fraudCasePage={{ ...fraudCasePage, content: filteredFraudCases }}
        filters={fraudCaseFilters}
        onFiltersChange={setFraudCaseFilters}
        onPageChange={onFraudCasePageChange}
        onPageSizeChange={onFraudCasePageSizeChange}
        onOpenCase={onOpenFraudCase}
      />

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

        <FilterBar
          filters={transactionFilters}
          onChange={setTransactionFilters}
          placeholder="Transaction, customer, merchant, currency"
          statusOptions={["ALL", "LEGITIMATE", "SUSPICIOUS"]}
          statusLabel="Classification"
        />

        {isLoading && <LoadingPanel label="Loading scored transactions..." />}
        {!isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
        {!isLoading && !error && filteredTransactions.length === 0 && (
          <EmptyState
            title="No scored transactions match this view"
            message="Adjust filters or generate synthetic traffic to populate transaction monitoring."
          />
        )}
        {!isLoading && !error && filteredTransactions.length > 0 && (
          <>
            <TransactionMonitorTable transactions={filteredTransactions} />
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
      </section>
    </div>
  );
}
