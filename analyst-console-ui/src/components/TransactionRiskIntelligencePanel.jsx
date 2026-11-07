import { useState } from "react";
import { formatDateTime, formatScore } from "../utils/format.js";
import { AnalystRecommendationPanel } from "./AnalystRecommendationPanel.jsx";
import {
  BoundedEngineResultsView,
  DiagnosticComparisonView,
  VELOCITY_SEVERITY_COPY,
  WarningListView,
  engineStatusLabel,
  listText
} from "./EngineIntelligenceDiagnosticView.jsx";
import { useScoredTransactionDetail } from "../transactions/useScoredTransactionDetail.js";
import { useFraudFeedback } from "../transactions/useFraudFeedback.js";
import { transactionRiskIntelligencePanelId } from "../transactions/transactionRiskIntelligencePanelId.js";
import { merchantDisplayValue } from "../transactions/merchantDisplay.js";
import { alertRecommendationPresentation } from "../transactions/alertRecommendationPresentation.js";

const STATUS_COPY = {
  AVAILABLE: "Engine Intelligence is available for this transaction.",
  ABSENT: "No Engine Intelligence projection exists for this scored transaction. This can happen for older transactions or when Engine Intelligence was not emitted.",
  UNAVAILABLE: "The scored transaction was found, but Engine Intelligence could not be safely read. The transaction detail remains available, but diagnostics are unavailable.",
  DEGRADED: "Engine Intelligence is available with limitations. Review the warnings section for diagnostic constraints."
};

const MERCHANT_DISPLAY_FIELDS = [
  ["Merchant name", "merchantName"],
  ["Merchant ID", "merchantId"],
  ["Merchant category code", "merchantCategoryCode"],
  ["Merchant category", "merchantCategory"],
  ["Channel", "channel"],
  ["Acquiring country", "acquiringCountryCode"]
];

export function TransactionRiskIntelligencePanel({
  transactionId,
  scoringOccurrenceToken,
  enabled = true,
  apiClient,
  panelId
}) {
  const shouldFetch = Boolean(enabled && transactionId);
  const safePanelId = panelId || transactionRiskIntelligencePanelId(transactionId);
  const titleId = `${safePanelId}-title`;
  const expectedOccurrenceToken = typeof scoringOccurrenceToken === "string"
    && /^[0-9a-f]{64}$/.test(scoringOccurrenceToken) ? scoringOccurrenceToken : null;
  const [reviewConfirmation, setReviewConfirmation] = useState(() => ({ token: expectedOccurrenceToken, confirmed: true }));
  const requiresOccurrenceReview = reviewConfirmation.token !== expectedOccurrenceToken || !reviewConfirmation.confirmed;
  const { detail, isLoading, error } = useScoredTransactionDetail({
    transactionId,
    expectedScoringOccurrenceToken: expectedOccurrenceToken,
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
        <TransactionRiskIntelligenceDetail
          detail={detail}
          apiClient={apiClient}
          requiresOccurrenceReview={requiresOccurrenceReview}
          onConfirmOccurrenceReview={() => setReviewConfirmation({ token: expectedOccurrenceToken, confirmed: true })}
        />
      )}
    </section>
  );
}

function TransactionRiskIntelligenceDetail({ detail, apiClient, requiresOccurrenceReview, onConfirmOccurrenceReview }) {
  const intelligence = detail.engineIntelligence;
  const alertRecommendation = alertRecommendationPresentation(detail.alertRecommended);
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
          <Field label="Alert recommendation flag" value={alertRecommendation.label} />
          <Field label="Reason codes" value={listText(detail.reasonCodes)} />
        </dl>
        <p className="sectionCopy">alertRecommended is a scored transaction field, not a final payment decision.</p>
      </section>

      <MerchantInvestigationSection merchantInfo={detail.merchantInfo} />

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
      <AnalystFeedbackSection
        transactionId={detail.transactionId}
        scoringOccurrenceToken={detail.scoringOccurrenceToken}
        apiClient={apiClient}
        requiresOccurrenceReview={requiresOccurrenceReview}
        onConfirmOccurrenceReview={onConfirmOccurrenceReview}
      />
      <EngineResults engines={intelligence.engines} />
      <DiagnosticSignals diagnosticSignals={intelligence.diagnosticSignals} />
      <Warnings warnings={intelligence.warnings} />
    </>
  );
}

