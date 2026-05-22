import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { LoadingPanel } from "./LoadingPanel.jsx";

export const EVIDENCE_SUMMARY_HELPER_TEXT = "Evidence summary is read-only investigation context. It is not confirmed fraud, not an analyst decision, not a final outcome, and not legal proof.";

const UNAVAILABLE_MESSAGE = "Evidence summary unavailable.";
const LEGACY_MESSAGE = "Legacy context. This case may not have structured evidence summary data.";
const PARTIAL_MESSAGE = "Partial summary. Some linked evidence context is incomplete or unavailable.";
const TRUNCATED_MESSAGE = "Truncated summary. Only the first bounded set of linked alert evidence was included.";

export function FraudCaseEvidenceSummarySection({
  caseId,
  getFraudCaseEvidenceSummary,
  className = "",
  heading = "Evidence summary"
}) {
  const [state, setState] = useState({
    isLoading: Boolean(caseId && getFraudCaseEvidenceSummary),
    summary: null,
    error: false
  });
  const requestSeqRef = useRef(0);

  useEffect(() => {
    if (!caseId || typeof getFraudCaseEvidenceSummary !== "function") {
      setState({ isLoading: false, summary: null, error: true });
      return undefined;
    }

    const abortController = new AbortController();
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setState({ isLoading: true, summary: null, error: false });

    getFraudCaseEvidenceSummary(caseId, { signal: abortController.signal })
      .then((summary) => {
        if (requestSeqRef.current === requestSeq && !abortController.signal.aborted) {
          setState({ isLoading: false, summary, error: false });
        }
      })
      .catch((error) => {
        if (requestSeqRef.current === requestSeq && !abortController.signal.aborted && !isAbortError(error)) {
          setState({ isLoading: false, summary: null, error: true });
        }
      });

    return () => {
      abortController.abort();
      requestSeqRef.current += 1;
    };
  }, [caseId, getFraudCaseEvidenceSummary]);

  const sectionClassName = ["subPanel", "evidenceSummarySection", className].filter(Boolean).join(" ");

  return (
    <section className={sectionClassName} data-testid="fraud-case-evidence-summary-section" aria-labelledby="fraud-case-evidence-summary-heading">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Investigation context</p>
          <h2 id="fraud-case-evidence-summary-heading">{heading}</h2>
          <p className="sectionCopy">{EVIDENCE_SUMMARY_HELPER_TEXT}</p>
        </div>
      </div>

      {state.isLoading && <LoadingPanel label="Loading evidence summary..." />}
      {!state.isLoading && state.error && <EvidenceSummaryNotice message={UNAVAILABLE_MESSAGE} />}
      {!state.isLoading && !state.error && state.summary && <EvidenceSummaryContent summary={state.summary} />}
    </section>
  );
}

