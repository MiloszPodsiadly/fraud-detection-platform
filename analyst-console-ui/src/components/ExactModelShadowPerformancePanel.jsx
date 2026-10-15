import { useEffect, useState } from "react";
import { LoadingPanel } from "./LoadingPanel.jsx";

const EMPTY_SELECTOR = Object.freeze({
  modelName: "",
  modelVersion: "",
  featureContractVersion: "",
  modelArtifactSha256: "",
  fromInclusive: "",
  toInclusive: ""
});

export function ExactModelShadowPerformancePanel({
  state,
  canRead,
  modelCard
}) {
  const [draft, setDraft] = useState(EMPTY_SELECTOR);

  useEffect(() => {
    const subject = modelCard?.subject;
    const window = modelCard?.evaluationSummary?.evaluationWindow;
    if (!subject) return;
    setDraft((current) => current.modelArtifactSha256 ? current : {
      modelName: subject.modelName || "",
      modelVersion: subject.modelVersion || "",
      featureContractVersion: subject.featureContractVersion || "",
      modelArtifactSha256: subject.modelArtifactSha256 || "",
      fromInclusive: window?.fromInclusive || "",
      toInclusive: window?.toInclusive || ""
    });
  }, [modelCard]);

  function update(field, value) {
    setDraft((current) => ({ ...current, [field]: value }));
  }

  function submit(event) {
    event.preventDefault();
    state.load(Object.fromEntries(
      Object.entries(draft).map(([field, value]) => [field, value.trim()])
    ));
  }

  return (
    <section className="panel modelCardPanel" id="exact-model-shadow-performance">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Exact artifact evidence</p>
          <h2>Model Shadow Performance</h2>
        </div>
      </div>
      {canRead === false && <State title="Access denied" message="Required permission: model-shadow-performance:read." />}
      {canRead !== false && (
        <form className="filterGrid" onSubmit={submit}>
          <SelectorField label="Model name" value={draft.modelName} onChange={(value) => update("modelName", value)} />
          <SelectorField label="Model version" value={draft.modelVersion} onChange={(value) => update("modelVersion", value)} />
          <SelectorField label="Feature contract" value={draft.featureContractVersion} onChange={(value) => update("featureContractVersion", value)} />
          <SelectorField label="Artifact SHA-256" value={draft.modelArtifactSha256} onChange={(value) => update("modelArtifactSha256", value)} pattern="[0-9a-f]{64}" />
          <SelectorField label="Window from (UTC)" value={draft.fromInclusive} onChange={(value) => update("fromInclusive", value)} required={false} />
          <SelectorField label="Window to (UTC)" value={draft.toInclusive} onChange={(value) => update("toInclusive", value)} required={false} />
          <div className="filterActions">
            <button className="primaryButton" type="submit" disabled={state.isLoading}>Load exact artifact</button>
          </div>
        </form>
      )}
      {canRead !== false && state.isLoading && <LoadingPanel label="Loading exact model evidence..." />}
      {canRead !== false && !state.isLoading && state.error && <ErrorState error={state.error} onRetry={state.refresh} />}
      {canRead !== false && !state.isLoading && !state.error && state.summary && <Summary summary={state.summary} />}
    </section>
  );
}

function SelectorField({ label, value, onChange, required = true, pattern }) {
  return (
    <label className="filterField">
      <span>{label}</span>
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        required={required}
        pattern={pattern}
        autoComplete="off"
      />
    </label>
  );
}

function Summary({ summary }) {
  const evaluation = summary.evaluationSummary;
  const subject = evaluation.evaluationSubject;
  const population = evaluation.population;
  const disagreement = evaluation.supportedMetrics.rulesVsMlDisagreement;
  return (
    <div className="shadowPerformanceStack">
      <div className="stateBanner shadowPerformanceBanner" role="status">
        {summary.coverage.status}. Diagnostic evidence only; production authority: NONE.
      </div>
      <div className="shadowPerformanceGrid">
        <EvidenceSection title="Exact model identity" rows={[
          ["Model", subject.modelName],
          ["Version", subject.modelVersion],
          ["Feature contract", subject.featureContractVersion],
          ["Artifact SHA-256", subject.modelArtifactSha256]
        ]} />
        <EvidenceSection title="Evaluated population" rows={[
          ["Records considered", population.recordsConsidered],
          ["Records evaluated", population.recordsEvaluated],
          ["Positive class", evaluation.classBalance.positiveClassCount],
          ["Negative class", evaluation.classBalance.negativeClassCount],
          ["Source truncated", summary.coverage.sourceTruncated ? "YES" : "NO"]
        ]} />
        <EvidenceSection title="Rules vs ML" rows={[
          ["Records compared", disagreement.recordsCompared],
          ["Disagreement rate", metric(disagreement.disagreementRate)],
          ["ML high, Rules low", disagreement.categoryCounts.ML_HIGH_RULES_LOW],
          ["Rules high, ML low", disagreement.categoryCounts.RULES_HIGH_ML_LOW],
          ["Both high", disagreement.categoryCounts.BOTH_HIGH],
          ["Both low", disagreement.categoryCounts.BOTH_LOW]
        ]} />
        <EvidenceSection title="Confirmed fraud by category" rows={Object.entries(
          disagreement.analystLabeledFraudByCategory
        )} />
        <EvidenceSection title="Operational diagnostics" rows={[
          ["ML latency p95", unavailable(summary.operationalDiagnostics.mlLatencyP95)],
          ["Fallback rate", unavailable(summary.operationalDiagnostics.fallbackRate)],
          ["Reason codes", unavailable(summary.operationalDiagnostics.mlReasonCodeDistribution)]
        ]} />
      </div>
    </div>
  );
}

function EvidenceSection({ title, rows }) {
  return (
    <section className="shadowPerformanceSubpanel">
      <h3>{title}</h3>
      <dl className="shadowPerformanceDefinitionList">
        {rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{String(value)}</dd></div>)}
      </dl>
    </section>
  );
}

function ErrorState({ error, onRetry }) {
  const states = {
    400: ["Invalid selector", "The exact artifact selector is malformed."],
    401: ["Session required", "You are not authenticated."],
    403: ["Access denied", "Required permission: model-shadow-performance:read."],
    404: ["Artifact not found", "No artifact matches the complete selector."],
    503: ["Artifact unavailable", "The selected artifact is corrupt or unavailable."]
  };
  const [title, message] = error?.state === "invalid-response"
    ? ["Unsupported contract", "The response does not match the exact-artifact contract."]
    : states[error?.status] || ["Unable to load evidence", "The exact-artifact API could not be reached."];
  return <State title={title} message={message} onRetry={onRetry} />;
}

function State({ title, message, onRetry }) {
  return (
    <div className="statePanel warningPanel" role="alert">
      <h3>{title}</h3><p>{message}</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Retry</button>}
    </div>
  );
}

function metric(value) {
  return value.available ? `${(value.value * 100).toFixed(2)}%` : `UNAVAILABLE: ${value.reason}`;
}

function unavailable(value) {
  return value.available ? "AVAILABLE" : `UNAVAILABLE: ${value.reason}`;
}