function MerchantInvestigationSection({ merchantInfo }) {
  const merchantFields = MERCHANT_DISPLAY_FIELDS.map(([label, key]) => [label, merchantDisplayValue(merchantInfo, key)])
    .filter(([, value]) => value !== null);
  const hasMerchantContext = merchantFields.length > 0;

  return (
    <section className="detailSection" aria-label="Merchant Investigation">
      <h4>Merchant Investigation</h4>
      <p className="sectionCopy">
        Merchant details supplied with this transaction are context only. They do not establish independently verified merchant identity or merchant risk.
      </p>
      {hasMerchantContext ? (
        <dl>
          {merchantFields.map(([label, value]) => (
            <Field key={label} label={label} value={value} />
          ))}
        </dl>
      ) : (
        <p className="emptyStateMessage">No displayable transaction merchant details are available.</p>
      )}
      <p className="sectionCopy">
        Verified Merchant Risk history is not provided by this transaction detail. The current implementation does not connect an independently admitted merchant evidence source. Absence of evidence is not evidence of low risk.
      </p>
    </section>
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

function AnalystFeedbackSection({ transactionId, scoringOccurrenceToken, apiClient, requiresOccurrenceReview, onConfirmOccurrenceReview }) {
  const {
    feedback,
    isLoading,
    error,
    canReadFeedback = true,
    canCreateFeedback = true,
    submitState,
    submitError,
    submit
  } = useFraudFeedback({ transactionId, scoringOccurrenceToken, enabled: Boolean(transactionId), apiClient });
  const [selectedLabel, setSelectedLabel] = useState("CONFIRMED_FRAUD");
  const [notes, setNotes] = useState("");
  const selectedOption = FEEDBACK_OPTIONS.find((option) => option.feedbackLabel === selectedLabel) || FEEDBACK_OPTIONS[0];

  const onSubmit = (event) => {
    event.preventDefault();
    submit({
      analystDecision: selectedOption.analystDecision,
      feedbackLabel: selectedOption.feedbackLabel,
      scoringOccurrenceToken,
      decisionReasonCodes: [selectedOption.reasonCode],
      notes: notes.trim() === "" ? null : notes.trim()
    });
  };
  const feedbackRuntimeAvailable = canReadFeedback && canCreateFeedback;
  const hasScoringOccurrenceToken = typeof scoringOccurrenceToken === "string"
    && /^[0-9a-f]{64}$/.test(scoringOccurrenceToken);

  return (
    <section className="detailSection analystFeedbackSection" aria-label="Analyst Feedback">
      <h4>Analyst Feedback</h4>
      <p className="sectionCopy">
        Feedback records analyst review outcome only. It does not authorize payment, approve, decline, block,
        change scoring, update recommendations, create cases, trigger workflow, train models, promote models, or
        change thresholds.
      </p>
      {!canReadFeedback && <p className="emptyStateMessage">Analyst feedback is not available in this runtime.</p>}
      {canReadFeedback && requiresOccurrenceReview && (
        <div className="summaryCard" role="status">
          <p className="sectionCopy">Scoring changed while this transaction was expanded. Review the current scoring before recording feedback.</p>
          <button className="secondaryButton compactButton" type="button" onClick={onConfirmOccurrenceReview}>
            I reviewed the current scoring
          </button>
        </div>
      )}
      {canReadFeedback && isLoading && <p className="loadingText">Loading analyst feedback...</p>}
      {canReadFeedback && !isLoading && error && <p className="formError">{feedbackErrorMessage(error)}</p>}
      {canReadFeedback && !isLoading && !error && feedback && <ExistingFeedback feedback={feedback} />}
      {canReadFeedback && !canCreateFeedback && !isLoading && !error && !feedback && (
        <p className="emptyStateMessage">
          {hasScoringOccurrenceToken
            ? "Analyst feedback is not available in this runtime."
            : "Refresh transaction details before recording feedback."}
        </p>
      )}
      {feedbackRuntimeAvailable && !requiresOccurrenceReview && !isLoading && !error && !feedback && (
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
          {submitState === "stale-occurrence" && (
            <p className="formError">Scoring changed after review. Refresh transaction details and review the current scoring before retrying.</p>
          )}
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
  const relationCopy = feedback.scoringOccurrenceRelation === "CURRENT"
    ? "This feedback snapshot matches the current scoring occurrence."
    : feedback.scoringOccurrenceRelation === "PRIOR"
      ? "This feedback belongs to a prior scoring occurrence and does not cover the current scoring."
      : "The scoring occurrence for this historical feedback could not be verified.";
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
        <Field label="Notes" value={feedback.notesPresent ? "Present" : "None"} />
      </dl>
      <p className="sectionCopy">{relationCopy}</p>
      <p className="sectionCopy">One active feedback record is already present for this transaction.</p>
    </article>
  );
}

function ComparisonSummary({ comparison }) {
  return (
    <DiagnosticComparisonView
      comparison={comparison}
      sectionClassName="detailSection"
      headingId="transaction-risk-projected-comparison-heading"
      headingLevel={4}
      title="Projected Comparison"
      emptyCopy="Projected engine comparison is not available for this transaction."
      FieldComponent={Field}
      comparedEnginesText={listText}
      includeBoundaryCopy
      ariaLabel="Projected Comparison"
    />
  );
}

function EngineResults({ engines }) {
  return (
    <BoundedEngineResultsView
      engines={engines}
      sectionClassName="detailSection"
      cardClassName="summaryCard"
      headingId="transaction-risk-engine-results-heading"
      headingLevel={4}
      title="Engine Results"
      emptyCopy="No bounded engine results are available in the projected diagnostics."
      FieldComponent={Field}
      ReasonCodesComponent={() => null}
      ariaLabel="Engine Results"
    />
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
            <Field label="Engine status" value={engineStatusLabel(signal.engineStatus)} />
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
    <WarningListView
      warnings={warnings}
      sectionClassName="detailSection"
      cardClassName="summaryCard"
      headingId="transaction-risk-warnings-heading"
      headingLevel={4}
      title="Warnings and Limitations"
      emptyCopy="No warnings are present in the projected diagnostics."
      FieldComponent={Field}
      ariaLabel="Warnings and Limitations"
    />
  );
}

function DiagnosticBoundaryBanner() {
  return (
    <section className="diagnosticBoundaryBanner" aria-label="Diagnostic Boundary">
      <h4>Diagnostic Boundary</h4>
      <p>
        Transaction diagnostics are read-only. Analyst Feedback is a separate bounded review-outcome record.
        It does not approve, decline, block, authorize payment, create cases, trigger workflow, change scoring,
        update recommendations, train models, promote models, or change thresholds.
      </p>
      <p>{VELOCITY_SEVERITY_COPY}</p>
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
