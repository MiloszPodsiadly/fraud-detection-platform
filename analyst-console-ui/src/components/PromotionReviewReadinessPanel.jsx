import { isValidPromotionReviewReadinessReport } from "../governance/promotionReviewReadinessReportValidation.js";
import { LoadingPanel } from "./LoadingPanel.jsx";

const REQUIRED_PERMISSION = "promotion-readiness:read";
const INVALID_MESSAGE = "The Promotion Review Readiness response is malformed or missing required safety fields.";

const READINESS_COPY = Object.freeze({
  REVIEWABLE: [
    "Human review may begin.",
    "This is not model promotion approval."
  ],
  INSUFFICIENT_DATA: [
    "Not enough diagnostic evidence for human review."
  ],
  NOT_REVIEWABLE: [
    "Diagnostic checks failed. Human review should not begin yet."
  ]
});

const SENSITIVE_PATTERNS = [
  /secret/i,
  /token/i,
  /stack\s*trace/i,
  /stacktrace/i,
  /exception/i,
  /raw\s*artifact/i,
  /raw[A-Z_ -]?/i,
  /transaction\s*reference/i,
  /transactionReference/i,
  /customer/i,
  /account/i,
  /card/i,
  /device/i,
  /merchant/i,
  /[A-Za-z]:[\\/]/,
  /\/(?:var|tmp|run|home|users)\//i,
  /PromotionReviewReadinessReport[A-Za-z]*/
];

export function PromotionReviewReadinessPanel({
  report,
  isLoading,
  error,
  canReadPromotionReadiness
}) {
  const invalid = !isLoading
    && !error
    && canReadPromotionReadiness === true
    && report
    && !isValidPromotionReviewReadinessReport(report);

  return (
    <section className="panel promotionReadinessPanel" id="promotion-review-readiness">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Governance diagnostics</p>
          <h2>Promotion Review Readiness</h2>
          <p className="sectionCopy">
            Offline diagnostic aid only. REVIEWABLE means human review may begin. It does not mean promotion approval.
          </p>
        </div>
      </div>

      {canReadPromotionReadiness === false && (
        <PromotionReadinessState
          title="Access denied"
          message={`You do not have permission to read Promotion Review Readiness diagnostics. Required permission: ${REQUIRED_PERMISSION}.`}
        />
      )}
      {canReadPromotionReadiness !== false && isLoading && <LoadingPanel label="Loading Promotion Review Readiness..." />}
      {canReadPromotionReadiness !== false && !isLoading && error && (
        <PromotionReadinessState {...errorStateFor(error)} />
      )}
      {invalid && (
        <PromotionReadinessState
          title="Malformed diagnostic response"
          message={INVALID_MESSAGE}
        />
      )}
      {canReadPromotionReadiness !== false && !isLoading && !error && report && !invalid && (
        <PromotionReadinessSuccess report={report} />
      )}
    </section>
  );
}

function PromotionReadinessSuccess({ report }) {
  return (
    <div className="shadowPerformanceStack promotionReadinessStack">
      <div className="stateBanner shadowPerformanceBanner promotionReadinessBanner" role="status">
        {safeText(report.banner)}
      </div>
      <PromotionReadinessStatus status={report.readinessStatus} />
      <div className="shadowPerformanceGrid">
        <PromotionSection title="Report context">
          <DefinitionList rows={[
            ["Report type", report.reportType],
            ["Report version", report.reportVersion],
            ["Generated", formatTimestamp(report.generatedAt)],
            ["Governance status", report.governanceStatus],
            ["Readiness status", report.readinessStatus]
          ]} />
        </PromotionSection>
        <PromotionSection title="Diagnostic boundary">
          <DefinitionList rows={[
            ["Diagnostic only", booleanValue(report.diagnosticOnly)],
            ["Not promotion approval", booleanValue(report.notPromotionApproval)],
            ["Not threshold recommendation", booleanValue(report.notThresholdRecommendation)],
            ["Not production decisioning", booleanValue(report.notProductionDecisioning)],
            ["Not payment authorization", booleanValue(report.notPaymentAuthorization)],
            ["Not automatic decisioning", booleanValue(report.notAutomaticDecisioning)],
            ["Not analyst recommendation", booleanValue(report.notAnalystRecommendation)]
          ]} />
        </PromotionSection>
        <PromotionSection title="Input summary">
          <DefinitionList rows={[
            ["Shadow summary present", booleanValue(report.inputs?.shadowPerformanceSummary?.present)],
            ["Shadow summary type", report.inputs?.shadowPerformanceSummary?.summaryType],
            ["Shadow summary version", report.inputs?.shadowPerformanceSummary?.summaryVersion],
            ["Shadow summary generated", formatTimestamp(report.inputs?.shadowPerformanceSummary?.generatedAt)],
            ["Minimum diagnostic evidence records", report.inputs?.minimumDiagnosticEvidenceRecords],
            ["Records accepted for evaluation", report.inputs?.recordsAcceptedForEvaluation]
          ]} />
        </PromotionSection>
      </div>
      <PromotionChecksTable checks={report.checks} />
      <div className="shadowPerformanceGrid">
        <PromotionList title="Reason codes" items={report.reasonCodes} emptyLabel="No reason codes reported." />
        <PromotionList title="Warnings" items={report.warnings} emptyLabel="No warnings reported." />
        <PromotionList title="Limitations" items={report.limitations} emptyLabel="No limitations reported." />
      </div>
    </div>
  );
}

