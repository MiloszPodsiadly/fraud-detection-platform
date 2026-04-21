import { useState } from "react";
import { submitAnalystDecision } from "../api/alertsApi.js";
import { formatScore } from "../utils/format.js";
import { RiskBadge } from "./RiskBadge.jsx";

const DECISIONS = [
  "CONFIRMED_FRAUD",
  "MARKED_LEGITIMATE",
  "REQUIRE_MORE_EVIDENCE",
  "ESCALATED"
];

export function AnalystDecisionForm({ alertId, summary, disabled, onSubmitted }) {
  const [analystId, setAnalystId] = useState("analyst.local");
  const [decision, setDecision] = useState("REQUIRE_MORE_EVIDENCE");
  const [decisionReason, setDecisionReason] = useState("");
  const [tags, setTags] = useState("manual-review");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);

  async function handleSubmit(event) {
    event.preventDefault();
    setIsSubmitting(true);
    setError("");
    setResult(null);

    try {
      const response = await submitAnalystDecision(alertId, {
        analystId,
        decision,
        decisionReason: decisionReason.trim(),
        tags: tags.split(",").map((tag) => tag.trim()).filter(Boolean),
        decisionMetadata: {
          source: "analyst-console-ui"
        }
      });
      setResult(response);
      await onSubmitted();
    } catch (apiError) {
      setError(apiError.message);
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <form className="decisionForm" onSubmit={handleSubmit}>
      <div>
        <p className="eyebrow">Decision</p>
        <h2>Case action</h2>
      </div>

      {summary && (
        <div className="caseMiniCard">
          <RiskBadge riskLevel={summary.riskLevel} />
          <strong>{summary.transactionId}</strong>
          <span>{formatScore(summary.fraudScore)} fraud score</span>
        </div>
      )}

      <label>
        Analyst ID
        <input value={analystId} onChange={(event) => setAnalystId(event.target.value)} disabled={disabled || isSubmitting} />
      </label>

      <label>
        Decision
        <select value={decision} onChange={(event) => setDecision(event.target.value)} disabled={disabled || isSubmitting}>
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
          disabled={disabled || isSubmitting}
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
          disabled={disabled || isSubmitting}
          placeholder="manual-review, account-takeover"
        />
      </label>

      {error && <p className="formError">{error}</p>}
      {result && <p className="formSuccess">Decision saved. Status: {result.resultingStatus}</p>}

      <button className="primaryButton" type="submit" disabled={disabled || isSubmitting}>
        {isSubmitting ? "Submitting..." : "Submit decision"}
      </button>
    </form>
  );
}