function EvidenceSummaryContent({ summary }) {
  const status = normalizeEvidenceCode(summary.aggregateEvidenceStatus);
  const reasonCodes = safeArray(summary.topReasonCodes).map(normalizeEvidenceCode).filter(Boolean);
  const sourceCounts = safeArray(summary.evidenceBySource);
  const statusCounts = safeArray(summary.evidenceByStatus);
  const evidenceItems = safeArray(summary.highestSeverityEvidence).map(toEvidenceItem);
  const isLegacy = summary.legacy === true || status === "LEGACY";
  const isTruncated = summary.truncated === true;
  const isPartial = summary.partial === true || status === "PARTIAL";
  const isUnavailable = ["UNAVAILABLE", "ERROR", "STALE", "NOT_APPLICABLE"].includes(status);

  if (isLegacy) {
    return <EvidenceSummaryNotice message={LEGACY_MESSAGE} />;
  }

  if (isUnavailable && evidenceItems.length === 0 && sourceCounts.length === 0 && statusCounts.length === 0) {
    return <EvidenceSummaryNotice message={UNAVAILABLE_MESSAGE} />;
  }

  return (
    <div className="evidenceSummaryBody">
      <div className="evidenceSummaryNotices" aria-live="polite">
        {isPartial && <EvidenceSummaryNotice message={PARTIAL_MESSAGE} compact />}
        {isTruncated && <EvidenceSummaryNotice message={TRUNCATED_MESSAGE} compact />}
        {isUnavailable && <EvidenceSummaryNotice message={UNAVAILABLE_MESSAGE} compact />}
      </div>

      <dl className="evidenceSummaryStats">
        <div>
          <dt>Evidence status</dt>
          <dd>{status || "UNKNOWN"}</dd>
        </div>
        <div>
          <dt>Linked alerts</dt>
          <dd>{formatCount(summary.linkedAlertCount)}</dd>
        </div>
        <div>
          <dt>Evidence items</dt>
          <dd>{formatCount(summary.evidenceItemCount)}</dd>
        </div>
        {isTruncated && summary.truncationReason && (
          <div>
            <dt>Truncation reason</dt>
            <dd>{safeTruncationReason(summary.truncationReason)}</dd>
          </div>
        )}
      </dl>

      <div className="evidenceSummaryGrid">
        <SummaryList title="Reason codes" items={reasonCodes} emptyLabel="No reason codes" />
        <CountList title="Evidence sources" items={sourceCounts} labelKey="source" />
        <CountList title="Evidence status counts" items={statusCounts} labelKey="status" />
      </div>

      {evidenceItems.length > 0 && (
        <div className="evidenceSummaryItems">
          <h3>Highest severity evidence</h3>
          <div className="evidenceSummaryCards">
            {evidenceItems.map((item, index) => (
              <article className="evidenceSummaryCard" key={`${item.reasonCode}-${item.evidenceType}-${index}`}>
                <div className="evidenceSummaryCardHeader">
                  <strong>{item.title || item.reasonCode || "Evidence item"}</strong>
                  <span>{item.severity || "UNKNOWN"}</span>
                </div>
                {item.description && <p>{item.description}</p>}
                <dl>
                  <div>
                    <dt>Reason code</dt>
                    <dd>{item.reasonCode || "UNKNOWN"}</dd>
                  </div>
                  <div>
                    <dt>Evidence type</dt>
                    <dd>{item.evidenceType || "UNKNOWN"}</dd>
                  </div>
                  <div>
                    <dt>Source</dt>
                    <dd>{item.source || "UNKNOWN"}</dd>
                  </div>
                  <div>
                    <dt>Status</dt>
                    <dd>{item.status || "UNKNOWN"}</dd>
                  </div>
                </dl>
              </article>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function EvidenceSummaryNotice({ message, compact = false }) {
  return (
    <div className={compact ? "evidenceSummaryNotice evidenceSummaryNoticeCompact" : "evidenceSummaryNotice"} role="status">
      {message}
    </div>
  );
}

function SummaryList({ title, items, emptyLabel }) {
  return (
    <section className="evidenceSummaryList">
      <h3>{title}</h3>
      {items.length === 0 ? <p>{emptyLabel}</p> : (
        <ul>
          {items.map((item) => <li key={item}>{item}</li>)}
        </ul>
      )}
    </section>
  );
}

function CountList({ title, items, labelKey }) {
  const normalizedItems = safeArray(items).map((item) => toCountItem(item, labelKey));

  return (
    <section className="evidenceSummaryList">
      <h3>{title}</h3>
      {normalizedItems.length === 0 ? <p>No evidence context</p> : (
        <ul>
          {normalizedItems.map((item, index) => (
            <li key={`${item.label}-${index}`}>
              <span>{item.label}</span>
              <strong>{item.count}</strong>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function toEvidenceItem(item) {
  return {
    title: boundedEvidenceTitle(item),
    description: boundedEvidenceDescription(),
    reasonCode: normalizeEvidenceCode(item?.reasonCode),
    evidenceType: normalizeEvidenceCode(item?.evidenceType),
    severity: normalizeEvidenceCode(item?.severity),
    source: normalizeEvidenceCode(item?.source),
    status: normalizeEvidenceCode(item?.status)
  };
}

function toCountItem(item, labelKey) {
  if (!item || typeof item !== "object") {
    return { label: "UNKNOWN", count: "0" };
  }

  return {
    label: normalizeEvidenceCode(item[labelKey]) || "UNKNOWN",
    count: formatCount(item.count)
  };
}

function safeArray(value) {
  return Array.isArray(value) ? value : [];
}

function normalizeText(value) {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeEvidenceCode(value) {
  const normalized = normalizeText(value);
  if (!normalized) {
    return "";
  }

  return /^[A-Z0-9_:-]{1,80}$/.test(normalized) ? normalized : "UNKNOWN";
}

function safeTruncationReason(value) {
  return normalizeEvidenceCode(value) === "LINKED_ALERT_LIMIT_EXCEEDED"
    ? "LINKED_ALERT_LIMIT_EXCEEDED"
    : "Bounded summary limit reached";
}

function boundedEvidenceTitle(item) {
  switch (normalizeEvidenceCode(item?.evidenceType)) {
    case "MODEL_SIGNAL":
      return "Model signal";
    case "RULE_MATCH":
      return "Rule evidence";
    case "PROFILE_DEVIATION":
      return "Profile deviation evidence";
    case "VELOCITY_CHECK":
      return "Velocity evidence";
    case "LINK_ANALYSIS":
      return "Linked-entity evidence";
    case "DEVICE_SIGNAL":
      return "Device evidence";
    case "LOCATION_SIGNAL":
      return "Location evidence";
    case "MERCHANT_SIGNAL":
      return "Merchant evidence";
    case "DIAGNOSTIC":
      return "Diagnostic evidence";
    default:
      return "Evidence item";
  }
}

function boundedEvidenceDescription() {
  return "Bounded evidence metadata derived from the fraud-case evidence summary.";
}

function formatCount(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? String(numeric) : "0";
}
