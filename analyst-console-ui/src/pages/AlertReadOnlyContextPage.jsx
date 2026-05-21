import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { DetailHeader } from "../components/DetailHeader.jsx";
import { EmptyState } from "../components/EmptyState.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { formatDateTime, formatScore } from "../utils/format.js";

export function AlertReadOnlyContextPage({
  suspiciousTransactionId,
  sourceSuspiciousTransaction,
  sourceSuspiciousTransactionLoading = false,
  sourceSuspiciousTransactionError = null,
  linkedAlertContextClient,
  canReadAlert = true,
  workspaceLabel = "Suspicious Transactions",
  onBack
}) {
  const [context, setContext] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [detailState, setDetailState] = useState("idle");
  const [errorMessage, setErrorMessage] = useState("");
  const headingRef = useRef(null);
  const requestSeqRef = useRef(0);
  const abortRef = useRef(null);

  const sourceState = sourceVerificationState({
    suspiciousTransactionId,
    sourceSuspiciousTransaction,
    sourceSuspiciousTransactionLoading,
    sourceSuspiciousTransactionError
  });
  const effectiveClient = getLinkedAlertContextClient(linkedAlertContextClient);
  const canFetchContext = canReadAlert === true && sourceState.state === "verified" && Boolean(effectiveClient);
  const availableContext = detailState === "available" && context?.state === "LINKED_ALERT_AVAILABLE" ? context : null;

  useEffect(() => {
    if (!canFetchContext) {
      abortRef.current?.abort();
      requestSeqRef.current += 1;
      setIsLoading(false);
      setContext(null);
      setErrorMessage("");
      setDetailState(sourceState.state);
      return;
    }

    abortRef.current?.abort();
    const abortController = new AbortController();
    abortRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setContext(null);
    setIsLoading(true);
    setErrorMessage("");
    setDetailState("loading");

    effectiveClient.getSuspiciousTransactionLinkedAlertContext(suspiciousTransactionId, { signal: abortController.signal })
      .then((nextContext) => {
        if (requestSeqRef.current !== requestSeq || abortController.signal.aborted) {
          return;
        }
        const state = stateForLinkedAlertContext(nextContext);
        setContext(state === "available" ? nextContext : null);
        setDetailState(state);
      })
      .catch((apiError) => {
        if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
          return;
        }
        const safeState = readOnlyFailureState(apiError);
        setContext(null);
        setErrorMessage(safeState.message);
        setDetailState(safeState.state);
      })
      .finally(() => {
        if (requestSeqRef.current === requestSeq) {
          setIsLoading(false);
          if (abortRef.current === abortController) {
            abortRef.current = null;
          }
        }
      });

    return () => {
      abortController.abort();
      requestSeqRef.current += 1;
    };
  }, [canFetchContext, effectiveClient, sourceState.state, suspiciousTransactionId]);

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

  if (!effectiveClient) {
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
        {!isLoading && detailState === "no-linked-alert" && (
          <ReadOnlyStateContent
            title="No linked alert"
            message="No linked alert."
            workspaceLabel={workspaceLabel}
            onBack={onBack}
            headingRef={headingRef}
          />
        )}
        {!isLoading && detailState === "linked-alert-not-found" && (
          <ReadOnlyStateContent
            title="Linked alert unavailable"
            message="Linked alert is not available."
            workspaceLabel={workspaceLabel}
            onBack={onBack}
            headingRef={headingRef}
          />
        )}
        {!isLoading && detailState === "relationship-mismatch" && (
          <ReadOnlyStateContent
            title="Invalid linked alert context"
            message="Linked alert context could not be verified."
            workspaceLabel={workspaceLabel}
            onBack={onBack}
            headingRef={headingRef}
          />
        )}
        {!isLoading && detailState === "temporarily-unavailable" && (
          <ReadOnlyStateContent
            title="Linked alert context unavailable"
            message="Linked alert context is temporarily unavailable."
            workspaceLabel={workspaceLabel}
            onBack={onBack}
            headingRef={headingRef}
          />
        )}
        {!isLoading && detailState === "unknown-state" && (
          <ReadOnlyStateContent
            title="Linked alert context unavailable"
            message="Linked alert context is unavailable."
            workspaceLabel={workspaceLabel}
            onBack={onBack}
            headingRef={headingRef}
          />
        )}
        {!isLoading && errorMessage && detailState !== "access-denied" && (
          <ErrorState message={errorMessage} />
        )}
        {!isLoading && availableContext && (
          <>
            <DetailHeader
              title="Alert context"
              entityType="Alert"
              entityId={availableContext.alertId}
              workspaceLabel={workspaceLabel}
              status={availableContext.alertStatus}
              riskLevel={availableContext.riskLevel}
              actionState="Read-only alert context"
              onBack={onBack}
              headingRef={headingRef}
              headingId={`read-only-alert-context-${safeDomId(availableContext.alertId)}`}
            />
            <ReadOnlyAlertContextBanner />
            <div className="metricGrid">
              <Metric label="Alert score" value={formatScore(availableContext.alertScore)} />
              <Metric label="Operational status" value={availableContext.alertStatus} />
              <Metric label="Risk level" value={availableContext.riskLevel} />
              <Metric label="Customer" value={availableContext.customerId} />
            </div>
            <div className="splitGrid">
              <section className="subPanel">
                <h3>Reason codes</h3>
                <div className="tagList">
                  {(availableContext.reasonCodes || []).map((reason) => (
                    <span className="tag" key={reason}>{reason}</span>
                  ))}
                  {(!availableContext.reasonCodes || availableContext.reasonCodes.length === 0) && (
                    <span className="muted">No reason codes supplied.</span>
                  )}
                </div>
              </section>
              <section className="subPanel">
                <h3>Read-only alert metadata</h3>
                <dl className="kvList">
                  <div><dt>Alert</dt><dd>{valueOrUnknown(availableContext.alertId)}</dd></div>
                  <div><dt>Transaction</dt><dd>{valueOrUnknown(availableContext.transactionId)}</dd></div>
                  <div><dt>Customer</dt><dd>{valueOrUnknown(availableContext.customerId)}</dd></div>
                  <div><dt>Account</dt><dd>{valueOrUnknown(availableContext.accountId)}</dd></div>
                  <div><dt>Correlation ID</dt><dd>{valueOrUnknown(availableContext.correlationId)}</dd></div>
                  <div><dt>Score decision ID</dt><dd>{valueOrUnknown(availableContext.scoreDecisionId)}</dd></div>
                  <div><dt>Created at</dt><dd>{formatDateTime(availableContext.createdAt)}</dd></div>
                  <div><dt>Updated at</dt><dd>{formatDateTime(availableContext.updatedAt)}</dd></div>
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
  suspiciousTransactionId,
  sourceSuspiciousTransaction,
  sourceSuspiciousTransactionLoading,
  sourceSuspiciousTransactionError
}) {
  if (!normalizeComparable(suspiciousTransactionId)) {
    return { state: "invalid" };
  }
  if (sourceSuspiciousTransactionLoading) {
    return { state: "verifying" };
  }
  if (sourceSuspiciousTransactionError) {
    return { state: "source-unavailable" };
  }
  if (!sourceSuspiciousTransaction) {
    return { state: "verified" };
  }
  return normalizeComparable(sourceSuspiciousTransaction.suspiciousTransactionId) === normalizeComparable(suspiciousTransactionId)
    ? { state: "verified" }
    : { state: "invalid" };
}

function getLinkedAlertContextClient(linkedAlertContextClient) {
  if (!linkedAlertContextClient || typeof linkedAlertContextClient.getSuspiciousTransactionLinkedAlertContext !== "function") {
    return null;
  }
  return Object.keys(linkedAlertContextClient).length === 1 ? linkedAlertContextClient : null;
}

function stateForLinkedAlertContext(context) {
  if (context?.state === "LINKED_ALERT_AVAILABLE") {
    return "available";
  }
  if (context?.state === "NO_LINKED_ALERT") {
    return "no-linked-alert";
  }
  if (context?.state === "LINKED_ALERT_NOT_FOUND") {
    return "linked-alert-not-found";
  }
  if (context?.state === "LINKED_ALERT_RELATIONSHIP_MISMATCH") {
    return "relationship-mismatch";
  }
  if (context?.state === "TEMPORARILY_UNAVAILABLE") {
    return "temporarily-unavailable";
  }
  return "unknown-state";
}

function readOnlyFailureState(error) {
  if (error?.isUnauthorized || error?.isForbidden || error?.status === 401 || error?.status === 403) {
    return {
      state: "access-denied",
      message: "Alert detail requires alert read access."
    };
  }
  return {
    state: "temporarily-unavailable",
    message: "Alert context is temporarily unavailable."
  };
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
