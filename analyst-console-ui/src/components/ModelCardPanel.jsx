import { isValidModelCard } from "../governance/modelCardValidation.js";
import { LoadingPanel } from "./LoadingPanel.jsx";

const REQUIRED_PERMISSION = "model-card:read";
const INVALID_MESSAGE = "The Model Card response uses an unsupported or malformed contract.";

export function ModelCardPanel({ card, isLoading, error, canReadModelCard, onRetry }) {
  const invalid = !isLoading && !error && canReadModelCard === true && card && !isValidModelCard(card);

  return (
    <section className="panel modelCardPanel" id="model-card">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Exact model evidence</p>
          <h2>Model Card</h2>
          <p className="sectionCopy">
            Verified artifact and evaluation evidence only. This card does not approve promotion, deployment, thresholds, payments, or automatic decisions.
          </p>
        </div>
      </div>

      {canReadModelCard === false && (
        <ModelCardState
          title="Access denied"
          message={`You do not have permission to read the Model Card. Required permission: ${REQUIRED_PERMISSION}.`}
        />
      )}
      {canReadModelCard !== false && isLoading && <LoadingPanel label="Loading Model Card..." />}
      {canReadModelCard !== false && !isLoading && error && (
        <ModelCardState {...errorStateFor(error)} onRetry={onRetry} />
      )}
      {invalid && (
        <ModelCardState title="Unsupported Model Card contract" message={INVALID_MESSAGE} canRetry onRetry={onRetry} />
      )}
      {canReadModelCard !== false && !isLoading && !error && card && !invalid && <ModelCardSuccess card={card} />}
    </section>
  );
}

function ModelCardSuccess({ card }) {
  const summary = card.evaluationSummary;
  return (
    <div className="shadowPerformanceStack modelCardStack">
      <div className="stateBanner shadowPerformanceBanner" role="status">
        Promotion state: {displayMachineCode(card.promotionState)}. No production authority is granted.
      </div>
      <div className="shadowPerformanceGrid">
        <ModelCardSection title="Exact model identity">
          <DefinitionList rows={[
            ["Model name", card.subject.modelName],
            ["Model version", card.subject.modelVersion],
            ["Feature contract", card.subject.featureContractVersion],
            ["Artifact SHA-256", card.subject.modelArtifactSha256],
            ["Identity", card.subject.identityCompleteness],
            ["Card generated", displayTimestamp(card.generatedAt)]
          ]} />
        </ModelCardSection>
        <ModelCardSection title="Artifact verification">
          <DefinitionList rows={[
            ["Status", card.modelArtifactVerification.status],
            ["Verified SHA-256", card.modelArtifactVerification.sha256],
            ["Schema", card.schemaVersion],
            ["Card type", card.cardType]
          ]} />
        </ModelCardSection>
        <UsageBoundary boundary={card.verifiedUsageBoundary} />
      </div>
      {card.evaluationEvidence.status === "NOT_AVAILABLE"
        ? <EvaluationUnavailable reason={card.evaluationEvidence.reason} />
        : <EvaluationAvailable evidence={card.evaluationEvidence} summary={summary} />}
    </div>
  );
}

function UsageBoundary({ boundary }) {
  return (
    <ModelCardSection title="Verified usage boundary">
      <DefinitionList rows={[
        ["Allowed modes", boundary.allowedModes.join(", ")],
        ["Runtime decision authority", boundary.runtimeDecisionAuthority],
        ["Promotion authority", boundary.promotionAuthority],
        ["Threshold authority", boundary.thresholdChangeAuthority],
        ["Payment authority", boundary.paymentAuthorizationAuthority],
        ["Workflow authority", boundary.workflowAuthority]
      ]} />
    </ModelCardSection>
  );
}

function EvaluationUnavailable({ reason }) {
  return (
    <div className="statePanel warningPanel" role="status">
      <h3>No model evaluation evidence</h3>
      <p>The exact artifact remains identified and verified, but model evaluation is not configured.</p>
      <p>Reason: {displayMachineCode(reason)}</p>
    </div>
  );
}

