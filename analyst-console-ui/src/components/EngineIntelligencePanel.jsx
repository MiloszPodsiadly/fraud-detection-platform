import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import {
  BoundedEngineResultsView,
  DiagnosticComparisonView,
  VELOCITY_SEVERITY_COPY,
  WarningListView,
  engineDisplayName,
  engineStatusLabel
} from "./EngineIntelligenceDiagnosticView.jsx";
import { EngineIntelligenceFeedbackPanel } from "./EngineIntelligenceFeedbackPanel.jsx";
import { LoadingPanel } from "./LoadingPanel.jsx";

const TEMPORARILY_UNAVAILABLE = "Engine intelligence is temporarily unavailable.";
const NOT_PROJECTED = "Engine intelligence is not available for this transaction.";
const NOT_PROJECTED_HELPER = "This may happen for older transactions or periods when diagnostic emission was disabled.";
const NON_AVAILABLE_STATES = new Set(["not-projected", "unauthorized", "not-found", "unavailable"]);

export function EngineIntelligencePanel({
  transactionId,
  getEngineIntelligence,
  submitEngineIntelligenceFeedback,
  canSubmitFeedback = false,
  className = ""
}) {
  const [state, setState] = useState({
    isLoading: Boolean(transactionId && getEngineIntelligence),
    result: null,
    error: false
  });
  const requestSeqRef = useRef(0);
  const headingId = `engine-intelligence-heading-${safeDomId(transactionId)}`;

  useEffect(() => {
    if (!transactionId || typeof getEngineIntelligence !== "function") {
      setState({ isLoading: false, result: { state: "unavailable" }, error: true });
      return undefined;
    }

    const abortController = new AbortController();
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setState({ isLoading: true, result: null, error: false });

    getEngineIntelligence(transactionId, { signal: abortController.signal })
      .then((result) => {
        if (requestSeqRef.current === requestSeq && !abortController.signal.aborted) {
          setState({ isLoading: false, result: normalizePanelResult(result), error: false });
        }
      })
      .catch((error) => {
        if (requestSeqRef.current === requestSeq && !abortController.signal.aborted && !isAbortError(error)) {
          setState({ isLoading: false, result: { state: "unavailable" }, error: true });
        }
      });

    return () => {
      abortController.abort();
      requestSeqRef.current += 1;
    };
  }, [getEngineIntelligence, transactionId]);

  const sectionClassName = ["subPanel", "engineIntelligencePanel", className].filter(Boolean).join(" ");

  return (
    <section className={sectionClassName} data-testid="engine-intelligence-panel" aria-labelledby={headingId}>
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Engine diagnostics</p>
          <h2 id={headingId}>Engine intelligence</h2>
          <p className="sectionCopy">Diagnostic engine output for this transaction.</p>
          <p className="sectionCopy">Diagnostic only. Operational statuses and disagreement are investigation context.</p>
          <p className="sectionCopy">{VELOCITY_SEVERITY_COPY}</p>
        </div>
      </div>

      {state.isLoading && <LoadingPanel label="Loading engine intelligence..." />}
      {!state.isLoading && state.error && <EngineIntelligenceNotice message={TEMPORARILY_UNAVAILABLE} />}
      {!state.isLoading && !state.error && <EngineIntelligenceContent result={state.result} />}
      {!state.isLoading && !state.error && typeof submitEngineIntelligenceFeedback === "function" && shouldRenderFeedback(state.result) && (
        <EngineIntelligenceFeedbackPanel
          transactionId={transactionId}
          engineIntelligenceAvailable={state.result?.state === "available"}
          submitEngineIntelligenceFeedback={submitEngineIntelligenceFeedback}
          canSubmitFeedback={canSubmitFeedback}
          disabled={state.result?.state === "unauthorized" || state.result?.state === "not-found"}
        />
      )}
    </section>
  );
}

function shouldRenderFeedback(result) {
  return ["available", "not-projected"].includes(result?.state);
}

function EngineIntelligenceContent({ result }) {
  if (result?.state === "not-projected") {
    return (
      <EngineIntelligenceNotice message={NOT_PROJECTED}>
        <span>{NOT_PROJECTED_HELPER}</span>
      </EngineIntelligenceNotice>
    );
  }

  if (result?.state === "unauthorized") {
    return <EngineIntelligenceNotice message="Engine intelligence access denied." />;
  }

  if (result?.state === "not-found") {
    return <EngineIntelligenceNotice message="Engine intelligence transaction was not found." />;
  }

  if (result?.state !== "available") {
    return <EngineIntelligenceNotice message={TEMPORARILY_UNAVAILABLE} />;
  }

  return (
    <div className="engineIntelligenceBody">
      <ComparisonSection comparison={result.comparison} />
      <EngineResultList engines={result.engines} />
      <DiagnosticSignalList signals={result.diagnosticSignals} />
      <EngineIntelligenceWarningList warnings={result.warnings} />
    </div>
  );
}

