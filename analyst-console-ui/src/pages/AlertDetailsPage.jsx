import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { AUTHORITIES, hasAuthority } from "../auth/session.js";
import { AnalystDecisionForm } from "../components/AnalystDecisionForm.jsx";
import { AssistantSummaryPanel } from "../components/AssistantSummaryPanel.jsx";
import { DetailHeader } from "../components/DetailHeader.jsx";
import { DetailStateBanner } from "../components/DetailStateBanner.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { JsonInspector } from "../components/JsonInspector.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { PermissionNotice } from "../components/SecurityStatePanels.jsx";
import { TransactionSummary } from "../components/TransactionSummary.jsx";
import { formatDateTime, formatScore } from "../utils/format.js";
import { isWorkspaceDetailRuntimeAvailable, normalizeWorkspaceDetailRuntimeState } from "../workspace/workspaceRuntimeStates.js";

export function AlertDetailsPage({
  alertId,
  alertSummary,
  alertSummaryRuntimeState,
  session,
  apiClient,
  canReadAlert = true,
  workspaceLabel = "Fraud Transaction",
  onBack,
  onDecisionSubmitted
}) {
  const [alert, setAlert] = useState(null);
  const [assistantSummary, setAssistantSummary] = useState(null);
  const [isAssistantLoading, setIsAssistantLoading] = useState(false);
  const [assistantError, setAssistantError] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");
  const [detailState, setDetailState] = useState("loading");
  const [lastSuccessfulLoadAt, setLastSuccessfulLoadAt] = useState(null);
  const alertRequestSeqRef = useRef(0);
  const assistantRequestSeqRef = useRef(0);
  const alertAbortRef = useRef(null);
  const assistantAbortRef = useRef(null);
  const currentContextRef = useRef({ alertId, apiClient });
  const currentAlertRef = useRef(null);
  const headingRef = useRef(null);
  const canSubmitDecision = hasAuthority(session, AUTHORITIES.ALERT_DECISION_SUBMIT);
  const detailHeadingId = `detail-heading-alert-${safeDomId(alertId)}`;
  const normalizedAlertSummaryRuntimeState = normalizeWorkspaceDetailRuntimeState(alertSummaryRuntimeState);

  useEffect(() => {
    currentContextRef.current = { alertId, apiClient };
  }, [alertId, apiClient]);

  useEffect(() => {
    currentAlertRef.current = alert;
  }, [alert]);

  useEffect(() => {
    if (!isLoading) {
      headingRef.current?.focus();
    }
  }, [alertId, isLoading]);

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
    if (canReadAlert !== true) {
      alertAbortRef.current?.abort();
      assistantAbortRef.current?.abort();
      setIsLoading(false);
      setError("");
      setDetailState(canReadAlert === false ? "access-denied" : "runtime-not-ready");
      return { status: canReadAlert === false ? "access-denied" : "runtime-not-ready" };
    }
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
    setDetailState("loading");
    setAssistantSummary(null);
    setAssistantError("");
    try {
      const nextAlert = await currentApiClient.getAlert(currentAlertId, { signal: abortController.signal });
      if (alertRequestSeqRef.current !== requestSeq || abortController.signal.aborted) {
        return { status: abortController.signal.aborted ? "aborted" : "stale" };
      }
      setAlert(nextAlert);
      const loadedAt = new Date().toISOString();
      setLastSuccessfulLoadAt(loadedAt);
      setDetailState("loaded");
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
      setDetailState(currentAlertRef.current ? "stale" : "unavailable");
      return { status: "failed" };
    } finally {
      if (alertRequestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (alertAbortRef.current === abortController) {
          alertAbortRef.current = null;
        }
      }
    }
  }, [alertId, apiClient, canReadAlert, loadAssistantSummary]);

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
    return refreshResult;
  }

  const actionState = alertActionState({
    canSubmitDecision,
    detailState,
    canReadAlert,
    hasAlert: Boolean(alert)
  });
  const decisionDisabled = isLoading || detailState === "stale" || Boolean(error && !alert);

  return (
    <section className="detailsLayout pageEnter">
      <div className="panel detailsMain">
        {isLoading && <LoadingPanel label="Loading alert details..." />}
        {!isLoading && canReadAlert !== true && (
          <>
            <DetailHeader
              title="Alert detail"
              entityType="Alert"
              entityId={alertId}
              workspaceLabel={workspaceLabel}
              actionState={actionState}
              onBack={onBack}
              headingRef={headingRef}
              headingId={detailHeadingId}
            />
            <DetailStateBanner
              state={canReadAlert === false ? "access-denied" : "runtime-not-ready"}
              message={canReadAlert === false ? "This session does not include alert read authority." : "Alert detail cannot load until runtime capabilities are ready."}
            />
          </>
        )}
        {!isLoading && canReadAlert === true && error && !alert && <ErrorState message={error} onRetry={loadAlert} />}
        {!isLoading && canReadAlert === true && !error && !alert && (
          <EmptyState title="Alert not found" message="The selected alert is no longer available." />
        )}
        {!isLoading && canReadAlert === true && alert && (
          <>
            <DetailHeader
              title={alert.alertReason}
              entityType="Alert"
              entityId={alert.alertId}
              workspaceLabel={workspaceLabel}
              status={alert.alertStatus}
              riskLevel={alert.riskLevel}
              actionState={actionState}
              lastLoadedAt={lastSuccessfulLoadAt}
              onBack={onBack}
              headingRef={headingRef}
              headingId={detailHeadingId}
            />
            <DetailStateBanner
              state={detailState === "stale" ? "stale" : null}
              message={detailState === "stale" ? staleAlertMessage(error) : error}
              onRetry={loadAlert}
              retryLabel={`Retry alert ${alert.alertId} detail`}
            />
            <p className="muted">
              Created {formatDateTime(alert.createdAt)} with correlation ID{" "}
              <code>{alert.correlationId}</code>
            </p>
            {!isWorkspaceDetailRuntimeAvailable(normalizedAlertSummaryRuntimeState) && !alertSummary && (
              <DetailStateBanner
                state="runtime-not-ready"
                message="Alert queue summary is not mounted for this workspace; detail data is loaded directly from the alert service."
              />
            )}

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
        {canSubmitDecision ? (
          <AnalystDecisionForm
            alertId={alertId}
            session={session}
            apiClient={apiClient}
            canSubmit
            disabled={decisionDisabled}
            summary={alert || alertSummary}
            onSubmitted={handleDecisionSubmitted}
          />
        ) : (
          <PermissionNotice
            session={session}
            authority={AUTHORITIES.ALERT_DECISION_SUBMIT}
            action="submitting an analyst decision"
          />
        )}
      </aside>
    </section>
  );
}

function alertActionState({ canSubmitDecision, detailState, canReadAlert, hasAlert }) {
  if (canReadAlert === false || !canSubmitDecision) {
    return "Decision action unavailable: missing authority";
  }
  if (canReadAlert !== true || (!hasAlert && detailState === "runtime-not-ready")) {
    return "Decision action unavailable: runtime not ready";
  }
  if (detailState === "stale") {
    return "Decision action unavailable: refresh required";
  }
  return "Decision action available";
}

function safeDomId(value) {
  const safe = String(value || "unknown").replace(/[^A-Za-z0-9-]+/g, "-").replace(/^-+|-+$/g, "");
  return safe || "unknown";
}

function staleAlertMessage(error) {
  return error
    ? `Refresh alert detail successfully before submitting a decision. Last refresh error: ${error}`
    : "Refresh alert detail successfully before submitting a decision.";
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
