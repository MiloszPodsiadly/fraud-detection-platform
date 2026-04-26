import { useState } from "react";
import { AUTHORITIES, hasAuthority } from "../auth/session.js";
import { formatDateTime } from "../utils/format.js";
import { EmptyState } from "./EmptyState.jsx";
import { ErrorState } from "./ErrorState.jsx";
import { LoadingPanel } from "./LoadingPanel.jsx";
import { RiskBadge } from "./RiskBadge.jsx";
import { PermissionNotice } from "./SecurityStatePanels.jsx";

const SEVERITIES = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const LIMITS = [25, 50, 100];
const AUDIT_DECISIONS = ["ACKNOWLEDGED", "NEEDS_FOLLOW_UP", "DISMISSED_AS_NOISE"];
const MAX_NOTE_LENGTH = 500;

export function GovernanceReviewQueue({
  advisoryQueue,
  filters,
  isLoading,
  error,
  auditHistories = {},
  session,
  onFiltersChange,
  onRetry,
  onRecordAudit
}) {
  const status = advisoryQueue?.status || "UNAVAILABLE";
  const events = status === "UNAVAILABLE" ? [] : advisoryQueue?.advisory_events || [];
  const isUnavailable = status === "UNAVAILABLE";
  const isPartial = status === "PARTIAL";
  const canRecordAudit = hasAuthority(session, AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE);

  function updateFilter(field, value) {
    onFiltersChange({ ...filters, [field]: value });
  }

  return (
    <section className="panel governanceQueuePanel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Governance</p>
          <h2>Operator review queue</h2>
          <p className="sectionCopy">
            Advisory context is read-only. Recording review writes audit history only.
            Audit entries do not trigger system actions. Advisory signals do not affect scoring, model behavior, or system decisions.
            Operator review is manual.
          </p>
        </div>
      </div>

      <div className="filterBar governanceFilterBar">
        <label>
          Severity
          <select value={filters.severity} onChange={(event) => updateFilter("severity", event.target.value)}>
            {SEVERITIES.map((severity) => (
              <option key={severity} value={severity}>{severity}</option>
            ))}
          </select>
        </label>
        <label>
          Model version exact match
          <input
            value={filters.modelVersion}
            onChange={(event) => updateFilter("modelVersion", event.target.value)}
            placeholder="2026-04-21.trained.v1"
          />
        </label>
        <label>
          Limit
          <select value={filters.limit} onChange={(event) => updateFilter("limit", Number(event.target.value))}>
            {LIMITS.map((limit) => (
              <option key={limit} value={limit}>{limit}</option>
            ))}
          </select>
        </label>
      </div>
      <p className="sectionCopy">Results are limited to recent advisory events.</p>

      {isPartial && (
        <div className="stateBanner" role="status">
          Partial data available. Some advisory events may be missing.
        </div>
      )}
      {isUnavailable && !isLoading && !error && (
        <div className="stateBanner stateBannerWarning" role="status">
          Advisory data is currently unavailable.
        </div>
      )}

      {isLoading && <LoadingPanel label="Loading governance advisories..." />}
      {!isLoading && error && <ErrorState error={error} onRetry={onRetry} />}
      {!isLoading && !error && !isUnavailable && events.length === 0 && (
        <EmptyState
          title="No advisory signals available for the selected filters."
          message="This does not guarantee absence of model drift."
        />
      )}
      {!isLoading && !error && events.length > 0 && (
        <>
          <div className="queueMeta" aria-label="Governance queue summary">
            <span>Status <strong>{status}</strong></span>
            <span>Returned <strong>{advisoryQueue.count}</strong></span>
            <span>Retention <strong>{advisoryQueue.retention_limit}</strong></span>
          </div>
          <div className="tableWrap">
            <table className="alertTable governanceTable">
              <thead>
                <tr>
                  <th>Severity</th>
                  <th>Signal</th>
                  <th>Model</th>
                  <th>Lifecycle context</th>
                  <th>Recommended review</th>
                  <th>Created</th>
                  <th>Human review</th>
                </tr>
              </thead>
              <tbody>
                {events.map((event) => (
                  <GovernanceEventRows
                    key={event.event_id}
                    event={event}
                    auditHistory={auditHistories[event.event_id]}
                    canRecordAudit={canRecordAudit}
                    session={session}
                    onRecordAudit={onRecordAudit}
                  />
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}

function GovernanceEventRows({ event, auditHistory, canRecordAudit, session, onRecordAudit }) {
  const [decision, setDecision] = useState("ACKNOWLEDGED");
  const [note, setNote] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState("");

  async function submitAudit(formEvent) {
    formEvent.preventDefault();
    if (!canRecordAudit || isSubmitting) {
      return;
    }
    setIsSubmitting(true);
    setError("");
    setResult("");
    try {
      await onRecordAudit(event.event_id, {
        decision,
        note: note.trim() || undefined
      });
      setNote("");
      setResult("Human review recorded.");
    } catch (apiError) {
      setError(apiError.message || "Unable to record review.");
    } finally {
      setIsSubmitting(false);
    }
  }

  const auditEvents = auditHistory?.audit_events || [];

  return (
    <>
      <tr>
        <td>
          <RiskBadge riskLevel={event.severity} />
        </td>
        <td>
          <strong>{event.drift_status}</strong>
          <span>{event.confidence} confidence</span>
          <span>{event.advisory_confidence_context}</span>
          {event.explanation && (
            <p className="tableCopy">
              <strong>Explanation (heuristic):</strong> {event.explanation}
            </p>
          )}
        </td>
        <td>
          <strong>{event.model_name || "Unknown model"}</strong>
          <span>{event.model_version || "Unknown version"}</span>
        </td>
        <td>
          <LifecycleContext context={event.lifecycle_context} />
        </td>
        <td>
          <ActionTags actions={event.recommended_actions} />
        </td>
        <td>{formatDateTime(event.created_at)}</td>
        <td>
          <strong>{auditEvents.length}</strong>
          <span>review entries</span>
        </td>
      </tr>
      <tr className="governanceAuditRow">
        <td colSpan="7">
          <div className="governanceAuditPanel">
            <form className="governanceAuditForm" onSubmit={submitAudit}>
              <div>
                <strong>Record review</strong>
                <p className="tableCopy">
                  This records human review only. It does not affect scoring or model behavior.
                </p>
              </div>
              {!canRecordAudit && (
                <PermissionNotice
                  session={session}
                  authority={AUTHORITIES.GOVERNANCE_ADVISORY_AUDIT_WRITE}
                  action="recording governance advisory review"
                />
              )}
              <label>
                Decision
                <select
                  value={decision}
                  onChange={(selectEvent) => setDecision(selectEvent.target.value)}
                  disabled={!canRecordAudit || isSubmitting}
                >
                  {AUDIT_DECISIONS.map((option) => (
                    <option key={option} value={option}>{option}</option>
                  ))}
                </select>
              </label>
              <label>
                Note
                <textarea
                  value={note}
                  onChange={(textEvent) => setNote(textEvent.target.value.slice(0, MAX_NOTE_LENGTH))}
                  disabled={!canRecordAudit || isSubmitting}
                  maxLength={MAX_NOTE_LENGTH}
                  rows="3"
                  placeholder="Optional bounded operator note."
                />
              </label>
              <div className="governanceAuditActions">
                <span className="muted">{note.length}/{MAX_NOTE_LENGTH}</span>
                <button className="secondaryButton" type="submit" disabled={!canRecordAudit || isSubmitting}>
                  {isSubmitting ? "Recording..." : "Mark reviewed"}
                </button>
              </div>
              {error && <p className="formError">{error}</p>}
              {result && <p className="formSuccess">{result}</p>}
            </form>
            <AuditHistory history={auditHistory} />
          </div>
        </td>
      </tr>
    </>
  );
}

function AuditHistory({ history }) {
  const auditEvents = history?.audit_events || [];
  if (history?.status === "UNAVAILABLE") {
    return (
      <div className="governanceAuditHistory">
        <strong>Audit history</strong>
        <p className="formError">{history.error || "Audit history is currently unavailable."}</p>
      </div>
    );
  }

  return (
    <div className="governanceAuditHistory">
      <strong>Audit history</strong>
      {auditEvents.length === 0 ? (
        <p className="muted">No human review entries recorded for this advisory event.</p>
      ) : (
        <ol>
          {auditEvents.map((auditEvent) => (
            <li key={auditEvent.audit_id}>
              <strong>{auditEvent.decision}</strong>
              <span>{formatDateTime(auditEvent.created_at)} by {auditEvent.actor_display_name || auditEvent.actor_id}</span>
              {auditEvent.note && <p>{auditEvent.note}</p>}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function LifecycleContext({ context = {} }) {
  return (
    <dl className="lifecycleFacts">
      <div>
        <dt>Current version</dt>
        <dd>{context.current_model_version || "Not available"}</dd>
      </div>
      <div>
        <dt>Loaded</dt>
        <dd>{formatDateTime(context.model_loaded_at)}</dd>
      </div>
      <div>
        <dt>Changed recently</dt>
        <dd>{context.model_changed_recently ? "Yes" : "No"}</dd>
      </div>
      <div>
        <dt>Recent events</dt>
        <dd>{Number(context.recent_lifecycle_event_count || 0)}</dd>
      </div>
    </dl>
  );
}

function ActionTags({ actions = [] }) {
  if (!actions.length) {
    return <span className="muted">Manual review only</span>;
  }

  return (
    <div className="tagList">
      {actions.map((action) => (
        <span className="tag" key={action}>{action}</span>
      ))}
    </div>
  );
}
