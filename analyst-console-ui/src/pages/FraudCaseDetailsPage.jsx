import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { AUTHORITIES, hasAuthority } from "../auth/session.js";
import { DetailHeader } from "../components/DetailHeader.jsx";
import { DetailStateBanner } from "../components/DetailStateBanner.jsx";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { RiskBadge } from "../components/RiskBadge.jsx";
import { PermissionNotice } from "../components/SecurityStatePanels.jsx";
import { formatAmount, formatDateTime, formatScore } from "../utils/format.js";
import { createIdempotencyKey } from "../utils/idempotencyKey.js";

const CASE_STATUSES = ["IN_REVIEW", "CONFIRMED_FRAUD", "FALSE_POSITIVE", "CLOSED"];
const SECURE_REQUEST_ID_ERROR = "Secure request identifier could not be generated. Reload the page and try again.";

export function FraudCaseDetailsPage({
  caseId,
  session,
  apiClient,
  canReadFraudCase = true,
  workspaceLabel = "Fraud Case",
  onBack,
  onCaseUpdated
}) {
  const [fraudCase, setFraudCase] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");
  const [detailState, setDetailState] = useState("loading");
  const [lastSuccessfulLoadAt, setLastSuccessfulLoadAt] = useState(null);
  const [expandedTransactionId, setExpandedTransactionId] = useState(null);
  const [form, setForm] = useState({
    status: "IN_REVIEW",
    analystId: "",
    decisionReason: "",
    tags: "rapid-transfer, grouped-low-risk"
  });
  const [decisionIdempotencyKey, setDecisionIdempotencyKey] = useState("");
  const [submitState, setSubmitState] = useState({ isSubmitting: false, error: "", success: "", warning: "" });
  const loadRequestSeqRef = useRef(0);
  const mutationSeqRef = useRef(0);
  const loadAbortRef = useRef(null);
  const mutationAbortRef = useRef(null);
  const mountedRef = useRef(false);
  const currentContextRef = useRef({ caseId, apiClient });
  const currentCaseRef = useRef(null);
  const headingRef = useRef(null);
  const detailHeadingId = `detail-heading-fraud-case-${safeDomId(caseId)}`;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      loadAbortRef.current?.abort();
      mutationAbortRef.current?.abort();
      loadRequestSeqRef.current += 1;
      mutationSeqRef.current += 1;
    };
  }, []);

  useEffect(() => {
    currentContextRef.current = { caseId, apiClient };
    mutationAbortRef.current?.abort();
    mutationAbortRef.current = null;
    mutationSeqRef.current += 1;
    setSubmitState({ isSubmitting: false, error: "", success: "", warning: "" });
  }, [apiClient, caseId]);

  useEffect(() => {
    currentCaseRef.current = fraudCase;
  }, [fraudCase]);

  useEffect(() => {
    if (!isLoading) {
      headingRef.current?.focus();
    }
  }, [caseId, isLoading]);

  const loadCase = useCallback(async () => {
    if (canReadFraudCase !== true) {
      loadAbortRef.current?.abort();
      setIsLoading(false);
      setError("");
      setDetailState(canReadFraudCase === false ? "access-denied" : "runtime-not-ready");
      return { status: canReadFraudCase === false ? "access-denied" : "runtime-not-ready" };
    }
    loadAbortRef.current?.abort();
    const abortController = new AbortController();
    loadAbortRef.current = abortController;
    const requestSeq = loadRequestSeqRef.current + 1;
    loadRequestSeqRef.current = requestSeq;
    const currentCaseId = caseId;
    const currentApiClient = apiClient;
    setIsLoading(true);
    setError("");
    setDetailState("loading");
    try {
      const nextCase = await currentApiClient.getFraudCase(currentCaseId, { signal: abortController.signal });
      if (!isCurrentLoad(requestSeq, abortController.signal, loadRequestSeqRef, mountedRef)) {
        return;
      }
      setFraudCase(nextCase);
      setLastSuccessfulLoadAt(new Date().toISOString());
      setDetailState("loaded");
      setForm({
        status: nextCase.status === "OPEN" ? "IN_REVIEW" : nextCase.status,
        analystId: session.userId || nextCase.analystId || nextCase.assignedInvestigatorId || "",
        decisionReason: nextCase.decisionReason || "",
        tags: (nextCase.decisionTags || ["rapid-transfer", "grouped-low-risk"]).join(", ")
      });
      setDecisionIdempotencyKey("");
    } catch (apiError) {
      if (loadRequestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return;
      }
      setError(apiError.message);
      setDetailState(currentCaseRef.current ? "stale" : "unavailable");
    } finally {
      if (loadRequestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (loadAbortRef.current === abortController) {
          loadAbortRef.current = null;
        }
      }
    }
  }, [apiClient, canReadFraudCase, caseId, session.userId]);

  useEffect(() => {
    loadCase();
    return () => {
      loadAbortRef.current?.abort();
      loadRequestSeqRef.current += 1;
    };
  }, [loadCase]);

  if (isLoading) {
    return <LoadingPanel label="Loading fraud case..." />;
  }

  if (canReadFraudCase !== true) {
    return (
      <div className="pageEnter">
        <section className="panel detailsMain">
          <DetailHeader
            title="Fraud case detail"
            entityType="Fraud case"
            entityId={caseId}
            workspaceLabel={workspaceLabel}
            actionState={canReadFraudCase === false ? "Case update unavailable: missing authority" : "Case update unavailable: runtime not ready"}
            onBack={onBack}
            headingRef={headingRef}
            headingId={detailHeadingId}
          />
          <DetailStateBanner
            state={canReadFraudCase === false ? "access-denied" : "runtime-not-ready"}
            message={canReadFraudCase === false ? "This session does not include fraud case read authority." : "Fraud case detail cannot load until runtime capabilities are ready."}
          />
        </section>
      </div>
    );
  }

  if (error && !fraudCase) {
    return <ErrorState message={error} onRetry={loadCase} />;
  }

  const canUpdateCase = hasAuthority(session, AUTHORITIES.FRAUD_CASE_UPDATE);
  const actionDisabled = submitState.isSubmitting || !canUpdateCase || detailState === "stale";
  const actionState = fraudCaseActionState({ canUpdateCase, detailState });

  function changeForm(patch) {
    setForm((current) => ({ ...current, ...patch }));
    setDecisionIdempotencyKey("");
    setSubmitState((current) => ({ ...current, error: "", success: "", warning: "" }));
  }

  async function submitDecision(event) {
    event.preventDefault();
    if (!canUpdateCase || detailState === "stale") {
      return;
    }
    mutationAbortRef.current?.abort();
    const abortController = new AbortController();
    mutationAbortRef.current = abortController;
    const mutationSeq = mutationSeqRef.current + 1;
    mutationSeqRef.current = mutationSeq;
    const currentCaseId = caseId;
    const currentApiClient = apiClient;
    setSubmitState({ isSubmitting: true, error: "", success: "", warning: "" });
    try {
      let idempotencyKey;
      try {
        idempotencyKey = decisionIdempotencyKey || createIdempotencyKey("fraud-case-update");
      } catch {
        setSubmitState({ isSubmitting: false, error: SECURE_REQUEST_ID_ERROR, success: "", warning: "" });
        return;
      }
      setDecisionIdempotencyKey(idempotencyKey);
      // Abort only protects the frontend request lifecycle; an unsafe request that reached the server may still complete.
      const response = await currentApiClient.updateFraudCase(currentCaseId, {
        status: form.status,
        analystId: session.userId || form.analystId,
        decisionReason: form.decisionReason,
        tags: form.tags.split(",").map((tag) => tag.trim()).filter(Boolean)
      }, { idempotencyKey, signal: abortController.signal });
      if (!isCurrentMutation(mutationSeq, currentCaseId, currentApiClient, abortController.signal, mutationSeqRef, mountedRef, currentContextRef)) {
        return;
      }
      const updatedCase = response?.updated_case || response?.updatedCase || response?.current_case_snapshot || response?.currentCaseSnapshot || response;
      setFraudCase(updatedCase);
      setForm({
        status: updatedCase.status === "OPEN" ? "IN_REVIEW" : updatedCase.status,
        analystId: session.userId || updatedCase.analystId || updatedCase.assignedInvestigatorId || "",
        decisionReason: updatedCase.decisionReason || "",
        tags: (updatedCase.decisionTags || []).join(", ")
      });
      setDecisionIdempotencyKey("");
      setSubmitState({ isSubmitting: false, error: "", success: "Case decision saved.", warning: "" });
      let refreshWarning = "";
      try {
        await onCaseUpdated?.();
      } catch {
        refreshWarning = "Case decision saved. Latest dashboard state could not be refreshed.";
      }
      if (!isCurrentMutation(mutationSeq, currentCaseId, currentApiClient, abortController.signal, mutationSeqRef, mountedRef, currentContextRef)) {
        return;
      }
      if (refreshWarning) {
        setSubmitState({ isSubmitting: false, error: "", success: "Case decision saved.", warning: refreshWarning });
      }
    } catch (apiError) {
      if (!isCurrentMutation(mutationSeq, currentCaseId, currentApiClient, abortController.signal, mutationSeqRef, mountedRef, currentContextRef)) {
        return;
      }
      if (isAbortError(apiError)) {
        setSubmitState({ isSubmitting: false, error: "", success: "", warning: "" });
        return;
      }
      setSubmitState({ isSubmitting: false, error: apiError.message, success: "", warning: "" });
    } finally {
      if (mutationSeqRef.current === mutationSeq && mutationAbortRef.current === abortController) {
        mutationAbortRef.current = null;
      }
    }
  }

  return (
    <div className="pageEnter">
      <div className="detailsLayout">
        <section className="panel detailsMain">
          <DetailHeader
            title={fraudCase.suspicionType}
            entityType="Fraud case"
            entityId={fraudCase.caseNumber || fraudCase.caseId}
            workspaceLabel={workspaceLabel}
            status={fraudCase.status}
            riskLevel={fraudCase.riskLevel}
            actionState={actionState}
            lastLoadedAt={lastSuccessfulLoadAt}
            onBack={onBack}
            headingRef={headingRef}
            headingId={detailHeadingId}
          />
          <DetailStateBanner
            state={detailState === "stale" ? "stale" : null}
            message={detailState === "stale" ? staleFraudCaseMessage(error) : error}
            onRetry={loadCase}
            retryLabel={`Retry fraud case ${fraudCase.caseNumber || fraudCase.caseId} detail`}
          />
          <p className="sectionCopy">{fraudCase.reason}</p>

          <div className="metricGrid">
            <div className="metricCard">
              <strong>{formatPln(fraudCase.totalAmountPln)}</strong>
              <span>Total PLN</span>
            </div>
            <div className="metricCard">
              <strong>{fraudCase.transactions?.length || 0}</strong>
              <span>Grouped transfers</span>
            </div>
            <div className="metricCard">
              <strong>{fraudCase.aggregationWindow}</strong>
              <span>Aggregation window</span>
            </div>
            <div className="metricCard">
              <strong>{formatPln(fraudCase.thresholdPln)}</strong>
              <span>Threshold</span>
            </div>
          </div>

          <section className="subPanel">
            <div className="panelHeader">
              <div>
                <p className="eyebrow">Evidence</p>
                <h2>Grouped transactions</h2>
              </div>
            </div>
            <div className="tableWrap">
              <table className="alertTable">
                <thead>
                  <tr>
                    <th>Risk</th>
                    <th>Transaction</th>
                    <th>Amount</th>
                    <th>PLN</th>
                    <th>Timestamp</th>
                    <th className="numericCell">Score</th>
                    <th>Details</th>
                  </tr>
                </thead>
                <tbody>
                  {(fraudCase.transactions || []).map((transaction) => {
                    const expanded = expandedTransactionId === transaction.transactionId;
                    return (
                      <Fragment key={transaction.transactionId}>
                        <tr key={transaction.transactionId}>
                          <td><RiskBadge riskLevel={transaction.riskLevel} /></td>
                          <td>
                            <strong>{transaction.transactionId}</strong>
                            <span>{transaction.correlationId || "No correlation"}</span>
                          </td>
                          <td>{formatAmount(transaction.transactionAmount)}</td>
                          <td>{formatPln(transaction.amountPln)}</td>
                          <td>{formatDateTime(transaction.transactionTimestamp)}</td>
                          <td className="numericCell">{formatScore(transaction.fraudScore)}</td>
                          <td>
                            <button
                              className="rowButton compactButton"
                              type="button"
                              aria-expanded={expanded}
                              onClick={() => setExpandedTransactionId(expanded ? null : transaction.transactionId)}
                            >
                              {expanded ? "Hide" : "Details"}
                            </button>
                          </td>
                        </tr>
                        {expanded && (
                          <tr className="transactionDetailRow" key={`${transaction.transactionId}-details`}>
                            <td colSpan="7">
                              <dl className="transactionDetailGrid">
                                <div>
                                  <dt>Transaction ID</dt>
                                  <dd>{transaction.transactionId}</dd>
                                </div>
                                <div>
                                  <dt>Correlation ID</dt>
                                  <dd>{transaction.correlationId || "Not supplied"}</dd>
                                </div>
                                <div>
                                  <dt>Original amount</dt>
                                  <dd>{formatAmount(transaction.transactionAmount)}</dd>
                                </div>
                                <div>
                                  <dt>PLN amount</dt>
                                  <dd>{formatPln(transaction.amountPln)}</dd>
                                </div>
                                <div>
                                  <dt>Fraud score</dt>
                                  <dd>{formatScore(transaction.fraudScore)}</dd>
                                </div>
                                <div>
                                  <dt>Risk level</dt>
                                  <dd>{transaction.riskLevel || "UNKNOWN"}</dd>
                                </div>
                                <div>
                                  <dt>Timestamp</dt>
                                  <dd>{formatDateTime(transaction.transactionTimestamp)}</dd>
                                </div>
                              </dl>
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </section>
        </section>

        <aside className="panel decisionRail">
          <p className="eyebrow">Decision</p>
          <h2>Update case</h2>
          {!canUpdateCase && (
            <PermissionNotice
              session={session}
              authority={AUTHORITIES.FRAUD_CASE_UPDATE}
              action="updating a fraud case"
            />
          )}
          {canUpdateCase && <form className="decisionForm" onSubmit={submitDecision}>
            <label>
              Status
              <select value={form.status} onChange={(event) => changeForm({ status: event.target.value })} disabled={actionDisabled}>
                {CASE_STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
              </select>
            </label>
            <label>
              Analyst
              <input value={session.userId || form.analystId} readOnly disabled />
            </label>
            <label>
              Reason
              <textarea rows="5" required value={form.decisionReason} onChange={(event) => changeForm({ decisionReason: event.target.value })} disabled={actionDisabled} />
            </label>
            <label>
              Tags
              <input value={form.tags} onChange={(event) => changeForm({ tags: event.target.value })} disabled={actionDisabled} />
            </label>
            {submitState.error && <p className="formError">{submitState.error}</p>}
            {submitState.success && <p className="formSuccess">{submitState.success}</p>}
            {submitState.warning && <p className="formWarning">{submitState.warning}</p>}
            {detailState === "stale" && <p className="formWarning">Refresh fraud case detail successfully before saving a case decision.</p>}
            <button className="primaryButton" type="submit" disabled={actionDisabled}>
              {submitState.isSubmitting ? "Saving..." : "Save case decision"}
            </button>
          </form>}

          <div className="caseStatePanel">
            <h3>Current decision</h3>
            <dl className="caseStateList">
              <div>
                <dt>Status</dt>
                <dd><span className="statusPill">{fraudCase.status || "UNKNOWN"}</span></dd>
              </div>
              <div>
                <dt>Assigned investigator</dt>
                <dd>{fraudCase.assignedInvestigatorId || fraudCase.analystId || "Unassigned"}</dd>
              </div>
              <div>
                <dt>Decision reason</dt>
                <dd>{fraudCase.decisionReason || fraudCase.closureReason || "Not recorded"}</dd>
              </div>
              <div>
                <dt>Updated</dt>
                <dd>{formatDateTime(fraudCase.updatedAt)}</dd>
              </div>
            </dl>
          </div>
        </aside>
      </div>
    </div>
  );
}

function fraudCaseActionState({ canUpdateCase, detailState }) {
  if (!canUpdateCase) {
    return "Case update unavailable: missing authority";
  }
  if (detailState === "runtime-not-ready") {
    return "Case update unavailable: runtime not ready";
  }
  if (detailState === "stale") {
    return "Case update unavailable: refresh required";
  }
  return "Case update available";
}

function safeDomId(value) {
  const safe = String(value || "unknown").replace(/[^A-Za-z0-9-]+/g, "-").replace(/^-+|-+$/g, "");
  return safe || "unknown";
}

function staleFraudCaseMessage(error) {
  return error
    ? `Refresh fraud case detail successfully before updating the case decision. Last refresh error: ${error}`
    : "Refresh fraud case detail successfully before updating the case decision.";
}

function isCurrentLoad(requestSeq, signal, requestSeqRef, mountedRef) {
  return mountedRef.current && requestSeqRef.current === requestSeq && !signal.aborted;
}

function isCurrentMutation(mutationSeq, caseId, apiClient, signal, mutationSeqRef, mountedRef, currentContextRef) {
  return mountedRef.current
    && mutationSeqRef.current === mutationSeq
    && currentContextRef.current.caseId === caseId
    && currentContextRef.current.apiClient === apiClient
    && !signal.aborted;
}

function formatPln(value) {
  if (value === null || value === undefined) {
    return "N/A";
  }

  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: "PLN"
  }).format(Number(value));
}
