import { LoadingPanel } from "./LoadingPanel.jsx";

const REQUIRED_PERMISSION = "shadow-performance:read";
const REQUIRED_BANNER = "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, threshold recommendation, production decisioning approval, payment authorization, automatic approve / decline / block logic, or analyst recommendation logic.";
const MAX_DIAGNOSTIC_COUNT = 1000;
const REQUIRED_EVALUATION_SUBJECT = {
  subjectType: "PLATFORM_RECOMMENDATION",
  sourceComponent: "ENGINE_INTELLIGENCE_PROJECTION",
  sourceVersion: "ENGINE_INTELLIGENCE_PROJECTION_V1",
  featureContractVersion: "NOT_APPLICABLE",
  modelIdentity: "NOT_AVAILABLE",
  modelArtifactSha256: "NOT_AVAILABLE",
  identityCompleteness: "NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE"
};
const REQUIRED_GOVERNANCE = {
  governanceStatus: "DIAGNOSTIC_ONLY",
  diagnosticOnly: true,
  notProductionApproval: true,
  notPromotionApproval: true,
  notThresholdRecommendation: true,
  notPaymentAuthorization: true,
  notAutomaticDecisioning: true
};
const REQUIRED_EVALUATION = {
  evaluationCardType: "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1",
  evaluationCardVersion: "platform-recommendation-evaluation-card-v1",
  evaluationPurpose: "OFFLINE_DIAGNOSTIC",
  evaluationReportType: "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1",
  evaluationReportVersion: "FDP-124",
  datasetTimeBasis: "FEEDBACK_CREATED_AT"
};

const METRIC_FIELDS = [
  ["alertRecommendedPrecision", "Alert-recommended precision", "percent"],
  ["alertRecommendedRecall", "Alert-recommended recall", "percent"],
  ["falsePositiveRate", "Offline false-positive rate", "percent"],
  ["falseNegativeRate", "Offline false-negative rate", "percent"]
];
const RATE_METRIC_FIELDS = METRIC_FIELDS.map(([field]) => field);

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
        <span>Report type {displayValue(summary.reportType)}</span>
        <span>Version {displayValue(summary.summaryVersion)}</span>
        <span>Generated {formatTimestamp(summary.generatedAt)}</span>
      </div>
      <div className="shadowPerformanceGrid">
        <ShadowPerformanceSubjectPanel subject={summary.evaluationSubject} />
        <ShadowPerformanceGovernancePanel governance={summary.governance} />
        <ShadowPerformanceEvaluationPanel evaluation={summary.evaluation} />
      </div>
      <div className="shadowPerformanceMetricsBand" aria-label="Shadow performance metrics with evaluation population context">
        <ShadowPerformancePopulationPanel population={summary.evaluationPopulation} />
        <ShadowPerformanceMetricsPanel metrics={summary.metrics} />
      </div>
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

function ShadowPerformanceSubjectPanel({ subject }) {
  return (
    <ShadowSection title="Evaluation subject">
      <DefinitionList rows={[
        ["Subject type", subject.subjectType],
        ["Source component", subject.sourceComponent],
        ["Source version", subject.sourceVersion],
        ["Feature contract version", subject.featureContractVersion],
        ["Model identity", subject.modelIdentity],
        ["Model artifact SHA-256", subject.modelArtifactSha256],
        ["Identity completeness", subject.identityCompleteness]
      ]} />
    </ShadowSection>
  );
}

