import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { DetailHeader } from "../components/DetailHeader.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { formatDateTime, formatScore } from "../utils/format.js";

export function AlertReadOnlyContextPage({
  alertId,
  sourceSuspiciousTransaction,
  sourceSuspiciousTransactionLoading = false,
  sourceSuspiciousTransactionError = null,
  alertReadClient,
  canReadAlert = true,
  workspaceLabel = "Suspicious Transactions",
  onBack
}) {
  const [alert, setAlert] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [detailState, setDetailState] = useState("idle");
  const [errorMessage, setErrorMessage] = useState("");
  const headingRef = useRef(null);
  const requestSeqRef = useRef(0);
  const alertAbortRef = useRef(null);

  const sourceState = sourceVerificationState({
    alertId,
    sourceSuspiciousTransaction,
    sourceSuspiciousTransactionLoading,
    sourceSuspiciousTransactionError
  });
  const effectiveAlertReadClient = getAlertOnlyClient(alertReadClient);
  const canFetchAlert = canReadAlert === true && sourceState.state === "verified" && Boolean(effectiveAlertReadClient);
  const linkedAlertContextMismatch = alert && !linkedAlertContextMatches(alert, sourceSuspiciousTransaction);

  useEffect(() => {
    if (!canFetchAlert) {
      alertAbortRef.current?.abort();
      requestSeqRef.current += 1;
      setIsLoading(false);
      setAlert(null);
      setErrorMessage("");
      setDetailState(sourceState.state);
      return;
    }

    alertAbortRef.current?.abort();
    const abortController = new AbortController();
    alertAbortRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setAlert(null);
    setIsLoading(true);
    setErrorMessage("");
    setDetailState("loading");

    effectiveAlertReadClient.getAlert(alertId, { signal: abortController.signal })
      .then((nextAlert) => {
        if (requestSeqRef.current !== requestSeq || abortController.signal.aborted) {
          return;
        }
        setAlert(nextAlert);
        setDetailState("loaded");
      })
      .catch((apiError) => {
        if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
          return;
        }
        const safeState = readOnlyFailureState(apiError);
        setErrorMessage(safeState.message);
        setDetailState(safeState.state);
      })
      .finally(() => {
        if (requestSeqRef.current === requestSeq) {
          setIsLoading(false);
          if (alertAbortRef.current === abortController) {
            alertAbortRef.current = null;
          }
        }
      });

    return () => {
      abortController.abort();
      requestSeqRef.current += 1;
    };
  }, [alertId, canFetchAlert, effectiveAlertReadClient, sourceState.state]);

  useEffect(() => {
    if (!isLoading) {
      headingRef.current?.focus();
    }
  }, [isLoading, detailState]);

  if (canReadAlert === false) {
    return (
      <ReadOnlyState
        title="Alert context unavailable"
        message="Alert detail requires alert read access."
        workspaceLabel={workspaceLabel}
        onBack={onBack}
        headingRef={headingRef}
      />
    );
  }

  if (sourceState.state === "verifying") {
    return (
      <ReadOnlyState
        title="Verifying linked alert context"
        message="Verifying linked alert context"
        workspaceLabel={workspaceLabel}
        onBack={onBack}
        headingRef={headingRef}
      />
    );
  }

  if (sourceState.state === "source-unavailable") {
    return (
      <ReadOnlyState
        title="Linked alert context unavailable"
        message="Linked alert context is unavailable."
        workspaceLabel={workspaceLabel}
        onBack={onBack}
        headingRef={headingRef}
      />
    );
  }

  if (sourceState.state === "invalid") {
    return (
      <ReadOnlyState
        title="Invalid linked alert context"
        message="Linked alert context could not be verified."
        workspaceLabel={workspaceLabel}
        onBack={onBack}
        headingRef={headingRef}
      />
    );
  }

  if (!effectiveAlertReadClient) {
    return (
      <ReadOnlyState
        title="Alert context unavailable"
        message="Alert context is temporarily unavailable."
        workspaceLabel={workspaceLabel}
        onBack={onBack}
        headingRef={headingRef}
      />
    );
  }

  return (
    <section className="detailsLayout pageEnter">
      <div className="panel detailsMain">
        {isLoading && <LoadingPanel label="Loading alert context..." />}
        {!isLoading && detailState === "access-denied" && (
          <ReadOnlyStateContent
            title="Alert context unavailable"
            message="Alert detail requires alert read access."
            workspaceLabel={workspaceLabel}
            onBack={onBack}
            headingRef={headingRef}
          />
        )}
        {!isLoading && detailState === "not-found" && (
          <ReadOnlyStateContent
            title="Alert not found"
            message="Alert not found"
            workspaceLabel={workspaceLabel}
            onBack={onBack}
            headingRef={headingRef}
          />
        )}
        {!isLoading && errorMessage && detailState !== "access-denied" && detailState !== "not-found" && (
          <ErrorState message={errorMessage} />
        )}
        {!isLoading && linkedAlertContextMismatch && (
          <ErrorState message="Linked alert context could not be verified." />
        )}
        {!isLoading && alert && !linkedAlertContextMismatch && (
          <>
            <DetailHeader
              title="Alert context"
              entityType="Alert"
              entityId={alert.alertId}
              workspaceLabel={workspaceLabel}
              status={alert.alertStatus}
              riskLevel={alert.riskLevel}
              actionState="Read-only alert context"
              onBack={onBack}
              headingRef={headingRef}
              headingId={`read-only-alert-context-${safeDomId(alert.alertId)}`}
            />
            <ReadOnlyAlertContextBanner />
            <div className="metricGrid">
              <Metric label="Alert score" value={formatScore(alert.fraudScore)} />
              <Metric label="Operational status" value={alert.alertStatus} />
              <Metric label="Risk level" value={alert.riskLevel} />
              <Metric label="Customer" value={alert.customerId} />
            </div>
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
                <h3>Read-only alert metadata</h3>
                <dl className="kvList">
                  <div><dt>Transaction</dt><dd>{valueOrUnknown(alert.transactionId)}</dd></div>
                  <div><dt>Customer</dt><dd>{valueOrUnknown(alert.customerId)}</dd></div>
                  <div><dt>Correlation ID</dt><dd>{valueOrUnknown(alert.correlationId)}</dd></div>
                  <div><dt>Score decision ID</dt><dd>{valueOrUnknown(alert.scoreDecisionId)}</dd></div>
                  <div><dt>Created at</dt><dd>{formatDateTime(alert.createdAt)}</dd></div>
                  <div><dt>Updated at</dt><dd>{formatDateTime(alert.updatedAt)}</dd></div>
                </dl>
              </section>
              <section className="subPanel">
                <h3>Linked suspicious transaction</h3>
                <dl className="kvList">
                  <div><dt>Suspicious transaction</dt><dd>{valueOrUnknown(sourceSuspiciousTransaction.suspiciousTransactionId)}</dd></div>
                  <div><dt>Linked alert</dt><dd>{valueOrUnknown(sourceSuspiciousTransaction.linkedAlertId)}</dd></div>
                </dl>
              </section>
            </div>
          </>
        )}
      </div>
    </section>
  );
}