function ComparisonSection({ comparison }) {
  return (
    <DiagnosticComparisonView
      comparison={comparison}
      sectionClassName="engineIntelligenceBlock"
      headingId="engine-intelligence-comparison-heading"
      title="Diagnostic comparison"
      FieldComponent={Field}
      comparedEnginesText={(engineIds) => engineIds.join(" vs ")}
    />
  );
}

function EngineResultList({ engines }) {
  return (
    <BoundedEngineResultsView
      engines={engines}
      sectionClassName="engineIntelligenceBlock"
      cardClassName="engineIntelligenceCard"
      headingId="engine-intelligence-engines-heading"
      title="Engine results"
      emptyCopy="No engine results"
      FieldComponent={Field}
      ReasonCodesComponent={ReasonCodes}
      showDisplayName
      hideRiskForOperationalStatus
    />
  );
}

function DiagnosticSignalList({ signals }) {
  return (
    <section className="engineIntelligenceBlock" aria-labelledby="engine-intelligence-signals-heading">
      <h3 id="engine-intelligence-signals-heading">Diagnostic signals</h3>
      {signals.length === 0 ? <p className="muted">No diagnostic signals</p> : (
        <div className="engineIntelligenceCards">
          {signals.map((signal, index) => (
            <article className="engineIntelligenceCard" key={`${signal.signalCategory}-${signal.reasonCodes.join("-")}-${index}`}>
              <div className="engineIntelligenceCardHeader">
                <strong>{signal.signalCategory}</strong>
                {signal.engineType && <span>{engineDisplayName(signal)} / {signal.engineId}</span>}
              </div>
              <dl>
                {signal.engineStatus && <Field label="Status" value={engineStatusLabel(signal.engineStatus)} />}
                <Field label="Score bucket" value={signal.scoreBucket} />
                {signal.riskLevel && signal.signalCategory !== "OPERATIONAL_SIGNAL" && <Field label="Risk level" value={signal.riskLevel} />}
              </dl>
              <ReasonCodes reasonCodes={signal.reasonCodes} />
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function EngineIntelligenceWarningList({ warnings }) {
  return (
    <WarningListView
      warnings={warnings}
      sectionClassName="engineIntelligenceBlock"
      cardClassName="engineIntelligenceCard"
      headingId="engine-intelligence-warnings-heading"
      title="Warnings"
      emptyCopy="No warnings"
      FieldComponent={Field}
      compact
    />
  );
}

function Field({ label, value }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value || "Not reported"}</dd>
    </div>
  );
}

function ReasonCodes({ reasonCodes }) {
  return (
    <div className="engineIntelligenceReasons">
      <span>Reason codes</span>
      {reasonCodes.length === 0 ? <p>No reason codes</p> : (
        <ul>
          {reasonCodes.map((reasonCode) => <li key={reasonCode}>{reasonCode}</li>)}
        </ul>
      )}
    </div>
  );
}

function EngineIntelligenceNotice({ message, children }) {
  return (
    <div className="engineIntelligenceNotice" role="status">
      <p>{message}</p>
      {children}
    </div>
  );
}

function normalizePanelResult(result) {
  if (!result || typeof result !== "object") {
    return { state: "unavailable" };
  }
  if (result.state !== "available") {
    return NON_AVAILABLE_STATES.has(result.state) ? result : { state: "unavailable" };
  }
  if (!isValidAvailablePanelResult(result)) {
    return { state: "unavailable" };
  }
  return result;
}

function isValidAvailablePanelResult(result) {
  return Boolean(
    result.comparison
      && typeof result.comparison === "object"
      && result.comparison.comparisonType
      && Array.isArray(result.comparison.comparedEngineIds)
      && result.comparison.agreementStatus
      && result.comparison.riskMismatchStatus
      && result.comparison.scoreDeltaBucket
      && Array.isArray(result.engines)
      && Array.isArray(result.diagnosticSignals)
      && Array.isArray(result.warnings)
  );
}

function safeDomId(value) {
  const safe = String(value || "unknown").replace(/[^A-Za-z0-9-]+/g, "-").replace(/^-+|-+$/g, "");
  return safe || "unknown";
}
