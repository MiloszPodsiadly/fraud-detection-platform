import { formatDateTime } from "../utils/format.js";

const STATUS_COPY = {
  AVAILABLE: "A bounded analyst recommendation is available.",
  DEGRADED: "Recommendation is available with diagnostic limitations. Review warnings before interpreting this advisory signal.",
  ABSENT: "No analyst recommendation was produced for this scored transaction. This is not RECOMMEND_NO_ACTION.",
  NOT_APPLICABLE: "The recommendation layer did not produce a recommendation for this transaction.",
  INSUFFICIENT_DATA: "There is not enough bounded diagnostic evidence to produce an analyst recommendation.",
  UNAVAILABLE: "Analyst recommendation is unavailable for this transaction."
};

const RECOMMENDATION_COPY = {
  RECOMMEND_REVIEW: "Suggested manual analyst review.",
  RECOMMEND_CASE_CREATION: "May be suitable for case creation consideration. This does not create a case.",
  RECOMMEND_STEP_UP_REVIEW: "Suggested stronger manual verification. This does not trigger step-up automatically or start workflow.",
  RECOMMEND_MONITOR: "Suggested monitoring or lower-priority review.",
  RECOMMEND_NO_ACTION: "No additional review suggestion from this advisory layer. This is not transaction approval and not payment authorization."
};

const NON_DECISIONING_LABELS = {
  notPaymentAuthorization: "Not payment authorization",
  notAutomaticDecisioning: "Not automatic decisioning",
  notCaseAction: "Not case action",
  notWorkflowAction: "Not workflow action",
  notModelPromotion: "Not model promotion",
  notThresholdRecommendation: "Not threshold recommendation"
};

export function AnalystRecommendationPanel({ recommendation }) {
  return (
    <section className="detailSection" aria-label="Analyst Recommendation">
      <h4>Analyst Recommendation</h4>
      <p className="sectionCopy">
        This recommendation is an analyst aid only. It does not approve, decline, block, authorize payment,
        create a case, trigger workflow, promote a model, or change thresholds.
      </p>
      <dl>
        <Field label="Status" value={recommendation.status} />
        <Field label="Recommendation" value={recommendation.recommendation || "None"} />
        <Field label="Recommendation version" value={recommendation.recommendationVersion || "Not available"} />
        <Field label="Generated at" value={formatDateTime(recommendation.generatedAt)} />
        <Field label="Source" value={recommendation.source || "Not available"} />
        <Field label="Confidence" value={recommendation.confidence || "Not available"} />
        <Field label="Reason codes" value={listText(recommendation.reasonCodes)} />
      </dl>
      <p className="sectionCopy">{STATUS_COPY[recommendation.status] || STATUS_COPY.UNAVAILABLE}</p>
      {recommendation.recommendation && (
        <p className="sectionCopy">{RECOMMENDATION_COPY[recommendation.recommendation] || recommendation.recommendation}</p>
      )}
      <RecommendationWarnings warnings={recommendation.warnings} />
      <NonDecisioningBoundary nonDecisioning={recommendation.nonDecisioning} />
    </section>
  );
}

function RecommendationWarnings({ warnings }) {
  return (
    <section className="summaryCard" aria-label="Analyst Recommendation Warnings">
      <h5>Warnings</h5>
      {warnings.length === 0 && <p className="sectionCopy">No recommendation warnings are present.</p>}
      {warnings.map((warning) => (
        <dl key={warning.warningCode}>
          <Field label="Warning code" value={warning.warningCode} />
          <Field label="Count" value={String(warning.count)} />
        </dl>
      ))}
    </section>
  );
}

function NonDecisioningBoundary({ nonDecisioning }) {
  return (
    <section className="summaryCard" aria-label="Analyst Recommendation Boundary">
      <h5>Non-decisioning Boundary</h5>
      <dl>
        {Object.entries(NON_DECISIONING_LABELS).map(([flag, label]) => (
          <Field key={flag} label={label} value={nonDecisioning?.[flag] ? "Confirmed" : "Not confirmed"} />
        ))}
      </dl>
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

function listText(values) {
  return Array.isArray(values) && values.length > 0 ? values.join(", ") : "None";
}
