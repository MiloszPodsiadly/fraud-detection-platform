import { useEffect, useState } from "react";
import { getFraudCase, updateFraudCase } from "../api/alertsApi.js";
import { AUTHORITIES, hasAuthority } from "../auth/session.js";
import { ErrorState } from "../components/ErrorState.jsx";
import { LoadingPanel } from "../components/LoadingPanel.jsx";
import { RiskBadge } from "../components/RiskBadge.jsx";
import { PermissionNotice } from "../components/SecurityStatePanels.jsx";
import { formatAmount, formatDateTime, formatScore } from "../utils/format.js";

const CASE_STATUSES = ["IN_REVIEW", "CONFIRMED_FRAUD", "FALSE_POSITIVE", "CLOSED"];

export function FraudCaseDetailsPage({ caseId, session, onBack, onCaseUpdated }) {
  const [fraudCase, setFraudCase] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");
  const [form, setForm] = useState({
    status: "IN_REVIEW",
    analystId: "",
    decisionReason: "",
    tags: "rapid-transfer, grouped-low-risk"
  });
  const [submitState, setSubmitState] = useState({ isSubmitting: false, error: "", success: "" });

  useEffect(() => {
    loadCase();
  }, [caseId]);

  async function loadCase() {
    setIsLoading(true);
    setError("");
    try {
      const nextCase = await getFraudCase(caseId);
      setFraudCase(nextCase);
      setForm({
        status: nextCase.status === "OPEN" ? "IN_REVIEW" : nextCase.status,
        analystId: session.userId || nextCase.analystId || "",
        decisionReason: nextCase.decisionReason || "",
        tags: (nextCase.decisionTags || ["rapid-transfer", "grouped-low-risk"]).join(", ")
      });
    } catch (apiError) {
      setError(apiError.message);
    } finally {
      setIsLoading(false);
    }
  }

  async function submitDecision(event) {
    event.preventDefault();
    if (!canUpdateCase) {
      return;
    }
    setSubmitState({ isSubmitting: true, error: "", success: "" });
    try {
      const updated = await updateFraudCase(caseId, {
        status: form.status,
        analystId: session.userId,
        decisionReason: form.decisionReason,
        tags: form.tags.split(",").map((tag) => tag.trim()).filter(Boolean)
      });
      setFraudCase(updated);
      setSubmitState({ isSubmitting: false, error: "", success: "Fraud case updated." });
      onCaseUpdated();
    } catch (apiError) {
      setSubmitState({ isSubmitting: false, error: apiError.message, success: "" });
    }
  }

  if (isLoading) {
    return <LoadingPanel label="Loading fraud case..." />;
  }

  if (error) {
    return <ErrorState message={error} onRetry={loadCase} />;
  }

  const canUpdateCase = hasAuthority(session, AUTHORITIES.FRAUD_CASE_UPDATE);
  const actionDisabled = submitState.isSubmitting || !canUpdateCase;

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
                  </tr>
                </thead>
                <tbody>
                  {(fraudCase.transactions || []).map((transaction) => (
                    <tr key={transaction.transactionId}>
                      <td><RiskBadge riskLevel={transaction.riskLevel} /></td>
                      <td>{transaction.transactionId}</td>
                      <td>{formatAmount(transaction.transactionAmount)}</td>
                      <td>{formatPln(transaction.amountPln)}</td>
                      <td>{formatDateTime(transaction.transactionTimestamp)}</td>
                      <td className="numericCell">{formatScore(transaction.fraudScore)}</td>
                    </tr>
                  ))}
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
              <select value={form.status} onChange={(event) => setForm((current) => ({ ...current, status: event.target.value }))} disabled={actionDisabled}>
                {CASE_STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
              </select>
            </label>
            <label>
              Analyst
              <input value={session.userId || form.analystId} readOnly disabled />
            </label>
            <label>
              Reason
              <textarea rows="5" required value={form.decisionReason} onChange={(event) => setForm((current) => ({ ...current, decisionReason: event.target.value }))} disabled={actionDisabled} />
            </label>
            <label>
              Tags
              <input value={form.tags} onChange={(event) => setForm((current) => ({ ...current, tags: event.target.value }))} disabled={actionDisabled} />
            </label>
            {submitState.error && <p className="formError">{submitState.error}</p>}
            {submitState.success && <p className="formSuccess">{submitState.success}</p>}
            <button className="primaryButton" type="submit" disabled={actionDisabled}>
              {submitState.isSubmitting ? "Saving..." : "Save case decision"}
            </button>
          </form>
        </aside>
      </div>
    </div>
  );
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