function EvaluationAvailable({ evidence, summary }) {
  const population = summary.population;
  const excluded = excludedCount(population);
  const partialCoverage = excluded > 0 || summary.warnings.includes("MODEL_EVALUATION_PARTIAL_COVERAGE");
  const metrics = summary.supportedMetrics.mlPredictionMetrics;
  return (
    <>
      <div className="shadowPerformanceGrid">
        <ModelCardSection title="Evaluation provenance">
          <DefinitionList rows={[
            ["Report type", evidence.reportType],
            ["Artifact set", evidence.artifactSetVersion],
            ["Report generated", displayTimestamp(evidence.reportGeneratedAt)],
            ["Manifest SHA-256", evidence.manifestSha256],
            ["Integrity", evidence.integrityVerification],
            ["Source dataset verification", evidence.sourceDatasetVerificationLevel]
          ]} />
        </ModelCardSection>
        <ModelCardSection title="Evaluation population">
          <DefinitionList rows={[
            ["Records considered", population.recordsConsidered],
            ["Records evaluated", population.recordsEvaluated],
            ["Records excluded", excluded],
            ["Positive class", summary.classBalance.positiveClassCount],
            ["Negative class", summary.classBalance.negativeClassCount],
            ["Coverage", partialCoverage ? "PARTIAL" : "COMPLETE"]
          ]} />
        </ModelCardSection>
        <ModelCardSection title="Evaluation window">
          <DefinitionList rows={[
            ["Time basis", summary.evaluationWindow.timeBasis],
            ["From", displayTimestamp(summary.evaluationWindow.fromInclusive)],
            ["To", displayTimestamp(summary.evaluationWindow.toInclusive)],
            ["Dataset version", summary.sourceDataset.datasetVersion],
            ["Dataset SHA-256", summary.sourceDataset.sha256],
            ["Dataset truncated", summary.sourceDataset.truncated ? "Yes" : "No"]
          ]} />
        </ModelCardSection>
      </div>
      {partialCoverage && (
        <div className="statePanel warningPanel" role="status">
          <h3>Partial evaluation coverage</h3>
          <p>{excluded} record(s) were excluded. Read the exclusion counts and warnings before interpreting metrics.</p>
        </div>
      )}
      <div className="shadowPerformanceGrid">
        <ModelCardSection title="Metric availability">
          <DefinitionList rows={[
            ["Prediction metrics", availability(metrics)],
            ["Precision", metricDisplay(metrics.precision)],
            ["Recall", metricDisplay(metrics.recall)],
            ["True positive rate", metricDisplay(metrics.truePositiveRate)],
            ["False positive rate", metricDisplay(metrics.falsePositiveRate)],
            ["False negative rate", metricDisplay(metrics.falseNegativeRate)],
            ["Rules vs ML disagreement", availability(summary.supportedMetrics.rulesVsMlDisagreement)]
          ]} />
        </ModelCardSection>
        <ModelCardSection title="Exclusions">
          <DefinitionList rows={[
            ["Identity mismatch", population.recordsExcludedIdentityMismatch],
            ["Source identity mismatch", population.recordsExcludedSourceIdentityMismatch],
            ["Missing prediction evidence", population.recordsExcludedMissingPredictionEvidence],
            ["Unexpected evidence loss", population.recordsExcludedUnexpectedMissingPredictionEvidence],
            ["Invalid prediction evidence", population.recordsExcludedInvalidPredictionEvidence]
          ]} />
        </ModelCardSection>
        <ModelCardList title="Warnings" items={summary.warnings} emptyLabel="No evaluation warnings reported." />
        <ModelCardList title="Limitations" items={summary.limitations} emptyLabel="No limitations reported." />
      </div>
    </>
  );
}

function ModelCardList({ title, items, emptyLabel }) {
  return (
    <ModelCardSection title={title}>
      {items.length > 0
        ? <ul className="shadowPerformanceList">{items.map((item) => <li key={item}>{displayMachineCode(item)}</li>)}</ul>
        : <p className="sectionCopy">{emptyLabel}</p>}
    </ModelCardSection>
  );
}

function ModelCardSection({ title, children }) {
  return <section className="shadowPerformanceSubpanel"><h3>{title}</h3>{children}</section>;
}

function DefinitionList({ rows }) {
  return (
    <dl className="shadowPerformanceDefinitionList">
      {rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{displayValue(value)}</dd></div>)}
    </dl>
  );
}

function ModelCardState({ title, message, canRetry = false, onRetry }) {
  return (
    <div className="statePanel warningPanel" role="alert">
      <h3>{title}</h3>
      <p>{message}</p>
      {canRetry && onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Retry Model Card</button>}
    </div>
  );
}

function errorStateFor(error) {
  if (error?.status === 401) return { title: "Session required", message: "You are not authenticated." };
  if (error?.status === 403) return { title: "Access denied", message: "You do not have permission to read the Model Card." };
  if (error?.status === 404) return { title: "No current Model Card", message: "No current Model Card is configured.", canRetry: true };
  if (error?.status === 503) return { title: "Model Card unavailable", message: "The configured Model Card source is unavailable or invalid.", canRetry: true };
  if (error?.state === "invalid-response") return { title: "Unsupported Model Card contract", message: INVALID_MESSAGE, canRetry: true };
  return { title: "Unable to load Model Card", message: "Could not reach the Model Card API.", canRetry: true };
}

function excludedCount(population) {
  return population.recordsExcludedIdentityMismatch
    + population.recordsExcludedSourceIdentityMismatch
    + population.recordsExcludedMissingPredictionEvidence
    + population.recordsExcludedUnexpectedMissingPredictionEvidence
    + population.recordsExcludedInvalidPredictionEvidence;
}

function availability(metric) {
  return metric.available ? "AVAILABLE" : `NOT AVAILABLE: ${displayMachineCode(metric.reason)}`;
}

function metricDisplay(metric) {
  return metric.available ? `${(metric.value * 100).toFixed(2)}%` : `Not available: ${displayMachineCode(metric.reason)}`;
}

function displayTimestamp(value) {
  if (value === null || value === undefined) return "Unavailable";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? displayValue(value) : date.toLocaleString();
}

function displayValue(value) {
  const text = String(value ?? "").trim();
  return text || "Unavailable";
}

function displayMachineCode(value) {
  return displayValue(value);
}
