import { LoadingPanel } from "./LoadingPanel.jsx";

const REQUIRED_PERMISSION = "shadow-performance:read";
const REQUIRED_BANNER = "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.";

const METRIC_FIELDS = [
  ["precisionAtBudget", "Offline precision at budget", "percent"],
  ["recallAtTopK", "Offline recall at top K", "percent"],
  ["falsePositiveRate", "Offline false-positive rate", "percent"],
  ["mlCaughtRulesMissedCount", "ML caught / rules missed", "count"],
  ["rulesCaughtMlMissedCount", "Rules caught / ML missed", "count"],
  ["missingMlCount", "Missing ML count", "count"],
  ["missingRulesCount", "Missing rules count", "count"],
  ["missingProjectionCount", "Missing projection count", "count"],
  ["notEvaluationEligibleCount", "Not evaluation eligible count", "count"]
];

const DISAGREEMENT_FIELDS = [
  ["rulesHighMlHigh", "Rules high / ML high"],
  ["rulesHighMlLowOrMedium", "Rules high / ML low or medium"],
  ["rulesLowOrMediumMlHigh", "Rules low or medium / ML high"],
  ["rulesLowOrMediumMlLowOrMedium", "Rules low or medium / ML low or medium"],
  ["rulesMissingMlPresent", "Rules missing / ML present"],
  ["mlMissingRulesPresent", "ML missing / rules present"],
  ["bothMissing", "Both missing"],
  ["notEvaluationEligibleExcluded", "Not evaluation eligible excluded"]
];

export function ShadowPerformanceDashboard({
  summary,
  isLoading,
  error,
  canReadShadowPerformance,
  onRetry,
  headingProps = {}
}) {
  const malformed = !isLoading && !error && canReadShadowPerformance === true && summary && !isValidSummary(summary);

  return (
    <section className="panel shadowPerformancePanel" id="shadow-performance-summary">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Shadow diagnostics</p>
          <h2 {...headingProps}>Shadow Performance Summary</h2>
          <p className="sectionCopy">
            Current read-only diagnostic summary from the FDP-106 governance endpoint.
          </p>
        </div>
      </div>

      {canReadShadowPerformance === false && (
        <ShadowPerformanceState
          title="Access denied"
          message={`You do not have permission to view Shadow Performance Summary. Required permission: ${REQUIRED_PERMISSION}.`}
        />
      )}
      {canReadShadowPerformance !== false && isLoading && <LoadingPanel label="Loading Shadow Performance Summary..." />}
      {canReadShadowPerformance !== false && !isLoading && error?.status === 404 && (
        <ShadowPerformanceNoCurrentSummary onRetry={onRetry} />
      )}
      {canReadShadowPerformance !== false && !isLoading && error && error.status !== 404 && (
        <ShadowPerformanceState {...errorStateFor(error)} onRetry={onRetry} />
      )}
      {malformed && (
        <ShadowPerformanceState
          title="Malformed Shadow Performance Summary"
          message="Shadow Performance Summary response was malformed. Do not use this view for model assessment."
          onRetry={onRetry}
        />
      )}
      {canReadShadowPerformance !== false && !isLoading && !error && summary && !malformed && (
        <ShadowPerformanceSuccess summary={summary} />
      )}
    </section>
  );
}

