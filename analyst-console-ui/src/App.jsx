import { useEffect, useMemo, useState } from "react";
import { listAlerts, listScoredTransactions } from "./api/alertsApi.js";
import { AlertDetailsPage } from "./pages/AlertDetailsPage.jsx";
import { AlertsListPage } from "./pages/AlertsListPage.jsx";

function getInitialAlertId() {
  return new URLSearchParams(window.location.search).get("alertId");
}

export default function App() {
  const [alerts, setAlerts] = useState([]);
  const [transactionPage, setTransactionPage] = useState({
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 25
  });
  const [transactionPageRequest, setTransactionPageRequest] = useState({ page: 0, size: 25 });
  const [selectedAlertId, setSelectedAlertId] = useState(getInitialAlertId);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    loadDashboard(transactionPageRequest);
  }, [transactionPageRequest]);

  useEffect(() => {
    const handlePopState = () => setSelectedAlertId(getInitialAlertId());
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const selectedAlertSummary = useMemo(
    () => alerts.find((alert) => alert.alertId === selectedAlertId),
    [alerts, selectedAlertId]
  );

  async function loadDashboard(nextPageRequest = transactionPageRequest) {
    setIsLoading(true);
    setError("");
    try {
      const [nextAlerts, nextTransactionPage] = await Promise.all([
        listAlerts(),
        listScoredTransactions(nextPageRequest)
      ]);
      setAlerts(nextAlerts);
      setTransactionPage(nextTransactionPage);
    } catch (apiError) {
      setError(apiError.message);
    } finally {
      setIsLoading(false);
    }
  }

  function refreshDashboard() {
    loadDashboard(transactionPageRequest);
  }

  function changeTransactionPage(page) {
    setTransactionPageRequest((current) => ({ ...current, page }));
  }

  function changeTransactionPageSize(size) {
    setTransactionPageRequest({ page: 0, size });
  }

  function openAlert(alertId) {
    const nextUrl = `${window.location.pathname}?alertId=${encodeURIComponent(alertId)}`;
    window.history.pushState({}, "", nextUrl);
    setSelectedAlertId(alertId);
  }

  function closeAlert() {
    window.history.pushState({}, "", window.location.pathname);
    setSelectedAlertId(null);
  }

  return (
    <div className="appShell">
      <header className="hero">
        <div>
          <p className="eyebrow">Fraud operations</p>
          <h1>Analyst Console</h1>
          <p className="heroCopy">
            Internal workspace for watching legitimate and suspicious transactions, triaging
            high-risk alerts, and submitting analyst decisions.
          </p>
        </div>
        <div className="heroStats" aria-label="Alert summary">
          <div>
            <span>{transactionPage.totalElements}</span>
            <small>Scored transactions</small>
          </div>
          <div>
            <span>{transactionPage.content.filter((transaction) => !transaction.alertRecommended).length}</span>
            <small>Page legitimate</small>
          </div>
          <div>
            <span>{transactionPage.content.filter((transaction) => transaction.alertRecommended).length}</span>
            <small>Page suspicious</small>
          </div>
          <div>
            <span>{alerts.filter((alert) => alert.alertStatus === "OPEN").length}</span>
            <small>Open alerts</small>
          </div>
        </div>
      </header>

      <main>
        {selectedAlertId ? (
          <AlertDetailsPage
            alertId={selectedAlertId}
            alertSummary={selectedAlertSummary}
            onBack={closeAlert}
            onDecisionSubmitted={refreshDashboard}
          />
        ) : (
          <AlertsListPage
            alerts={alerts}
            transactionPage={transactionPage}
            isLoading={isLoading}
            error={error}
            onRetry={refreshDashboard}
            onTransactionPageChange={changeTransactionPage}
            onTransactionPageSizeChange={changeTransactionPageSize}
            onOpenAlert={openAlert}
          />
        )}
      </main>
    </div>
  );
}
