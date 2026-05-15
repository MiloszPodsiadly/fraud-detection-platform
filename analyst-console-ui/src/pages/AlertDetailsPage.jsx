import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { AUTHORITIES, hasAuthority } from "../auth/session.js";
import { AnalystDecisionForm } from "../components/AnalystDecisionForm.jsx";
import { AssistantSummaryPanel } from "../components/AssistantSummaryPanel.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { JsonInspector } from "../components/JsonInspector.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { RiskBadge } from "../components/RiskBadge.jsx";
import { TransactionSummary } from "../components/TransactionSummary.jsx";
import { formatDateTime, formatScore } from "../utils/format.js";

export function AlertDetailsPage({ alertId, alertSummary, session, apiClient, onBack, onDecisionSubmitted }) {
  const [alert, setAlert] = useState(null);
  const [assistantSummary, setAssistantSummary] = useState(null);
  const [isAssistantLoading, setIsAssistantLoading] = useState(false);
  const [assistantError, setAssistantError] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");
  const alertRequestSeqRef = useRef(0);
  const assistantRequestSeqRef = useRef(0);
  const alertAbortRef = useRef(null);
  const assistantAbortRef = useRef(null);
  const currentContextRef = useRef({ alertId, apiClient });

  useEffect(() => {
    currentContextRef.current = { alertId, apiClient };
  }, [alertId, apiClient]);

  const loadAssistantSummary = useCallback(async (context = {
    alertId,
    apiClient,
    alertRequestSeq: alertRequestSeqRef.current
  }) => {
    assistantAbortRef.current?.abort();
    const abortController = new AbortController();
    assistantAbortRef.current = abortController;
    const requestSeq = assistantRequestSeqRef.current + 1;
    assistantRequestSeqRef.current = requestSeq;
    const currentAlertId = context.alertId;
    const currentApiClient = context.apiClient;
    setIsAssistantLoading(true);
    setAssistantError("");
    try {
      const nextSummary = await currentApiClient.getAssistantSummary(currentAlertId, { signal: abortController.signal });
      if (!isCurrentAssistantRequest(requestSeq, context, abortController.signal, assistantRequestSeqRef, alertRequestSeqRef, currentContextRef)) {
        return { status: "stale" };
      }
      setAssistantSummary(nextSummary);
      return { status: "loaded" };
    } catch (apiError) {
      if (!isCurrentAssistantRequest(requestSeq, context, abortController.signal, assistantRequestSeqRef, alertRequestSeqRef, currentContextRef)) {
        return { status: "stale" };
      }
      if (isAbortError(apiError)) {
        return { status: "aborted" };
      }
      setAssistantError(apiError.message);
      return { status: "failed" };
    } finally {
      if (assistantRequestSeqRef.current === requestSeq) {
        setIsAssistantLoading(false);
        if (assistantAbortRef.current === abortController) {
          assistantAbortRef.current = null;
        }
      }
    }
  }, [alertId, apiClient]);

  const loadAlert = useCallback(async () => {
    alertAbortRef.current?.abort();
    assistantAbortRef.current?.abort();
    const abortController = new AbortController();
    alertAbortRef.current = abortController;
    const requestSeq = alertRequestSeqRef.current + 1;
    alertRequestSeqRef.current = requestSeq;
    assistantRequestSeqRef.current += 1;
    const currentAlertId = alertId;
    const currentApiClient = apiClient;
    setIsLoading(true);
    setError("");
    setAssistantSummary(null);
    setAssistantError("");
    try {
      const nextAlert = await currentApiClient.getAlert(currentAlertId, { signal: abortController.signal });
      if (alertRequestSeqRef.current !== requestSeq || abortController.signal.aborted) {
        return { status: abortController.signal.aborted ? "aborted" : "stale" };
      }
      setAlert(nextAlert);
      loadAssistantSummary({
        alertId: currentAlertId,
        apiClient: currentApiClient,
        alertRequestSeq: requestSeq
      });
      return { status: "loaded" };
    } catch (apiError) {
      if (alertRequestSeqRef.current !== requestSeq) {
        return { status: "stale" };
      }
      if (isAbortError(apiError)) {
        return { status: "aborted" };
      }
      setError(apiError.message);
      return { status: "failed" };
    } finally {
      if (alertRequestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (alertAbortRef.current === abortController) {
          alertAbortRef.current = null;
        }
      }
    }
  }, [alertId, apiClient, loadAssistantSummary]);

  useEffect(() => {
    loadAlert();
    return () => {
      alertAbortRef.current?.abort();
      assistantAbortRef.current?.abort();
      alertRequestSeqRef.current += 1;
      assistantRequestSeqRef.current += 1;
    };
  }, [loadAlert]);

  async function handleDecisionSubmitted() {
    const refreshResult = await loadAlert();
    if (refreshResult?.status === "loaded") {
      await onDecisionSubmitted();
    }
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

            <AssistantSummaryPanel
              summary={assistantSummary}
              isLoading={isAssistantLoading}
              error={assistantError}
              onRetry={loadAssistantSummary}
            />

            <JsonInspector title="Feature snapshot" value={alert.featureSnapshot} />
            <JsonInspector title="Score details" value={alert.scoreDetails} />
          </>
        )}
      </div>

      <aside className="panel decisionRail">
        <AnalystDecisionForm
          alertId={alertId}
          session={session}
          apiClient={apiClient}
          canSubmit={hasAuthority(session, AUTHORITIES.ALERT_DECISION_SUBMIT)}
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

function isCurrentAssistantRequest(requestSeq, context, signal, assistantRequestSeqRef, alertRequestSeqRef, currentContextRef) {
  return assistantRequestSeqRef.current === requestSeq
    && alertRequestSeqRef.current === context.alertRequestSeq
    && currentContextRef.current.alertId === context.alertId
    && currentContextRef.current.apiClient === context.apiClient
    && !signal.aborted;
}
