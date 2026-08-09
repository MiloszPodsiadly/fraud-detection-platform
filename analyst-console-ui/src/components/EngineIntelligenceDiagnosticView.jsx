export const VELOCITY_SEVERITY_COPY = "Velocity score is a deterministic normalized risk-severity signal. It is not a calibrated fraud probability and must not be interpreted as model confidence.";

export const ENGINE_STATUS_COPY = {
  AVAILABLE: "Engine result available",
  UNAVAILABLE: "Engine result unavailable",
  TIMEOUT: "Engine timed out",
  DEGRADED: "Engine result degraded",
  NOT_APPLICABLE: "Engine not applicable"
};

export const RULES_VS_ML_DIAGNOSTIC_COPY = "Agreement status describes the Rules vs ML diagnostic comparison only.";
export const VELOCITY_OUTSIDE_COMPARISON_COPY = "Velocity remains an independent diagnostic signal and is not part of the Rules vs ML score delta.";
export const SCORE_DELTA_BOUNDARY_COPY = "Score delta bucket is diagnostic and not a threshold recommendation.";
export const WARNING_LIMITATION_COPY = "Warnings describe limitations in the projected diagnostic data.";
export const WARNING_NOT_OPERATIONAL_COPY = "Warnings are not operational instructions.";

const OPERATIONAL_STATUSES = new Set(["TIMEOUT", "UNAVAILABLE", "DEGRADED"]);

export function DiagnosticComparisonView({
  comparison,
  sectionClassName,
  headingId,
  headingLevel = 3,
  title,
  emptyCopy,
  FieldComponent,
  comparedEnginesText,
  includeBoundaryCopy = false,
  ariaLabel
}) {
  const Heading = headingTag(headingLevel);
  return (
    <section className={sectionClassName} aria-labelledby={headingId} aria-label={ariaLabel}>
      <Heading id={headingId}>{title}</Heading>
      {!comparison && emptyCopy && <p className="sectionCopy">{emptyCopy}</p>}
      {comparison && (
        <>
          <dl className={sectionClassName === "engineIntelligenceBlock" ? "engineIntelligenceFields" : undefined}>
            <FieldComponent label="Comparison type" value={comparison.comparisonType} />
            <FieldComponent label="Compared engines" value={comparedEnginesText(comparison.comparedEngineIds)} />
            <FieldComponent label="Rules vs ML agreement" value={comparison.agreementStatus} />
            <FieldComponent label={sectionClassName === "engineIntelligenceBlock" ? "Risk mismatch" : "Risk mismatch status"} value={comparison.riskMismatchStatus} />
            <FieldComponent label={sectionClassName === "engineIntelligenceBlock" ? "Score delta" : "Score delta bucket"} value={comparison.scoreDeltaBucket} />
          </dl>
          {includeBoundaryCopy && (
            <>
              <p className="sectionCopy">{RULES_VS_ML_DIAGNOSTIC_COPY}</p>
              <p className="sectionCopy">{VELOCITY_OUTSIDE_COMPARISON_COPY}</p>
              <p className="sectionCopy">{SCORE_DELTA_BOUNDARY_COPY}</p>
            </>
          )}
        </>
      )}
    </section>
  );
}