function PromotionReadinessStatus({ status }) {
  const copy = READINESS_COPY[status] || ["Readiness status unavailable."];
  return (
    <section className="statePanel promotionReadinessStatus" aria-label="Promotion Review Readiness status">
      <p className="eyebrow">Readiness status</p>
      <h3>{safeText(status)}</h3>
      {copy.map((line) => <p key={line}>{line}</p>)}
    </section>
  );
}

function PromotionChecksTable({ checks }) {
  return (
    <PromotionSection title="Checks">
      <div className="tableWrap">
        <table className="alertsTable promotionReadinessChecks">
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Status</th>
              <th scope="col">Severity</th>
            </tr>
          </thead>
          <tbody>
            {checks.map((check, index) => (
              <tr key={`${safeText(check?.name)}-${index}`}>
                <td>{safeText(check?.name)}</td>
                <td>{safeText(check?.status)}</td>
                <td>{safeText(check?.severity)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </PromotionSection>
  );
}

function PromotionList({ title, items, emptyLabel }) {
  return (
    <PromotionSection title={title}>
      {Array.isArray(items) && items.length > 0 ? (
        <ul className="shadowPerformanceList">
          {items.map((item, index) => <li key={`${safeText(item)}-${index}`}>{safeText(item)}</li>)}
        </ul>
      ) : (
        <p className="sectionCopy">{emptyLabel}</p>
      )}
    </PromotionSection>
  );
}

function PromotionSection({ title, children }) {
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

function PromotionReadinessState({ title, message }) {
  return (
    <div className="statePanel warningPanel" role="alert">
      <h3>{title}</h3>
      <p>{message}</p>
    </div>
  );
}

function errorStateFor(error) {
  if (error?.status === 401) {
    return {
      title: "Session required",
      message: "You are not authenticated."
    };
  }
  if (error?.status === 403) {
    return {
      title: "Access denied",
      message: "You do not have permission to read Promotion Review Readiness diagnostics."
    };
  }
  if (error?.status === 404) {
    return {
      title: "No current diagnostic report",
      message: "No current Promotion Review Readiness report is configured. Generate and expose the diagnostic report first."
    };
  }
  if (error?.status === 503) {
    return {
      title: "Diagnostic report unavailable",
      message: "Promotion Review Readiness report is unavailable or invalid. Do not use this report for assessment."
    };
  }
  if (error?.state === "invalid-response") {
    return {
      title: "Malformed diagnostic response",
      message: INVALID_MESSAGE
    };
  }
  return {
    title: "Unable to load diagnostics",
    message: "Could not reach the Promotion Review Readiness API."
  };
}

function formatTimestamp(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}

function booleanValue(value) {
  return value ? "Yes" : "No";
}

function displayValue(value) {
  if (value === null || value === undefined || value === "") {
    return "Unavailable";
  }
  return safeText(value);
}

function safeText(value) {
  const text = String(value ?? "").trim();
  if (!text || SENSITIVE_PATTERNS.some((pattern) => pattern.test(text))) {
    return "Unavailable";
  }
  return text;
}