function ShadowPerformanceGovernancePanel({ governance }) {
  return (
    <ShadowSection title="Governance context">
      <DefinitionList rows={[
        ["Governance status", governance.governanceStatus],
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
        ["Evaluation card type", evaluation.evaluationCardType],
        ["Evaluation card version", evaluation.evaluationCardVersion],
        ["Evaluation purpose", evaluation.evaluationPurpose],
        ["Evaluation report type", evaluation.evaluationReportType],
        ["Evaluation report version", evaluation.evaluationReportVersion],
        ["Metric basis", summaryMetricBasis(evaluation)],
        ["Dataset time basis", evaluation.datasetTimeBasis],
        ["Dataset version", evaluation.datasetVersion],
        ["Source manifest SHA-256", evaluation.sourceManifestSha256]
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
        <MetricCard label="Records evaluated" value={population.recordsEvaluated} />
        <MetricCard label="Positive class count" value={population.positiveClassCount} />
        <MetricCard label="Negative class count" value={population.negativeClassCount} />
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
          <li>FDP-104 Platform Recommendation Evaluation Card v1</li>
          <li>FDP-105 Shadow Performance Summary v2</li>
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
  if (!isObject(summary)
      || summary.reportType !== "SHADOW_PERFORMANCE_SUMMARY_V2"
      || summary.summaryVersion !== "shadow-performance-summary-v2"
      || !isString(summary.generatedAt)
      || summary.banner !== REQUIRED_BANNER
      || summary.metricBasis !== "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK"
      || hasLegacyShadowFields(summary)
      || !isValidEvaluationSubject(summary.evaluationSubject)
      || !isValidGovernance(summary.governance)
      || !isValidEvaluation(summary.evaluation)
      || !isObject(summary.evaluationPopulation)
      || !isObject(summary.metrics)
      || !isSafeStringArray(summary.warnings)
      || !isSafeStringArray(summary.limitations)) {
    return false;
  }

  const population = summary.evaluationPopulation;
  const recordsEvaluated = population.recordsEvaluated;
  const positive = population.positiveClassCount;
  const negative = population.negativeClassCount;
  if (!isDiagnosticCount(recordsEvaluated)
      || !isDiagnosticCount(positive)
      || !isDiagnosticCount(negative)
      || positive + negative !== recordsEvaluated) {
    return false;
  }

  if (!RATE_METRIC_FIELDS.every((field) => isMetricValue(summary.metrics[field]))) {
    return false;
  }
  return true;
}

function isObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isString(value) {
  return typeof value === "string" && value.length > 0;
}

function isValidEvaluationSubject(subject) {
  return isObject(subject)
    && Object.entries(REQUIRED_EVALUATION_SUBJECT).every(([field, value]) => subject[field] === value);
}

function isValidGovernance(governance) {
  return isObject(governance)
    && !("approvedFor" in governance)
    && Object.entries(REQUIRED_GOVERNANCE).every(([field, value]) => governance[field] === value);
}

function isValidEvaluation(evaluation) {
  return isObject(evaluation)
    && Object.entries(REQUIRED_EVALUATION).every(([field, value]) => evaluation[field] === value)
    && isString(evaluation.evaluationArtifactSetVersion)
    && isString(evaluation.datasetVersion)
    && /^[a-f0-9]{64}$/.test(evaluation.sourceManifestSha256);
}

function isMetricValue(metric) {
  if (!isObject(metric) || typeof metric.available !== "boolean") {
    return false;
  }
  if (metric.available) {
    return typeof metric.value === "number"
      && Number.isFinite(metric.value)
      && metric.value >= 0
      && metric.value <= 1
      && metric.reason === null;
  }
  return metric.value === null && isString(metric.reason);
}

function isDiagnosticCount(value) {
  return Number.isInteger(value) && value >= 0 && value <= MAX_DIAGNOSTIC_COUNT;
}

function isSafeStringArray(value) {
  return Array.isArray(value)
    && value.length <= 20
    && value.every((item) => typeof item === "string" && item.length > 0 && item.length <= 160);
}

function formatMetric(value, format) {
  if (!isObject(value) || value.available === false) {
    return value?.reason ? `Unavailable: ${value.reason}` : "Unavailable";
  }
  const number = Number(value.value);
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

function hasLegacyShadowFields(summary) {
  return "summaryType" in summary
    || "model" in summary
    || "disagreementSummary" in summary
    || "precisionAtBudget" in (summary.metrics || {})
    || "recallAtTopK" in (summary.metrics || {});
}

function summaryMetricBasis() {
  return "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK";
}

function displayValue(value) {
  if (value === null || value === undefined || value === "") {
    return "Unavailable";
  }
  return String(value);
}