function ShadowPerformanceSuccess({ summary }) {
  return (
    <div className="shadowPerformanceStack">
      <ShadowPerformanceDiagnosticBanner banner={summary.banner} />
      <div className="shadowPerformanceMeta" aria-label="Shadow summary context">
        <span>Summary type {displayValue(summary.summaryType)}</span>
        <span>Version {displayValue(summary.summaryVersion)}</span>
        <span>Generated {formatTimestamp(summary.generatedAt)}</span>
      </div>
      <div className="shadowPerformanceGrid">
        <ShadowPerformanceModelPanel model={summary.model} />
        <ShadowPerformanceGovernancePanel governance={summary.governance} />
        <ShadowPerformanceEvaluationPanel evaluation={summary.evaluation} />
      </div>
      <div className="shadowPerformanceMetricsBand" aria-label="Shadow performance metrics with evaluation population context">
        <ShadowPerformancePopulationPanel population={summary.evaluationPopulation} />
        <ShadowPerformanceMetricsPanel metrics={summary.metrics} />
      </div>
      <ShadowPerformanceDisagreementPanel disagreementSummary={summary.disagreementSummary} />
      <div className="shadowPerformanceGrid">
        <ShadowPerformanceWarningsPanel warnings={summary.warnings} />
        <ShadowPerformanceLimitationsPanel limitations={summary.limitations} />
      </div>
    </div>
  );
}

function ShadowPerformanceDiagnosticBanner({ banner }) {
  return (
    <div className="stateBanner shadowPerformanceBanner" role="status">
      {banner || REQUIRED_BANNER}
    </div>
  );
}

function ShadowPerformanceModelPanel({ model }) {
  return (
    <ShadowSection title="Model identity">
      <DefinitionList rows={[
        ["Model name", model.modelName],
        ["Model version", model.modelVersion],
        ["Model family", model.modelFamily],
        ["Feature contract version", model.featureContractVersion]
      ]} />
    </ShadowSection>
  );
}

function ShadowPerformanceGovernancePanel({ governance }) {
  return (
    <ShadowSection title="Governance context">
      <DefinitionList rows={[
        ["Governance status", governance.governanceStatus],
        ["Approved diagnostic modes", listValue(governance.approvedFor)],
        ["Diagnostic only", booleanValue(governance.diagnosticOnly)],
        ["Not production approval", booleanValue(governance.notProductionApproval)],
        ["Not promotion approval", booleanValue(governance.notPromotionApproval)],
        ["Not threshold recommendation", booleanValue(governance.notThresholdRecommendation)],
        ["Not payment authorization", booleanValue(governance.notPaymentAuthorization)],
        ["Not automatic decisioning", booleanValue(governance.notAutomaticDecisioning)]
      ]} />
    </ShadowSection>
  );
}

function ShadowPerformanceEvaluationPanel({ evaluation }) {
  return (
    <ShadowSection title="Evaluation context">
      <DefinitionList rows={[
        ["Evaluation report type", evaluation.evaluationReportType],
        ["Evaluation report version", evaluation.evaluationReportVersion],
        ["Metric basis", evaluation.metricBasis],
        ["Dataset time basis", evaluation.datasetTimeBasis],
        ["Dataset deduplication policy", evaluation.datasetDeduplicationPolicy]
      ]} />
    </ShadowSection>
  );
}

function ShadowPerformancePopulationPanel({ population }) {
  return (
    <section className="shadowPerformanceSubpanel">
      <h3>Evaluation population</h3>
      <p className="sectionCopy">
        Metrics are shown with evaluation population context to avoid overclaiming performance on small samples.
      </p>
      <div className="analyticsGrid shadowPerformancePopulationGrid">
        <MetricCard label="Dataset records read" value={population.datasetRecordsRead} />
        <MetricCard label="Records accepted for evaluation" value={population.recordsAcceptedForEvaluation} />
        <MetricCard label="Records excluded not evaluation eligible" value={population.recordsExcludedNotEvaluationEligible} />
      </div>
    </section>
  );
}

function ShadowPerformanceMetricsPanel({ metrics }) {
  return (
    <section className="shadowPerformanceSubpanel">
      <h3>Metrics</h3>
      <div className="analyticsGrid">
        {METRIC_FIELDS.map(([field, label, format]) => (
          <MetricCard key={field} label={label} value={formatMetric(metrics[field], format)} />
        ))}
      </div>
    </section>
  );
}

