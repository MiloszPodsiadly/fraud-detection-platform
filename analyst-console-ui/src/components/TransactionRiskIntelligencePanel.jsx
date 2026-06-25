import { useState } from "react";
import { formatDateTime, formatScore } from "../utils/format.js";
import { AnalystRecommendationPanel } from "./AnalystRecommendationPanel.jsx";
import { useScoredTransactionDetail } from "../transactions/useScoredTransactionDetail.js";
import { useFraudFeedback } from "../transactions/useFraudFeedback.js";
import { transactionRiskIntelligencePanelId } from "../transactions/transactionRiskIntelligencePanelId.js";

const STATUS_COPY = {
  AVAILABLE: "Engine Intelligence is available for this transaction.",
  ABSENT: "No Engine Intelligence projection exists for this scored transaction. This can happen for older transactions or when Engine Intelligence was not emitted.",
  UNAVAILABLE: "The scored transaction was found, but Engine Intelligence could not be safely read. The transaction detail remains available, but diagnostics are unavailable.",
  DEGRADED: "Engine Intelligence is available with limitations. Review the warnings section for diagnostic constraints."
};

const ENGINE_STATUS_COPY = {
  AVAILABLE: "Engine result available",
  UNAVAILABLE: "Engine result unavailable",
  TIMEOUT: "Engine timed out",
  DEGRADED: "Engine result degraded",
  NOT_APPLICABLE: "Engine not applicable"
};

export function TransactionRiskIntelligencePanel({
  transactionId,
  enabled = true,
  apiClient,
  panelId
}) {
  const shouldFetch = Boolean(enabled && transactionId);
  const safePanelId = panelId || transactionRiskIntelligencePanelId(transactionId);
  const titleId = `${safePanelId}-title`;
  const { detail, isLoading, error } = useScoredTransactionDetail({
    transactionId,
    enabled: shouldFetch,
    apiClient
  });

  return (
    <section className="transactionRiskIntelligencePanel" id={safePanelId} aria-labelledby={titleId}>
      <div className="detailSection transactionRiskIntelligenceHeader">
        <p className="eyebrow">Transaction diagnostics</p>
        <h3 id={titleId}>Transaction Risk Intelligence</h3>
        <DiagnosticBoundaryBanner />
      </div>

      {!enabled && <p className="emptyStateMessage">Transaction risk intelligence is not available without transaction read permission.</p>}
      {enabled && !transactionId && <p className="emptyStateMessage">No transaction selected.</p>}
      {shouldFetch && isLoading && <p className="loadingText">Loading transaction risk intelligence...</p>}
      {shouldFetch && !isLoading && error && (
        <p className="formError">{errorMessage(error)}</p>
      )}
      {shouldFetch && !isLoading && !error && detail && (
        <TransactionRiskIntelligenceDetail detail={detail} apiClient={apiClient} />
      )}
    </section>
  );
}

function TransactionRiskIntelligenceDetail({ detail, apiClient }) {
  const intelligence = detail.engineIntelligence;
  return (
    <>
      <section className="detailSection" aria-label="Transaction Summary">
        <h4>Transaction Summary</h4>
        <dl>
          <Field label="Transaction ID" value={detail.transactionId} />
          <Field label="Correlation ID" value={detail.correlationId} />
          <Field label="Transaction time" value={formatDateTime(detail.transactionTimestamp)} />
          <Field label="Scored at" value={formatDateTime(detail.scoredAt)} />
          <Field label="Fraud score" value={formatScore(detail.fraudScore)} />
          <Field label="Risk level" value={detail.riskLevel || "Not available"} />
          <Field label="Alert recommendation flag" value={detail.alertRecommended ? "Present" : "Not present"} />
          <Field label="Reason codes" value={listText(detail.reasonCodes)} />
        </dl>
        <p className="sectionCopy">alertRecommended is a scored transaction field, not a final payment decision.</p>
      </section>

      <section className={`detailSection transactionRiskStatus transactionRiskStatus${statusClass(intelligence.status)}`} aria-label="Engine Intelligence Status">
        <h4>Engine Intelligence Status</h4>
        <dl>
          <Field label="Status" value={intelligence.status} />
          <Field label="Contract version" value={nullableText(intelligence.contractVersion)} />
          <Field label="Generated at" value={formatDateTime(intelligence.generatedAt)} />
        </dl>
        <p className="sectionCopy">{STATUS_COPY[intelligence.status] || STATUS_COPY.UNAVAILABLE}</p>
      </section>

      <ComparisonSummary comparison={intelligence.comparison} />
      <AnalystRecommendationPanel recommendation={detail.analystRecommendation} />
      <AnalystFeedbackSection transactionId={detail.transactionId} apiClient={apiClient} />
      <EngineResults engines={intelligence.engines} />
      <DiagnosticSignals diagnosticSignals={intelligence.diagnosticSignals} />
      <Warnings warnings={intelligence.warnings} />
    </>
  );
}