export function BoundedEngineResultsView({
  engines,
  sectionClassName,
  cardClassName,
  headingId,
  headingLevel = 3,
  title,
  emptyCopy,
  FieldComponent,
  ReasonCodesComponent,
  showDisplayName = false,
  hideRiskForOperationalStatus = false,
  ariaLabel
}) {
  const Heading = headingTag(headingLevel);
  return (
    <section className={sectionClassName} aria-labelledby={headingId} aria-label={ariaLabel}>
      <Heading id={headingId}>{title}</Heading>
      {engines.length === 0 && <p className={sectionClassName === "engineIntelligenceBlock" ? "muted" : "sectionCopy"}>{emptyCopy}</p>}
      {engines.length > 0 && sectionClassName === "engineIntelligenceBlock" && (
        <div className="engineIntelligenceCards">
          {engines.map((engine) => (
            <article className={cardClassName} key={engine.engineId}>
              <EngineCardHeader engine={engine} showDisplayName={showDisplayName} />
              <dl>
                <FieldComponent label="Status" value={engineStatusLabel(engine.status)} />
                <FieldComponent label="Score bucket" value={engine.scoreBucket} />
                {(!hideRiskForOperationalStatus || !isOperationalStatus(engine.status)) && engine.riskLevel && (
                  <FieldComponent label="Risk level" value={engine.riskLevel} />
                )}
              </dl>
              <ReasonCodesComponent reasonCodes={engine.reasonCodes} />
            </article>
          ))}
        </div>
      )}
      {engines.length > 0 && sectionClassName !== "engineIntelligenceBlock" && engines.map((engine) => (
        <article className={cardClassName} key={`${engine.engineId}-${engine.engineType}`}>
          <h5>{showDisplayName ? engineDisplayName(engine) : engine.engineId}</h5>
          <dl>
            <FieldComponent label="Engine type" value={engine.engineType} />
            <FieldComponent label="Status" value={engineStatusLabel(engine.status)} />
            <FieldComponent label="Risk level" value={engine.riskLevel || "Not available"} />
            <FieldComponent label="Score bucket" value={engine.scoreBucket || "Not available"} />
            <FieldComponent label="Reason codes" value={listText(engine.reasonCodes)} />
          </dl>
        </article>
      ))}
    </section>
  );
}

export function WarningListView({
  warnings,
  sectionClassName,
  cardClassName,
  headingId,
  headingLevel = 3,
  title,
  emptyCopy,
  FieldComponent,
  compact = false,
  ariaLabel
}) {
  const Heading = headingTag(headingLevel);
  return (
    <section className={sectionClassName} aria-labelledby={headingId} aria-label={ariaLabel}>
      <Heading id={headingId}>{title}</Heading>
      {!compact && (
        <>
          <p className="sectionCopy">{WARNING_LIMITATION_COPY}</p>
          <p className="sectionCopy">{WARNING_NOT_OPERATIONAL_COPY}</p>
        </>
      )}
      {warnings.length === 0 && <p className={compact ? "muted" : "sectionCopy"}>{emptyCopy}</p>}
      {warnings.length > 0 && compact && (
        <ul className="engineIntelligenceWarningList">
          {warnings.map((warning) => (
            <li key={warning.warningCode}>
              <span>{warning.warningCode}</span>
              <strong>{warning.count}</strong>
            </li>
          ))}
        </ul>
      )}
      {warnings.length > 0 && !compact && warnings.map((warning) => (
        <article className={cardClassName} key={warning.warningCode}>
          <dl>
            <FieldComponent label="Warning code" value={warning.warningCode} />
            <FieldComponent label="Count" value={String(warning.count)} />
          </dl>
        </article>
      ))}
    </section>
  );
}

export function engineStatusLabel(status) {
  return ENGINE_STATUS_COPY[status] || status || "UNKNOWN";
}

export function engineDisplayName(engine) {
  if (engine?.engineId === "velocity.primary") {
    return "Velocity";
  }
  if (engine?.engineId === "rules.primary") {
    return "Rules";
  }
  if (engine?.engineId === "ml.python.primary") {
    return "ML model";
  }
  return engine?.engineId || "Engine";
}

export function isOperationalStatus(status) {
  return OPERATIONAL_STATUSES.has(status);
}

export function listText(values) {
  return Array.isArray(values) && values.length > 0 ? values.join(", ") : "None";
}

function EngineCardHeader({ engine, showDisplayName }) {
  return (
    <div className="engineIntelligenceCardHeader">
      <strong>{showDisplayName ? engineDisplayName(engine) : engine.engineId}</strong>
      <span>{engine.engineId} / {engine.engineType}</span>
    </div>
  );
}

function headingTag(level) {
  return `h${level}`;
}