function ShadowPerformanceDisagreementPanel({ disagreementSummary }) {
  return (
    <ShadowSection title="Rule vs ML diagnostic disagreement">
      <div className="analyticsGrid">
        {DISAGREEMENT_FIELDS.map(([field, label]) => (
          <MetricCard key={field} label={label} value={disagreementSummary[field]} />
        ))}
      </div>
    </ShadowSection>
  );
}

function ShadowPerformanceWarningsPanel({ warnings }) {
  return <ShadowListSection title="Warnings" items={warnings} emptyLabel="No warnings reported by the current summary." />;
}

function ShadowPerformanceLimitationsPanel({ limitations }) {
  return <ShadowListSection title="Limitations" items={limitations} emptyLabel="No limitations reported by the current summary." />;
}

function ShadowListSection({ title, items, emptyLabel }) {
  return (
    <ShadowSection title={title}>
      {items.length > 0 ? (
        <ul className="shadowPerformanceList">
          {items.map((item) => <li key={item}>{item}</li>)}
        </ul>
      ) : (
        <p className="sectionCopy">{emptyLabel}</p>
      )}
    </ShadowSection>
  );
}

function ShadowSection({ title, children }) {
  return (
    <section className="shadowPerformanceSubpanel">
      <h3>{title}</h3>
      {children}
    </section>
  );
}

function DefinitionList({ rows }) {
  return (
    <dl className="shadowPerformanceDefinitionList">
      {rows.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{displayValue(value)}</dd>
        </div>
      ))}
    </dl>
  );
}

function MetricCard({ label, value }) {
  return (
    <div className="metricCard">
      <strong>{displayValue(value)}</strong>
      <span>{label}</span>
    </div>
  );
}