const FEEDBACK_OPTIONS = [
  {
    feedbackLabel: "CONFIRMED_FRAUD",
    analystDecision: "MARKED_FRAUD",
    label: "Mark as confirmed fraud",
    reasonCode: "ANALYST_CONFIRMED_FRAUD",
    copy: "Confirmed fraud records the analyst review outcome only. It does not automatically block the transaction or create a case."
  },
  {
    feedbackLabel: "CONFIRMED_LEGITIMATE",
    analystDecision: "MARKED_LEGITIMATE",
    label: "Mark as confirmed legitimate",
    reasonCode: "ANALYST_CONFIRMED_LEGITIMATE",
    copy: "Confirmed legitimate records the analyst review outcome only. It is not payment approval or transaction authorization."
  },
  {
    feedbackLabel: "INCONCLUSIVE",
    analystDecision: "MARKED_INCONCLUSIVE",
    label: "Mark as inconclusive",
    reasonCode: "ANALYST_INCONCLUSIVE",
    copy: "This feedback is not a positive or negative training label."
  },
  {
    feedbackLabel: "NEEDS_MORE_INFO",
    analystDecision: "REQUESTED_MORE_INFO",
    label: "Needs more information",
    reasonCode: "ANALYST_NEEDS_MORE_INFO",
    copy: "This feedback is not a positive or negative training label."
  }
];

function AnalystFeedbackSection({ transactionId, apiClient }) {
  const {
    feedback,
    isLoading,
    error,
    submitState,
    submitError,
    submit
  } = useFraudFeedback({ transactionId, enabled: Boolean(transactionId), apiClient });
  const [selectedLabel, setSelectedLabel] = useState("CONFIRMED_FRAUD");
  const [notes, setNotes] = useState("");
  const selectedOption = FEEDBACK_OPTIONS.find((option) => option.feedbackLabel === selectedLabel) || FEEDBACK_OPTIONS[0];

  const onSubmit = (event) => {
    event.preventDefault();
    submit({
      analystDecision: selectedOption.analystDecision,
      feedbackLabel: selectedOption.feedbackLabel,
      decisionReasonCodes: [selectedOption.reasonCode],
      notes
    });
  };

  return (
    <section className="detailSection analystFeedbackSection" aria-label="Analyst Feedback">
      <h4>Analyst Feedback</h4>
      <p className="sectionCopy">
        Feedback records analyst review outcome only. It does not authorize payment, approve, decline, block,
        change scoring, update recommendations, create cases, trigger workflow, train models, promote models, or
        change thresholds.
      </p>
      {isLoading && <p className="loadingText">Loading analyst feedback...</p>}
      {!isLoading && error && <p className="formError">{feedbackErrorMessage(error)}</p>}
      {!isLoading && !error && feedback && <ExistingFeedback feedback={feedback} />}
      {!isLoading && !error && !feedback && (
        <form className="analystFeedbackForm" onSubmit={onSubmit}>
          <fieldset disabled={submitState === "submitting"}>
            <legend>Review outcome</legend>
            <div className="feedbackOptionGrid">
              {FEEDBACK_OPTIONS.map((option) => (
                <label className="feedbackOption" key={option.feedbackLabel}>
                  <input
                    type="radio"
                    name="fraudFeedbackLabel"
                    value={option.feedbackLabel}
                    checked={selectedLabel === option.feedbackLabel}
                    onChange={() => setSelectedLabel(option.feedbackLabel)}
                  />
                  <span>{option.label}</span>
                </label>
              ))}
            </div>
          </fieldset>
          <p className="sectionCopy">{selectedOption.copy}</p>
          <label className="feedbackNotesField">
            <span>Notes</span>
            <textarea
              maxLength={500}
              value={notes}
              onChange={(event) => setNotes(event.target.value)}
              rows={3}
            />
          </label>
          {submitState === "validation-error" && <p className="formError">Feedback request is invalid.</p>}
          {submitState === "duplicate" && <p className="formError">Feedback already exists for this transaction.</p>}
          {submitState === "error" && <p className="formError">{feedbackErrorMessage(submitError)}</p>}
          {submitState === "success" && <p className="formSuccess">Feedback recorded.</p>}
          <button type="submit" disabled={submitState === "submitting"}>
            {submitState === "submitting" ? "Recording..." : "Record feedback"}
          </button>
        </form>
      )}
    </section>
  );
}

