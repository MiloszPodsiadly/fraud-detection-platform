import { useEffect, useState } from "react";
import { getAlert } from "../api/alertsApi.js";
import { AnalystDecisionForm } from "../components/AnalystDecisionForm.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { JsonInspector } from "../components/JsonInspector.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { RiskBadge } from "../components/RiskBadge.jsx";
import { TransactionSummary } from "../components/TransactionSummary.jsx";
import { formatDateTime, formatScore } from "../utils/format.js";

export function AlertDetailsPage({ alertId, alertSummary, onBack, onDecisionSubmitted }) {
  const [alert, setAlert] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    loadAlert();
  }, [alertId]);

  async function loadAlert() {
    setIsLoading(true);
    setError("");
    try {
      setAlert(await getAlert(alertId));
    } catch (apiError) {
      setError(apiError.message);
    } finally {
      setIsLoading(false);
    }
  }

  async function handleDecisionSubmitted() {
    await loadAlert();
    await onDecisionSubmitted();
  }

  return (
    <section className="detailsLayout pageEnter">
      <div className="panel detailsMain">
        <button className="backButton" type="button" onClick={onBack}>
          Back to queue
        </button>

        {isLoading && <LoadingPanel label="Loading alert details..." />}
        {!isLoading && error && <ErrorState message={error} onRetry={loadAlert} />}
        {!isLoading && !error && !alert && (
          <EmptyState title="Alert not found" message="The selected alert is no longer available." />
        )}
        {!isLoading && !error && alert && (
          <>
            <div className="detailsHeader">
              <div>
                <p className="eyebrow">Alert {alert.alertId}</p>
                <h2>{alert.alertReason}</h2>
                <p className="muted">
                  Created {formatDateTime(alert.createdAt)} with correlation ID{" "}
                  <code>{alert.correlationId}</code>
                </p>
              </div>
              <RiskBadge riskLevel={alert.riskLevel} />
            </div>

            <div className="metricGrid">
              <Metric label="Fraud score" value={formatScore(alert.fraudScore)} />
              <Metric label="Status" value={alert.alertStatus} />
              <Metric label="Customer" value={alert.customerId} />
              <Metric label="Transaction" value={alert.transactionId} />
            </div>

            <TransactionSummary alert={alert} />

            <div className="splitGrid">
              <section className="subPanel">
                <h3>Reason codes</h3>
                <div className="tagList">
                  {(alert.reasonCodes || []).map((reason) => (
                    <span className="tag" key={reason}>{reason}</span>
                  ))}
                  {(!alert.reasonCodes || alert.reasonCodes.length === 0) && (
                    <span className="muted">No reason codes supplied.</span>
                  )}
                </div>
              </section>

              <section className="subPanel">
                <h3>Decision state</h3>
                <dl className="kvList">
                  <div><dt>Decision</dt><dd>{alert.analystDecision || "Pending"}</dd></div>
                  <div><dt>Analyst</dt><dd>{alert.analystId || "Unassigned"}</dd></div>
                  <div><dt>Decided at</dt><dd>{formatDateTime(alert.decidedAt)}</dd></div>
                </dl>
              </section>
            </div>

            <JsonInspector title="Feature snapshot" value={alert.featureSnapshot} />
            <JsonInspector title="Score details" value={alert.scoreDetails} />
          </>
        )}
      </div>

      <aside className="panel decisionRail">
        <AnalystDecisionForm
          alertId={alertId}
          disabled={isLoading || Boolean(error)}
          summary={alert || alertSummary}
          onSubmitted={handleDecisionSubmitted}
        />
      </aside>
    </section>
  );
}

function Metric({ label, value }) {
  return (
    <div className="metricCard">
      <span>{label}</span>
      <strong>{value || "Unknown"}</strong>
    </div>
  );
}