function ShadowPerformanceState({ title, message, onRetry }) {
  return (
    <div className="statePanel warningPanel" role="alert">
      <h3>{title}</h3>
      <p>{message}</p>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

function ShadowPerformanceNoCurrentSummary({ onRetry }) {
  return (
    <div className="statePanel warningPanel shadowPerformanceEmptyState" role="alert">
      <ShadowPerformanceDiagnosticBanner />
      <div className="shadowPerformanceEmptyIntro">
        <p className="eyebrow">FDP-106 current summary</p>
        <h3>No current Shadow Performance Summary</h3>
        <p>
          The dashboard reached the authorized FDP-106 read API, but no current validated Shadow Performance Summary is available.
        </p>
        <p>
          This is not a model quality result and it is not a failure of the dashboard. The UI does not display fake, zero, sample, fallback, or stale metrics when the API returns 404.
        </p>
        <p>
          Shadow performance metrics will appear here only after a valid FDP-105 Shadow Performance Summary is available through the FDP-106 endpoint.
        </p>
        <p>
          This 404 state is not production approval, not promotion readiness, not threshold recommendation, not payment authorization, not automatic decisioning, and not analyst recommendation logic.
        </p>
      </div>
      <ShadowSection title="Technical context">
        <DefinitionList rows={[
          ["Endpoint", "GET /api/v1/governance/shadow-performance/summary/current"],
          ["Status", "404 Not Found"],
          ["Data source", "FDP-106 Authorized Read API"],
          ["Current summary", "Unavailable"],
          ["Fallback metrics", "Disabled"],
          ["Demo/sample metrics", "Disabled"],
          ["Mode", "Read-only diagnostic view"]
        ]} />
      </ShadowSection>
      <ShadowSection title="What this means">
        <ul className="shadowPerformanceList">
          <li>The Shadow Performance dashboard route is working.</li>
          <li>The FDP-106 endpoint was reached.</li>
          <li>No current validated Shadow Performance Summary is configured yet.</li>
          <li>No metrics are shown because showing fake or zero metrics would be misleading.</li>
          <li>Missing summary does not mean the model is approved, rejected, production-ready, or unsafe.</li>
        </ul>
      </ShadowSection>
      <ShadowSection title="What needs to happen next">
        <p className="sectionCopy">
          To display metrics, the backend environment must provide a current validated Shadow Performance Summary produced from the governed artifact chain:
        </p>
        <ol className="shadowPerformanceChain">
          <li>FDP-102 feedback dataset export</li>
          <li>FDP-103 offline evaluation report</li>
          <li>FDP-104 Model Card v1</li>
          <li>FDP-105 Shadow Performance Summary v1</li>
          <li>FDP-106 authorized read API</li>
          <li>FDP-107 dashboard</li>
        </ol>
      </ShadowSection>
      {onRetry && <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

function errorStateFor(error) {
  if (error?.status === 401) {
    return {
      title: "Session required",
      message: "You must be signed in to view Shadow Performance Summary."
    };
  }
  if (error?.status === 403) {
    return {
      title: "Access denied",
      message: `You do not have permission to view Shadow Performance Summary. Required permission: ${REQUIRED_PERMISSION}.`
    };
  }
  if (error?.status === 404) {
    return {
      title: "No current summary",
      message: "No current Shadow Performance Summary is available. This is not a model quality result."
    };
  }
  if (error?.status === 503) {
    return {
      title: "Summary unavailable",
      message: "Shadow Performance Summary is currently unavailable or failed validation. Do not use this view for model assessment."
    };
  }
  return {
    title: "Unable to load Shadow Performance Summary",
    message: "Shadow Performance Summary could not be loaded. Retry the diagnostic read."
  };
}

function isValidSummary(summary) {
  return isObject(summary)
    && isString(summary.summaryType)
    && isString(summary.summaryVersion)
    && isString(summary.generatedAt)
    && isObject(summary.model)
    && isString(summary.model.modelName)
    && isString(summary.model.modelVersion)
    && isString(summary.model.modelFamily)
    && isString(summary.model.featureContractVersion)
    && isObject(summary.governance)
    && isString(summary.governance.governanceStatus)
    && Array.isArray(summary.governance.approvedFor)
    && [
      "diagnosticOnly",
      "notProductionApproval",
      "notPromotionApproval",
      "notThresholdRecommendation",
      "notPaymentAuthorization",
      "notAutomaticDecisioning"
    ].every((field) => typeof summary.governance[field] === "boolean")
    && isObject(summary.evaluation)
    && [
      "evaluationReportType",
      "evaluationReportVersion",
      "metricBasis",
      "datasetTimeBasis",
      "datasetDeduplicationPolicy"
    ].every((field) => isString(summary.evaluation[field]))
    && isObject(summary.evaluationPopulation)
    && [
      "datasetRecordsRead",
      "recordsAcceptedForEvaluation",
      "recordsExcludedNotEvaluationEligible"
    ].every((field) => Number.isFinite(Number(summary.evaluationPopulation[field])))
    && isObject(summary.metrics)
    && METRIC_FIELDS.every(([field]) => Number.isFinite(Number(summary.metrics[field])))
    && isObject(summary.disagreementSummary)
    && DISAGREEMENT_FIELDS.every(([field]) => Number.isFinite(Number(summary.disagreementSummary[field])))
    && Array.isArray(summary.warnings)
    && Array.isArray(summary.limitations)
    && isString(summary.banner);
}

function isObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isString(value) {
  return typeof value === "string" && value.length > 0;
}

function formatMetric(value, format) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return "Unavailable";
  }
  if (format === "percent") {
    return `${(number * 100).toFixed(1)}%`;
  }
  return number;
}

function formatTimestamp(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return displayValue(value);
  }
  return date.toLocaleString();
}

function booleanValue(value) {
  return value ? "Yes" : "No";
}

function listValue(value) {
  return Array.isArray(value) ? value.join(", ") : value;
}

function displayValue(value) {
  if (value === null || value === undefined || value === "") {
    return "Unavailable";
  }
  return String(value);
}