function ReadOnlyState({ title, message, workspaceLabel, onBack, headingRef }) {
  return (
    <section className="detailsLayout pageEnter">
      <div className="panel detailsMain">
        <ReadOnlyStateContent
          title={title}
          message={message}
          workspaceLabel={workspaceLabel}
          onBack={onBack}
          headingRef={headingRef}
        />
      </div>
    </section>
  );
}

function ReadOnlyStateContent({ title, message, workspaceLabel, onBack, headingRef }) {
  return (
    <>
      <DetailHeader
        title={title}
        entityType="Alert"
        workspaceLabel={workspaceLabel}
        actionState="Read-only alert context"
        onBack={onBack}
        headingRef={headingRef}
        headingId="read-only-alert-context-state"
      />
      <EmptyState title={title} message={message} />
    </>
  );
}

function ReadOnlyAlertContextBanner() {
  return (
    <section className="subPanel" aria-label="Read-only alert context semantics">
      <p className="eyebrow">Alert context</p>
      <p className="sectionCopy">
        Alert detail is investigation context. Not confirmed fraud. Not an analyst decision.
        Not a final outcome. Not a case lifecycle action.
      </p>
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

function sourceVerificationState({
  alertId,
  sourceSuspiciousTransaction,
  sourceSuspiciousTransactionLoading,
  sourceSuspiciousTransactionError
}) {
  if (sourceSuspiciousTransactionLoading || !sourceSuspiciousTransaction) {
    return sourceSuspiciousTransactionError ? { state: "source-unavailable" } : { state: "verifying" };
  }
  if (sourceSuspiciousTransactionError) {
    return { state: "source-unavailable" };
  }
  return normalizeComparable(sourceSuspiciousTransaction.linkedAlertId) === normalizeComparable(alertId)
    ? { state: "verified" }
    : { state: "invalid" };
}

function linkedAlertContextMatches(alert, suspiciousTransaction) {
  return stringMatch(alert.alertId, suspiciousTransaction.linkedAlertId)
    && optionalStringMatch(alert.transactionId, suspiciousTransaction.transactionId)
    && optionalStringMatch(alert.customerId, suspiciousTransaction.customerId)
    && optionalStringMatch(alert.scoreDecisionId, suspiciousTransaction.scoreDecisionId);
}

function getAlertOnlyClient(alertReadClient) {
  if (!alertReadClient || typeof alertReadClient.getAlert !== "function") {
    return null;
  }
  return Object.keys(alertReadClient).length === 1 ? alertReadClient : null;
}

function readOnlyFailureState(error) {
  if (error?.isUnauthorized || error?.isForbidden || error?.status === 401 || error?.status === 403) {
    return {
      state: "access-denied",
      message: "Alert detail requires alert read access."
    };
  }
  if (error?.status === 404) {
    return {
      state: "not-found",
      message: "Alert not found"
    };
  }
  return {
    state: "unavailable",
    message: "Alert context is temporarily unavailable."
  };
}

function stringMatch(left, right) {
  return normalizeComparable(left) !== "" && normalizeComparable(left) === normalizeComparable(right);
}

function optionalStringMatch(left, right) {
  const normalizedLeft = normalizeComparable(left);
  const normalizedRight = normalizeComparable(right);
  return normalizedLeft === "" || normalizedRight === "" || normalizedLeft === normalizedRight;
}

function normalizeComparable(value) {
  return value === null || value === undefined ? "" : String(value).trim();
}

function valueOrUnknown(value) {
  const normalized = normalizeComparable(value);
  return normalized || "Unknown";
}

function safeDomId(value) {
  const safe = String(value || "unknown").replace(/[^A-Za-z0-9-]+/g, "-").replace(/^-+|-+$/g, "");
  return safe || "unknown";
}
