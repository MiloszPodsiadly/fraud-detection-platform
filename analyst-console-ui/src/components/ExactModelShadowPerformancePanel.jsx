import { useEffect, useRef, useState } from "react";
import { LoadingPanel } from "./LoadingPanel.jsx";

const EMPTY_SELECTOR = Object.freeze({
  modelName: "",
  modelVersion: "",
  featureContractVersion: "",
  modelArtifactSha256: "",
  fromInclusive: "",
  toInclusive: ""
});
const MODEL_CARD_ORIGIN = "MODEL_CARD";
const MANUAL_ORIGIN = "MANUAL";

export function ExactModelShadowPerformancePanel({
  state,
  canRead,
  modelCard
}) {
  const [draft, setDraft] = useState(EMPTY_SELECTOR);
  const lastPrefill = useRef(null);
  const draftOrigin = useRef(MANUAL_ORIGIN);
  const loadedOrigin = useRef(null);
  const subject = modelCard?.subject;
  const hasSubject = subject !== null && subject !== undefined;
  const window = modelCard?.evaluationSummary?.evaluationWindow;
  const modelName = subject?.modelName || "";
  const modelVersion = subject?.modelVersion || "";
  const featureContractVersion = subject?.featureContractVersion || "";
  const modelArtifactSha256 = subject?.modelArtifactSha256 || "";
  const fromInclusive = window?.fromInclusive || "";
  const toInclusive = window?.toInclusive || "";

  useEffect(() => {
    if (!hasSubject) {
      if (lastPrefill.current !== null) {
        if (loadedOrigin.current === MODEL_CARD_ORIGIN) state.clear?.();
        loadedOrigin.current = null;
        setDraft((current) => {
          if (draftOrigin.current !== MODEL_CARD_ORIGIN) return current;
          draftOrigin.current = MANUAL_ORIGIN;
          return EMPTY_SELECTOR;
        });
        lastPrefill.current = null;
      }
      return;
    }
    const nextPrefill = {
      modelName,
      modelVersion,
      featureContractVersion,
      modelArtifactSha256,
      fromInclusive,
      toInclusive
    };
    if (selectorsEqual(nextPrefill, lastPrefill.current)) return;
    if (loadedOrigin.current === MODEL_CARD_ORIGIN) state.clear?.();
    loadedOrigin.current = null;
    const mayReplace = lastPrefill.current === null || draftOrigin.current === MODEL_CARD_ORIGIN;
    lastPrefill.current = nextPrefill;
    setDraft((current) => {
      if (!mayReplace) return current;
      draftOrigin.current = MODEL_CARD_ORIGIN;
      return nextPrefill;
    });
  }, [
    featureContractVersion,
    fromInclusive,
    hasSubject,
    modelArtifactSha256,
    modelName,
    modelVersion,
    state.clear,
    toInclusive
  ]);

  useEffect(() => {
    if (canRead === false) {
      loadedOrigin.current = null;
      state.clear?.();
    }
  }, [canRead, state.clear]);

  function update(field, value) {
    if (draft[field] === value) return;
    draftOrigin.current = MANUAL_ORIGIN;
    loadedOrigin.current = null;
    state.clear?.();
    setDraft((current) => ({ ...current, [field]: value }));
  }

  function submit(event) {
    event.preventDefault();
    loadedOrigin.current = draftOrigin.current;
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
  const source = evaluation.sourceDataset;
  const population = evaluation.population;
  const metrics = evaluation.supportedMetrics.mlPredictionMetrics;
  const disagreement = evaluation.supportedMetrics.rulesVsMlDisagreement;
  const sourceTruncated = summary.coverage.sourcePopulationStatus === "BOUNDED_SOURCE_TRUNCATED";
  return (
    <div className="shadowPerformanceStack">
      <div className="stateBanner shadowPerformanceBanner" role="status">
        Returned cohort: {summary.coverage.returnedCohortStatus}. Source population: {summary.coverage.sourcePopulationStatus}.
        Diagnostic evidence only; production authority: NONE.
      </div>
      {sourceTruncated && (
        <div className="statePanel warningPanel" role="status">
          <h3>Bounded source truncated</h3>
          <p>The returned cohort does not establish completeness of the source population.</p>
        </div>
      )}
      <ProvenanceExplanation sourceEvaluation={summary.sourceEvaluation} />
      <div className="shadowPerformanceGrid">
        <EvidenceSection title="Report identity" rows={[
          ["Schema", summary.schemaVersion],
          ["Report type", summary.reportType],
          ["Generated at", summary.generatedAt],
          ["Population type", summary.populationType]
        ]} />
        <EvidenceSection title="Exact model identity" rows={[
          ["Model", subject.modelName],
          ["Version", subject.modelVersion],
          ["Feature contract", subject.featureContractVersion],
          ["Artifact SHA-256", subject.modelArtifactSha256],
          ["Identity completeness", subject.identityCompleteness]
        ]} />
        <EvidenceSection title="Evaluation window" rows={[
          ["Time basis", evaluation.evaluationWindow.timeBasis],
          ["From", display(evaluation.evaluationWindow.fromInclusive)],
          ["To", display(evaluation.evaluationWindow.toInclusive)],
          ["Metric basis", evaluation.metricBasis]
        ]} />
        <EvidenceSection title="Cohort eligibility" rows={[
          ["Policy", summary.cohortEligibility.policy],
          ["Eligible labels", summary.cohortEligibility.eligibleLabels.join(", ")],
          ["Exact model prediction evidence required", summary.cohortEligibility.requiresExactModelPredictionEvidence ? "YES" : "NO"],
          ["Maximum records", summary.cohortEligibility.maximumRecords]
        ]} />
        <EvidenceSection title="Evaluation provenance" rows={[
          ["Source report", summary.sourceEvaluation.reportType],
          ["Artifact set", summary.sourceEvaluation.artifactSetVersion],
          ["Report generated", summary.sourceEvaluation.reportGeneratedAt],
          ["Manifest SHA-256", summary.sourceEvaluation.manifestSha256],
          ["Runtime artifact verification", summary.sourceEvaluation.artifactIntegrityVerification],
          ["Source dataset verification at publication", summary.sourceEvaluation.sourceDatasetVerificationLevel],
          ["External attestation", summary.sourceEvaluation.externalAttestation],
          ["Immutable storage guarantee", summary.sourceEvaluation.immutableStorageGuarantee]
        ]} />
        <EvidenceSection title="Evaluated population" rows={[
          ["Records considered", population.recordsConsidered],
          ["Records evaluated", population.recordsEvaluated],
          ["Records with prediction evidence", population.recordsWithPredictionEvidence],
          ["Positive class", evaluation.classBalance.positiveClassCount],
          ["Negative class", evaluation.classBalance.negativeClassCount],
          ["Returned cohort", summary.coverage.returnedCohortStatus],
          ["Source population", summary.coverage.sourcePopulationStatus]
        ]} />
        <EvidenceSection title="Population exclusions" rows={[
          ["Identity mismatch", population.recordsExcludedIdentityMismatch],
          ["Source identity mismatch", population.recordsExcludedSourceIdentityMismatch],
          ["Missing prediction evidence", population.recordsExcludedMissingPredictionEvidence],
          ["Unexpected evidence loss", population.recordsExcludedUnexpectedMissingPredictionEvidence],
          ["Invalid prediction evidence", population.recordsExcludedInvalidPredictionEvidence]
        ]} />
        <EvidenceSection title="Source-data quality" rows={[
          ["Dataset version", source.datasetVersion],
          ["Dataset SHA-256", source.sha256],
          ["Raw rows read", source.rawRowsRead],
          ["Records returned", source.recordsReturned],
          ["Unresolved excluded", source.excludedUnresolvedCount],
          ["Governance review excluded", source.excludedGovernanceReviewCount],
          ["Missing required fields skipped", source.skippedMissingRequiredFieldCount],
          ["Invalid source rows skipped", source.skippedInvalidSourceRecordCount]
        ]} />
        <EvidenceSection title="Prediction metrics" rows={[
          ["Availability", availability(metrics)],
          ["Precision", metric(metrics.precision)],
          ["Recall", metric(metrics.recall)],
          ["True positive rate", metric(metrics.truePositiveRate)],
          ["False positive rate", metric(metrics.falsePositiveRate)],
          ["False negative rate", metric(metrics.falseNegativeRate)],
          ["Classification policy", display(metrics.classificationPolicy)]
        ]} />
        <EvidenceSection title="Confusion matrix" rows={[
          ["True positive", display(metrics.truePositive)],
          ["False positive", display(metrics.falsePositive)],
          ["True negative", display(metrics.trueNegative)],
          ["False negative", display(metrics.falseNegative)]
        ]} />
        <EvidenceSection title="Rules vs ML" rows={[
          ["Availability", availability(disagreement)],
          ["Classification policy", display(disagreement.classificationPolicy)],
          ["Records with exact ML evidence", display(disagreement.recordsWithExactMlEvidence)],
          ["Records compared", disagreement.recordsCompared],
          ["Disagreement rate", metric(disagreement.disagreementRate)],
          ["Rules evidence unavailable", disagreement.recordsExcludedRulesEvidenceUnavailable],
          ["Analyst fraud records compared", disagreement.analystLabeledFraudRecordsCompared],
          ["Analyst fraud rules evidence unavailable", disagreement.analystLabeledFraudRecordsExcludedRulesEvidenceUnavailable],
          ["ML high, Rules low", disagreement.categoryCounts.ML_HIGH_RULES_LOW],
          ["Rules high, ML low", disagreement.categoryCounts.RULES_HIGH_ML_LOW],
          ["Both high", disagreement.categoryCounts.BOTH_HIGH],
          ["Both low", disagreement.categoryCounts.BOTH_LOW]
        ]} />
        <EvidenceSection title="Analyst-labeled fraud by disagreement category" rows={Object.entries(
          disagreement.analystLabeledFraudByCategory
        )} />
        <EvidenceSection title="Operational diagnostics" rows={[
          ["ML latency p95", unavailable(summary.operationalDiagnostics.mlLatencyP95)],
          ["Fallback rate", unavailable(summary.operationalDiagnostics.fallbackRate)],
          ["Reason codes", unavailable(summary.operationalDiagnostics.mlReasonCodeDistribution)]
        ]} />
        <EvidenceSection title="Governance boundary" rows={[
          ["Diagnostic only", summary.governance.diagnosticOnly ? "YES" : "NO"],
          ["Runtime decision authority", summary.governance.runtimeDecisionAuthority],
          ["Promotion authority", summary.governance.promotionAuthority],
          ["Threshold authority", summary.governance.thresholdChangeAuthority],
          ["Payment authority", summary.governance.paymentAuthorizationAuthority],
          ["Workflow authority", summary.governance.workflowAuthority]
        ]} />
        <EvidenceList title="Warnings" items={evaluation.warnings} empty="No evaluation warnings reported." />
        <EvidenceList title="Limitations" items={evaluation.limitations} empty="No limitations reported." />
      </div>
      <RankingMetrics precisionAtK={metrics.precisionAtK} recallAtK={metrics.recallAtK} />
    </div>
  );
}

function ProvenanceExplanation({ sourceEvaluation }) {
  const sourceMeaning = sourceEvaluation.sourceDatasetVerificationLevel === "VERIFIED_AGAINST_SOURCE_BYTES"
    ? "Source dataset bytes were verified at the upstream evaluation validation and publication boundary."
    : "The upstream artifact contains a source dataset identity claim. Source dataset bytes were not independently checked during exact-model Shadow Performance generation.";
  return (
    <div>
      <p className="sectionCopy">{sourceMeaning}</p>
      <p className="sectionCopy">
        The Java runtime verifies the local published artifact and manifest. It does not re-read the upstream
        evaluation artifact. The local manifest SHA-256 is an integrity check, not an external attestation or
        immutable-storage guarantee. External attestation remains {sourceEvaluation.externalAttestation}; immutable
        storage remains {sourceEvaluation.immutableStorageGuarantee}.
      </p>
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

function EvidenceList({ title, items, empty }) {
  return (
    <section className="shadowPerformanceSubpanel">
      <h3>{title}</h3>
      {items.length > 0
        ? <ul className="shadowPerformanceList">{items.map((item) => <li key={item}>{item}</li>)}</ul>
        : <p className="sectionCopy">{empty}</p>}
    </section>
  );
}

function RankingMetrics({ precisionAtK, recallAtK }) {
  const keys = Object.keys(precisionAtK).sort((left, right) => Number(left) - Number(right));
  return (
    <section className="shadowPerformanceSubpanel">
      <h3>Ranking metrics</h3>
      <div className="tableShell">
        <table className="dataTable">
          <thead><tr><th scope="col">K</th><th scope="col">Actual K</th><th scope="col">Precision at K</th><th scope="col">Recall at K</th></tr></thead>
          <tbody>{keys.map((key) => (
            <tr key={key}>
              <th scope="row">{precisionAtK[key].requestedK}</th>
              <td>{precisionAtK[key].actualK}</td>
              <td>{metric(precisionAtK[key].value)}</td>
              <td>{metric(recallAtK[key].value)}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
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
  return value?.available ? `${(value.value * 100).toFixed(2)}%` : `UNAVAILABLE: ${value?.reason}`;
}

function unavailable(value) {
  return value.available ? "AVAILABLE" : `UNAVAILABLE: ${value.reason}`;
}

function availability(value) {
  return value.available ? "AVAILABLE" : `UNAVAILABLE: ${value.reason}`;
}

function display(value) {
  return value === null || value === undefined || value === "" ? "UNAVAILABLE" : value;
}

function selectorsEqual(left, right) {
  return left !== null && right !== null
    && Object.keys(EMPTY_SELECTOR).every((field) => left[field] === right[field]);
}