function ExistingFeedback({ feedback }) {
  return (
    <article className="summaryCard">
      <h5>Feedback recorded</h5>
      <dl>
        <Field label="Feedback label" value={feedback.feedbackLabel} />
        <Field label="Analyst decision" value={feedback.analystDecision} />
        <Field label="Label source" value={feedback.labelSource} />
        <Field label="Status" value={feedback.feedbackStatus} />
        <Field label="Created at" value={formatDateTime(feedback.createdAt)} />
        <Field label="Reason codes" value={listText(feedback.decisionReasonCodes)} />
      </dl>
      <p className="sectionCopy">One active feedback record is already present for this transaction.</p>
    </article>
  );
}

function ComparisonSummary({ comparison }) {
  return (
    <section className="detailSection" aria-label="Projected Comparison">
      <h4>Projected Comparison</h4>
      {!comparison && <p className="sectionCopy">Projected engine comparison is not available for this transaction.</p>}
      {comparison && (
        <>
          <dl>
            <Field label="Agreement status" value={comparison.agreementStatus} />
            <Field label="Risk mismatch status" value={comparison.riskMismatchStatus} />
            <Field label="Score delta bucket" value={comparison.scoreDeltaBucket} />
          </dl>
          <p className="sectionCopy">Agreement status describes projected engine comparison only.</p>
          <p className="sectionCopy">Risk mismatch describes projected engine risk variance only.</p>
          <p className="sectionCopy">Score delta bucket is diagnostic and not a threshold recommendation.</p>
        </>
      )}
    </section>
  );
}

function EngineResults({ engines }) {
  return (
    <section className="detailSection" aria-label="Engine Results">
      <h4>Engine Results</h4>
      {engines.length === 0 && <p className="sectionCopy">No bounded engine results are available in the projected diagnostics.</p>}
      {engines.map((engine) => (
        <article className="summaryCard" key={`${engine.engineId}-${engine.engineType}`}>
          <h5>{engine.engineId}</h5>
          <dl>
            <Field label="Engine type" value={engine.engineType} />
            <Field label="Status" value={ENGINE_STATUS_COPY[engine.status] || engine.status} />
            <Field label="Risk level" value={engine.riskLevel || "Not available"} />
            <Field label="Score bucket" value={engine.scoreBucket || "Not available"} />
            <Field label="Reason codes" value={listText(engine.reasonCodes)} />
          </dl>
        </article>
      ))}
    </section>
  );
}

