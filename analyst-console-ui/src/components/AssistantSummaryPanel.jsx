import { formatDateTime, formatScore } from "../utils/format.js";

export function AssistantSummaryPanel({ summary, isLoading, error, onRetry }) {
  return (
    <section className="subPanel assistantPanel">
      <div className="assistantHeader">
        <div>
          <p className="eyebrow">AI assistant</p>
          <h3>Case summary</h3>
        </div>
        {summary?.generatedAt && <span className="muted">{formatDateTime(summary.generatedAt)}</span>}
      </div>

      {isLoading && <p className="muted">Generating case summary...</p>}
      {!isLoading && error && (
        <div className="assistantError">
          <span>{error}</span>
          <button className="secondaryButton" type="button" onClick={onRetry}>Retry</button>
        </div>
      )}
      {!isLoading && !error && summary && (
        <div className="assistantContent">
          {summary.supportingEvidence?.llmNarrative && (
            <div className="assistantNarrative">
              <strong>{summary.supportingEvidence.llmNarrative.overview}</strong>
              <div className="assistantObservationList">
                {(summary.supportingEvidence.llmNarrative.keyObservations || []).map((observation) => (
                  <span key={observation}>{observation}</span>
                ))}
              </div>
              {summary.supportingEvidence.llmNarrative.uncertainty && (
                <p>{summary.supportingEvidence.llmNarrative.uncertainty}</p>
              )}
              <small>{summary.supportingEvidence.llmNarrative.modelName}</small>
            </div>
          )}

          <div className="assistantAction">
            <strong>{summary.recommendedNextAction?.title || "Review case"}</strong>
            <span>{summary.recommendedNextAction?.rationale}</span>
          </div>

          <div className="assistantReasonGrid">
            {(summary.mainFraudReasons || []).map((reason) => (
              <article className="assistantReason" key={reason.reasonCode}>
                <div>
                  <strong>{reason.analystLabel || reason.reasonCode}</strong>
                  <span>{reason.reasonCode}</span>
                </div>
                {typeof reason.contribution === "number" && (
                  <b>{formatScore(reason.contribution)}</b>
                )}
                <p>{reason.explanation}</p>
              </article>
            ))}
          </div>

          <div className="assistantSteps">
            {(summary.recommendedNextAction?.suggestedReviewSteps || []).map((step) => (
              <span key={step}>{step}</span>
            ))}
          </div>

          <dl className="assistantFacts">
            <div>
              <dt>Recent count</dt>
              <dd>{summary.customerRecentBehaviorSummary?.recentTransactionCount ?? "Unknown"}</dd>
            </div>
            <div>
              <dt>Velocity</dt>
              <dd>{summary.customerRecentBehaviorSummary?.transactionVelocityPerMinute ?? "Unknown"}</dd>
            </div>
            <div>
              <dt>Device novelty</dt>
              <dd>{formatBoolean(summary.customerRecentBehaviorSummary?.deviceNovelty)}</dd>
            </div>
            <div>
              <dt>Country mismatch</dt>
              <dd>{formatBoolean(summary.customerRecentBehaviorSummary?.countryMismatch)}</dd>
            </div>
          </dl>
        </div>
      )}
    </section>
  );
}

function formatBoolean(value) {
  if (value === true) {
    return "Yes";
  }
  if (value === false) {
    return "No";
  }
  return "Unknown";
}
