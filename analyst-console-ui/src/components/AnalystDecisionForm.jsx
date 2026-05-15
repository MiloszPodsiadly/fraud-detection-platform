import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { AUTHORITIES } from "../auth/session.js";
import { createIdempotencyKey } from "../utils/idempotencyKey.js";
import { formatScore } from "../utils/format.js";
import { PermissionNotice } from "./SecurityStatePanels.jsx";
import { RiskBadge } from "./RiskBadge.jsx";

const DECISIONS = [
  "CONFIRMED_FRAUD",
  "MARKED_LEGITIMATE",
  "REQUIRE_MORE_EVIDENCE",
  "ESCALATED"
];
const SECURE_REQUEST_ID_ERROR = "Secure request identifier could not be generated. Reload the page and try again.";

export function AnalystDecisionForm({ alertId, summary, session, apiClient, canSubmit, disabled, onSubmitted }) {
  const [decision, setDecision] = useState("REQUIRE_MORE_EVIDENCE");
  const [decisionReason, setDecisionReason] = useState("");
  const [tags, setTags] = useState("manual-review");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);
  const [postSubmitWarning, setPostSubmitWarning] = useState("");
  const mountedRef = useRef(false);
  const submitSeqRef = useRef(0);
  const submitAbortRef = useRef(null);
  const currentContextRef = useRef({ alertId, apiClient });
  const actionDisabled = disabled || isSubmitting || !canSubmit;
  const analystId = session?.userId || "";

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      submitAbortRef.current?.abort();
      submitSeqRef.current += 1;
    };
  }, []);

  useEffect(() => {
    currentContextRef.current = { alertId, apiClient };
    submitAbortRef.current?.abort();
    submitAbortRef.current = null;
    submitSeqRef.current += 1;
    setIsSubmitting(false);
    setError("");
    setResult(null);
    setPostSubmitWarning("");
  }, [alertId, apiClient]);

  async function handleSubmit(event) {
    event.preventDefault();
    if (actionDisabled) {
      return;
    }
    submitAbortRef.current?.abort();
    const abortController = new AbortController();
    submitAbortRef.current = abortController;
    const submitSeq = submitSeqRef.current + 1;
    submitSeqRef.current = submitSeq;
    const currentAlertId = alertId;
    const currentApiClient = apiClient;
    setIsSubmitting(true);
    setError("");
    setResult(null);
    setPostSubmitWarning("");

    try {
      let idempotencyKey;
      try {
        idempotencyKey = createIdempotencyKey("alert-decision", alertId);
      } catch {
        setError(SECURE_REQUEST_ID_ERROR);
        return;
      }
      // Abort only protects the frontend request lifecycle; an unsafe request that reached the server may still complete.
      const response = await currentApiClient.submitAnalystDecision(currentAlertId, {
        analystId,
        decision,
        decisionReason: decisionReason.trim(),
        tags: tags.split(",").map((tag) => tag.trim()).filter(Boolean),
        decisionMetadata: {
          source: "analyst-console-ui"
        }
      }, {
        idempotencyKey,
        signal: abortController.signal
      });
      if (!isCurrentSubmit(submitSeq, currentAlertId, currentApiClient, abortController.signal, submitSeqRef, mountedRef, currentContextRef)) {
        return;
      }
      setResult(response);
      let refreshFailed = false;
      try {
        const postSubmitResult = await onSubmitted?.();
        refreshFailed = Boolean(postSubmitResult?.status && postSubmitResult.status !== "loaded");
      } catch {
        refreshFailed = true;
      }
      if (isCurrentSubmit(submitSeq, currentAlertId, currentApiClient, abortController.signal, submitSeqRef, mountedRef, currentContextRef) && refreshFailed) {
        setPostSubmitWarning("Decision saved. Dashboard refresh failed; retry refresh.");
      }
    } catch (apiError) {
      if (!isCurrentSubmit(submitSeq, currentAlertId, currentApiClient, abortController.signal, submitSeqRef, mountedRef, currentContextRef)) {
        return;
      }
      if (isAbortError(apiError)) {
        setIsSubmitting(false);
        return;
      }
      setError(apiError.message);
    } finally {
      if (submitSeqRef.current === submitSeq) {
        setIsSubmitting(false);
        if (submitAbortRef.current === abortController) {
          submitAbortRef.current = null;
        }
      }
    }
  }

  return (
    <form className="decisionForm" onSubmit={handleSubmit}>
      <div>
        <p className="eyebrow">Decision</p>
        <h2>Case action</h2>
      </div>

      {!canSubmit && (
        <PermissionNotice
          session={session}
          authority={AUTHORITIES.ALERT_DECISION_SUBMIT}
          action="submitting an analyst decision"
        />
      )}

      {summary && (
        <div className="caseMiniCard">
          <RiskBadge riskLevel={summary.riskLevel} />
          <strong>{summary.transactionId}</strong>
          <span>{formatScore(summary.fraudScore)} fraud score</span>
        </div>
      )}

      <label>
        Analyst ID
        <input value={analystId} readOnly disabled />
      </label>

      <label>
        Decision
        <select value={decision} onChange={(event) => setDecision(event.target.value)} disabled={actionDisabled}>
          {DECISIONS.map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
      </label>

      <label>
        Reason
        <textarea
          value={decisionReason}
          onChange={(event) => setDecisionReason(event.target.value)}
          disabled={actionDisabled}
          placeholder="Document the evidence behind this decision."
          required
          minLength="3"
          rows="5"
        />
      </label>

      <label>
        Tags
        <input
          value={tags}
          onChange={(event) => setTags(event.target.value)}
          disabled={actionDisabled}
          placeholder="manual-review, account-takeover"
        />
      </label>

      {error && <p className="formError">{error}</p>}
      {result && <p className="formSuccess">Decision saved. Status: {result.resultingStatus}</p>}
      {postSubmitWarning && <p className="formWarning">{postSubmitWarning}</p>}

      <button className="primaryButton" type="submit" disabled={actionDisabled}>
        {isSubmitting ? "Submitting..." : "Submit decision"}
      </button>
    </form>
  );
}

function isCurrentSubmit(submitSeq, alertId, apiClient, signal, submitSeqRef, mountedRef, currentContextRef) {
  return mountedRef.current
    && submitSeqRef.current === submitSeq
    && currentContextRef.current.alertId === alertId
    && currentContextRef.current.apiClient === apiClient
    && !signal.aborted;
}
