import { useMemo, useState } from "react";
import { createIdempotencyKey } from "../utils/idempotencyKey.js";

const USEFULNESS_OPTIONS = [
  ["HELPFUL", "Helpful"],
  ["SOMEWHAT_HELPFUL", "Somewhat helpful"],
  ["NOT_HELPFUL", "Not helpful"],
  ["NOT_SURE", "Not sure"]
];

const ACCURACY_OPTIONS = [
  ["SIGNALS_LOOK_CORRECT", "Signals looked correct"],
  ["SIGNALS_LOOK_PARTIALLY_CORRECT", "Signals looked partially correct"],
  ["SIGNALS_LOOK_INCORRECT", "Signals looked incorrect"],
  ["NOT_ENOUGH_INFORMATION", "Not enough information"],
  ["OPERATIONAL_ISSUE_AFFECTED_REVIEW", "Operational issue affected review"]
];

const SECURE_REQUEST_ID_ERROR = "Feedback could not be saved. Please try again.";

export function EngineIntelligenceFeedbackPanel({
  transactionId,
  engineIntelligenceAvailable,
  submitEngineIntelligenceFeedback,
  canSubmitFeedback = false,
  disabled = false
}) {
  const [usefulness, setUsefulness] = useState("");
  const [accuracyAssessment, setAccuracyAssessment] = useState("");
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const [state, setState] = useState({ isSubmitting: false, success: "", error: "" });
  const formId = useMemo(() => `engine-intelligence-feedback-${safeDomId(transactionId)}`, [transactionId]);
  const actionDisabled = disabled
    || state.isSubmitting
    || !canSubmitFeedback
    || typeof submitEngineIntelligenceFeedback !== "function"
    || !usefulness
    || !accuracyAssessment;

  function changeUsefulness(value) {
    setUsefulness(value);
    setIdempotencyKey("");
    setState({ isSubmitting: false, success: "", error: "" });
  }

  function changeAccuracy(value) {
    setAccuracyAssessment(value);
    setIdempotencyKey("");
    setState({ isSubmitting: false, success: "", error: "" });
  }

  async function submitFeedback(event) {
    event.preventDefault();
    if (actionDisabled) {
      return;
    }
    let nextIdempotencyKey = idempotencyKey;
    try {
      nextIdempotencyKey = nextIdempotencyKey || createIdempotencyKey("engine-intelligence-feedback");
    } catch {
      setState({ isSubmitting: false, success: "", error: SECURE_REQUEST_ID_ERROR });
      return;
    }
    setIdempotencyKey(nextIdempotencyKey);
    setState({ isSubmitting: true, success: "", error: "" });
    const result = await submitEngineIntelligenceFeedback(transactionId, {
      feedbackType: feedbackTypeFor({ engineIntelligenceAvailable, usefulness, accuracyAssessment }),
      usefulness,
      accuracyAssessment,
      engineIntelligenceAvailable: engineIntelligenceAvailable === true,
      selectedReasonCodes: []
    }, { idempotencyKey: nextIdempotencyKey });

    if (result?.state === "saved") {
      setIdempotencyKey("");
      setState({ isSubmitting: false, success: "Feedback saved.", error: "" });
      return;
    }
    setState({ isSubmitting: false, success: "", error: "Feedback could not be saved. Please try again." });
  }

  return (
    <section className="engineIntelligenceFeedbackPanel" aria-labelledby={`${formId}-heading`}>
      <div className="panelHeader compactPanelHeader">
        <div>
          <p className="eyebrow">Analyst feedback</p>
          <h3 id={`${formId}-heading`}>Was this engine intelligence useful?</h3>
        </div>
      </div>
      {!canSubmitFeedback && (
        <p className="formWarning">Feedback requires engine intelligence feedback write authority.</p>
      )}
      <form className="engineIntelligenceFeedbackForm" onSubmit={submitFeedback}>
        <fieldset disabled={state.isSubmitting || disabled || !canSubmitFeedback}>
          <legend>Was this engine intelligence useful?</legend>
          <div className="segmentedOptions">
            {USEFULNESS_OPTIONS.map(([value, label]) => (
              <label key={value}>
                <input
                  type="radio"
                  name={`${formId}-usefulness`}
                  value={value}
                  checked={usefulness === value}
                  onChange={() => changeUsefulness(value)}
                />
                <span>{label}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset disabled={state.isSubmitting || disabled || !canSubmitFeedback}>
          <legend>What best describes your feedback?</legend>
          <div className="segmentedOptions verticalOptions">
            {ACCURACY_OPTIONS.map(([value, label]) => (
              <label key={value}>
                <input
                  type="radio"
                  name={`${formId}-accuracy`}
                  value={value}
                  checked={accuracyAssessment === value}
                  onChange={() => changeAccuracy(value)}
                />
                <span>{label}</span>
              </label>
            ))}
          </div>
        </fieldset>
        {state.success && <p className="formSuccess">{state.success}</p>}
        {state.error && <p className="formError">{state.error}</p>}
        <button className="secondaryButton" type="submit" disabled={actionDisabled}>
          {state.isSubmitting ? "Submitting..." : "Submit feedback"}
        </button>
      </form>
    </section>
  );
}

function feedbackTypeFor({ engineIntelligenceAvailable, usefulness, accuracyAssessment }) {
  if (engineIntelligenceAvailable !== true) {
    return "MISSING_INTELLIGENCE_REVIEW";
  }
  if (accuracyAssessment === "OPERATIONAL_ISSUE_AFFECTED_REVIEW") {
    return "OPERATIONAL_STATUS_REVIEW";
  }
  if (usefulness === "NOT_HELPFUL") {
    return "ENGINE_DISAGREEMENT_REVIEW";
  }
  return "ENGINE_INTELLIGENCE_USEFULNESS";
}

function safeDomId(value) {
  const safe = String(value || "unknown").replace(/[^A-Za-z0-9-]+/g, "-").replace(/^-+|-+$/g, "");
  return safe || "unknown";
}
