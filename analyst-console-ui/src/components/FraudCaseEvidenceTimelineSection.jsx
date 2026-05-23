import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { LoadingPanel } from "./LoadingPanel.jsx";
import { safeTimelineArray, safeTruncationReason, toTimelineEvent } from "./fraudCaseTimelineDisplay.js";
import { timelineStateNotice } from "./fraudCaseTimelineCopy.js";

export const EVIDENCE_TIMELINE_HELPER_TEXT = "Evidence timeline is read-only investigation chronology. It is not an audit trail, not complete case history, not confirmed fraud, not an analyst decision, not a final outcome, and not legal proof.";

export function FraudCaseEvidenceTimelineSection({
  caseId,
  getFraudCaseEvidenceTimeline,
  className = "",
  heading = "Evidence timeline"
}) {
  const [state, setState] = useState({
    isLoading: Boolean(caseId && getFraudCaseEvidenceTimeline),
    timeline: null,
    error: false
  });
  const requestSeqRef = useRef(0);

  useEffect(() => {
    if (!caseId || typeof getFraudCaseEvidenceTimeline !== "function") {
      setState({ isLoading: false, timeline: null, error: true });
      return undefined;
    }

    const abortController = new AbortController();
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setState({ isLoading: true, timeline: null, error: false });

    getFraudCaseEvidenceTimeline(caseId, { signal: abortController.signal })
      .then((timeline) => {
        if (requestSeqRef.current === requestSeq && !abortController.signal.aborted) {
          setState({ isLoading: false, timeline, error: false });
        }
      })
      .catch((error) => {
        if (requestSeqRef.current === requestSeq && !abortController.signal.aborted && !isAbortError(error)) {
          setState({ isLoading: false, timeline: null, error: true });
        }
      });

    return () => {
      abortController.abort();
      requestSeqRef.current += 1;
    };
  }, [caseId, getFraudCaseEvidenceTimeline]);

  const sectionClassName = ["subPanel", "evidenceTimelineSection", className].filter(Boolean).join(" ");

  return (
    <section className={sectionClassName} data-testid="fraud-case-evidence-timeline-section" aria-labelledby="fraud-case-evidence-timeline-heading">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Investigation chronology</p>
          <h2 id="fraud-case-evidence-timeline-heading">{heading}</h2>
          <p className="sectionCopy">{EVIDENCE_TIMELINE_HELPER_TEXT}</p>
        </div>
      </div>

      {state.isLoading && <LoadingPanel label="Loading evidence timeline..." />}
      {!state.isLoading && state.error && <TimelineNotice message={timelineStateNotice("unavailable")} />}
      {!state.isLoading && !state.error && state.timeline && <EvidenceTimelineContent timeline={state.timeline} />}
    </section>
  );
}

function EvidenceTimelineContent({ timeline }) {
  const events = safeTimelineArray(timeline?.events).map(toTimelineEvent);
  const isLegacy = timeline?.legacy === true || events.some((event) => event.eventType === "LEGACY_CONTEXT");
  const isPartial = timeline?.partial === true;
  const isTruncated = timeline?.truncated === true;

  if (isLegacy && events.length === 0) {
    return <TimelineNotice message={timelineStateNotice("legacy")} />;
  }

  if (events.length === 0) {
    return <TimelineNotice message={timelineStateNotice("empty")} />;
  }

  return (
    <div className="evidenceTimelineBody">
      <div className="evidenceTimelineNotices" aria-live="polite">
        {isLegacy && <TimelineNotice message={timelineStateNotice("legacy")} compact />}
        {isPartial && <TimelineNotice message={timelineStateNotice("partial")} compact />}
        {isTruncated && <TimelineNotice message={timelineStateNotice("truncated")} compact />}
        {isTruncated && timeline?.truncationReason && (
          <TimelineNotice message={safeTruncationReason(timeline.truncationReason)} compact />
        )}
      </div>

      <ol className="evidenceTimelineList">
        {events.map((event, index) => (
          <li className="evidenceTimelineItem" key={`${event.renderKey}-${index}`}>
            <article>
              <div className="evidenceTimelineItemHeader">
                <strong>{event.title}</strong>
                <span>{event.time.label}</span>
              </div>
              {event.time.qualifier && <p className="evidenceTimelineQualifier">{event.time.qualifier}</p>}
              <p>{event.description}</p>
              <dl>
                <div>
                  <dt>Event type</dt>
                  <dd>{event.eventType}</dd>
                </div>
                <div>
                  <dt>Source</dt>
                  <dd>{event.source}</dd>
                </div>
                <div>
                  <dt>Evidence status</dt>
                  <dd>{event.evidenceStatus}</dd>
                </div>
                <div>
                  <dt>Linked entity type</dt>
                  <dd>{event.linkedEntityType}</dd>
                </div>
              </dl>
            </article>
          </li>
        ))}
      </ol>
    </div>
  );
}

function TimelineNotice({ message, compact = false }) {
  return (
    <div className={compact ? "evidenceTimelineNotice evidenceTimelineNoticeCompact" : "evidenceTimelineNotice"} role="status">
      {message}
    </div>
  );
}
