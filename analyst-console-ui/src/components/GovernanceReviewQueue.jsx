import { formatDateTime } from "../utils/format.js";
import { EmptyState } from "./EmptyState.jsx";
import { ErrorState } from "./ErrorState.jsx";
import { LoadingPanel } from "./LoadingPanel.jsx";
import { RiskBadge } from "./RiskBadge.jsx";

const SEVERITIES = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const LIMITS = [25, 50, 100];

export function GovernanceReviewQueue({
  advisoryQueue,
  filters,
  isLoading,
  error,
  onFiltersChange,
  onRetry
}) {
  const status = advisoryQueue?.status || "UNAVAILABLE";
  const events = status === "UNAVAILABLE" ? [] : advisoryQueue?.advisory_events || [];
  const isUnavailable = status === "UNAVAILABLE";
  const isPartial = status === "PARTIAL";

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
            This view is read-only. Advisory events do not trigger system actions.
            Advisory signals do not affect scoring, model behavior, or system decisions.
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
                </tr>
              </thead>
              <tbody>
                {events.map((event) => (
                  <tr key={event.event_id}>
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
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
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