function DiagnosticSignals({ diagnosticSignals }) {
  return (
    <section className="detailSection" aria-label="Diagnostic Signals">
      <h4>Diagnostic Signals</h4>
      <p className="sectionCopy">Displayed as diagnostics only.</p>
      {diagnosticSignals.length === 0 && <p className="sectionCopy">No bounded diagnostic signals are available.</p>}
      {diagnosticSignals.map((signal, index) => (
        <article className="summaryCard" key={`${signal.engineId}-${signal.reasonCode || index}`}>
          <dl>
            <Field label="Engine ID" value={signal.engineId} />
            <Field label="Engine type" value={signal.engineType} />
            <Field label="Engine status" value={ENGINE_STATUS_COPY[signal.engineStatus] || signal.engineStatus} />
            <Field label="Signal category" value={signal.signalCategory} />
            <Field label="Risk level" value={signal.riskLevel || "Not available"} />
            <Field label="Score bucket" value={signal.scoreBucket || "Not available"} />
            <Field label="Reason code" value={signal.reasonCode || "Not available"} />
          </dl>
        </article>
      ))}
    </section>
  );
}

function Warnings({ warnings }) {
  return (
    <section className="detailSection" aria-label="Warnings and Limitations">
      <h4>Warnings and Limitations</h4>
      <p className="sectionCopy">Warnings describe limitations in the projected diagnostic data.</p>
      <p className="sectionCopy">Warnings are not operational instructions.</p>
      {warnings.length === 0 && <p className="sectionCopy">No warnings are present in the projected diagnostics.</p>}
      {warnings.map((warning) => (
        <article className="summaryCard" key={warning.warningCode}>
          <dl>
            <Field label="Warning code" value={warning.warningCode} />
            <Field label="Count" value={String(warning.count)} />
          </dl>
        </article>
      ))}
    </section>
  );
}

function DiagnosticBoundaryBanner() {
  return (
    <section className="diagnosticBoundaryBanner" aria-label="Diagnostic Boundary">
      <h4>Diagnostic Boundary</h4>
      <p>
        Transaction Risk Intelligence is a read-only diagnostic view. It does not approve, does not decline,
        does not block, does not authorize payment, does not create a case, does not promote models,
        does not change thresholds, and does not trigger workflow.
      </p>
    </section>
  );
}

function Field({ label, value }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value || "Not available"}</dd>
    </>
  );
}

function listText(values) {
  return Array.isArray(values) && values.length > 0 ? values.join(", ") : "None";
}

function nullableText(value) {
  return value === null || value === undefined ? "Not available" : String(value);
}

function statusClass(status) {
  return typeof status === "string" ? status.charAt(0) + status.slice(1).toLowerCase() : "Unavailable";
}

function errorMessage(error) {
  if (error?.message === "INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE") {
    return "The transaction risk intelligence response is malformed or missing required safety fields.";
  }
  if (error?.name === "AbortError" || error?.code === "ABORT_ERR") {
    return "Transaction risk intelligence loading was interrupted before diagnostics could be displayed.";
  }
  if (error?.status === 400) {
    return "This transaction identifier is invalid.";
  }
  if (error?.status === 401 || error?.status === 403) {
    return "You do not have permission to read this transaction risk intelligence.";
  }
  if (error?.status === 404) {
    return "Scored transaction not found.";
  }
  return "Transaction risk intelligence is temporarily unavailable.";
}

function feedbackErrorMessage(error) {
  if (error?.status === 400) {
    return "Feedback request failed validation.";
  }
  if (error?.status === 401 || error?.status === 403) {
    return "You do not have permission to read or record analyst feedback.";
  }
  if (error?.status === 404) {
    return "Scored transaction or feedback was not found.";
  }
  if (error?.status === 409) {
    return "Feedback already exists for this transaction.";
  }
  if (error?.status === 503) {
    return "Analyst feedback is temporarily unavailable.";
  }
  return "Analyst feedback could not be loaded or recorded.";
}
