import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { LoadingPanel } from "./LoadingPanel.jsx";
import { boundedEvidenceDescription, boundedEvidenceTitleForType } from "./evidenceSummaryCopy.js";
import { formatCount, normalizeEvidenceCode, safeArray, safeTruncationReason, toCountItem } from "./evidenceSummaryDisplay.js";

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
                  <strong>{item.displayTitle || item.reasonCode || "Evidence item"}</strong>
                  <span>{item.severity || "UNKNOWN"}</span>
                </div>
                {item.displayDescription && <p>{item.displayDescription}</p>}
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
          {items.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}
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
    displayTitle: boundedEvidenceTitleForType(item?.evidenceType),
    displayDescription: boundedEvidenceDescription(),
    reasonCode: normalizeEvidenceCode(item?.reasonCode),
    evidenceType: normalizeEvidenceCode(item?.evidenceType),
    severity: normalizeEvidenceCode(item?.severity),
    source: normalizeEvidenceCode(item?.source),
    status: normalizeEvidenceCode(item?.status)
  };
}
