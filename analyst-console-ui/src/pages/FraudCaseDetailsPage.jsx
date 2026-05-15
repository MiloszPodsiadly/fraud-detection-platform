import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { AUTHORITIES, hasAuthority } from "../auth/session.js";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { RiskBadge } from "../components/RiskBadge.jsx";
import { PermissionNotice } from "../components/SecurityStatePanels.jsx";
import { formatAmount, formatDateTime, formatScore } from "../utils/format.js";

const CASE_STATUSES = ["IN_REVIEW", "CONFIRMED_FRAUD", "FALSE_POSITIVE", "CLOSED"];

export function FraudCaseDetailsPage({ caseId, session, apiClient, onBack, onCaseUpdated }) {
  const [fraudCase, setFraudCase] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");
  const [expandedTransactionId, setExpandedTransactionId] = useState(null);
  const [form, setForm] = useState({
    status: "IN_REVIEW",
    analystId: "",
    decisionReason: "",
    tags: "rapid-transfer, grouped-low-risk"
  });
  const [decisionIdempotencyKey, setDecisionIdempotencyKey] = useState("");
  const [submitState, setSubmitState] = useState({ isSubmitting: false, error: "", success: "" });
  const loadRequestSeqRef = useRef(0);
  const mutationSeqRef = useRef(0);
  const loadAbortRef = useRef(null);
  const mutationAbortRef = useRef(null);
  const mountedRef = useRef(false);
  const currentContextRef = useRef({ caseId, apiClient });

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
    setSubmitState({ isSubmitting: false, error: "", success: "" });
  }, [apiClient, caseId]);

  const loadCase = useCallback(async () => {
    loadAbortRef.current?.abort();
    const abortController = new AbortController();
    loadAbortRef.current = abortController;
    const requestSeq = loadRequestSeqRef.current + 1;
    loadRequestSeqRef.current = requestSeq;
    const currentCaseId = caseId;
    const currentApiClient = apiClient;
    setIsLoading(true);
    setError("");
    try {
      const nextCase = await currentApiClient.getFraudCase(currentCaseId, { signal: abortController.signal });
      if (!isCurrentLoad(requestSeq, abortController.signal, loadRequestSeqRef, mountedRef)) {
        return;
      }
      setFraudCase(nextCase);
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
    } finally {
      if (loadRequestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (loadAbortRef.current === abortController) {
          loadAbortRef.current = null;
        }
      }
    }
  }, [apiClient, caseId, session.userId]);

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

  if (error) {
    return <ErrorState message={error} onRetry={loadCase} />;
  }

  const canUpdateCase = hasAuthority(session, AUTHORITIES.FRAUD_CASE_UPDATE);
  const actionDisabled = submitState.isSubmitting || !canUpdateCase;

  function changeForm(patch) {
    setForm((current) => ({ ...current, ...patch }));
    setDecisionIdempotencyKey("");
    setSubmitState((current) => ({ ...current, error: "", success: "" }));
  }

  async function submitDecision(event) {
    event.preventDefault();
    if (!canUpdateCase) {
      return;
    }
    const idempotencyKey = decisionIdempotencyKey || createDecisionIdempotencyKey(caseId);
    mutationAbortRef.current?.abort();
    const abortController = new AbortController();
    mutationAbortRef.current = abortController;
    const mutationSeq = mutationSeqRef.current + 1;
    mutationSeqRef.current = mutationSeq;
    const currentCaseId = caseId;
    const currentApiClient = apiClient;
    setDecisionIdempotencyKey(idempotencyKey);
    setSubmitState({ isSubmitting: true, error: "", success: "" });
    try {
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
      setSubmitState({ isSubmitting: false, error: "", success: "Case decision saved." });
      onCaseUpdated?.();
    } catch (apiError) {
      if (!isCurrentMutation(mutationSeq, currentCaseId, currentApiClient, abortController.signal, mutationSeqRef, mountedRef, currentContextRef)) {
        return;
      }
      if (isAbortError(apiError)) {
        setSubmitState({ isSubmitting: false, error: "", success: "" });
        return;
      }
      setSubmitState({ isSubmitting: false, error: apiError.message, success: "" });
    } finally {
      if (mutationSeqRef.current === mutationSeq && mutationAbortRef.current === abortController) {
        mutationAbortRef.current = null;
      }
    }
  }

  return (
    <div className="pageEnter">
      <button className="backButton" type="button" onClick={onBack}>Back to dashboard</button>
      <div className="detailsLayout">
        <section className="panel detailsMain">
          <div className="detailsHeader">
            <div>
              <p className="eyebrow">Fraud case</p>
              <h2>{fraudCase.suspicionType}</h2>
              <p className="sectionCopy">{fraudCase.reason}</p>
            </div>
            <span className="statusPill">{fraudCase.status}</span>
          </div>

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
          <form className="decisionForm" onSubmit={submitDecision}>
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
            <button className="primaryButton" type="submit" disabled={actionDisabled}>
              {submitState.isSubmitting ? "Saving..." : "Save case decision"}
            </button>
          </form>

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

function createDecisionIdempotencyKey(caseId) {
  const random = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `fraud-case-update-${caseId}-${random}`;
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
