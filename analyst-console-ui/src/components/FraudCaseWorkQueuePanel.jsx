import { formatDateTime } from "../utils/format.js";
import {
  dedupeByCaseId,
  DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT,
  FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS
} from "../fraudCases/workQueueState.js";

const STATUS_OPTIONS = ["ALL", "OPEN", "IN_REVIEW", "CONFIRMED_FRAUD", "FALSE_POSITIVE", "CLOSED", "REOPENED"];
const PRIORITY_OPTIONS = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const RISK_OPTIONS = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];

export function FraudCaseWorkQueuePanel({
  queue,
  request,
  isLoading,
  error,
  onRequestChange,
  onLoadMore,
  onRetry,
  onRefreshFirstSlice,
  onOpenCase
}) {
  const items = dedupeByCaseId(queue.content || []);
  const invalidCursor = isInvalidCursorError(error);
  const stats = workQueueStats(items);
  const activeFilters = activeFilterLabels(request);
  const sortLabel = FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS.find((option) => option.value === request.sort)?.label || request.sort;

  function updateField(name, value) {
    onRequestChange({ [name]: value });
  }

  function clearFilters() {
    onRequestChange({
      status: "ALL",
      priority: "ALL",
      riskLevel: "ALL",
      assignee: "",
      createdFrom: "",
      createdTo: "",
      updatedFrom: "",
      updatedTo: "",
      linkedAlertId: "",
      sort: DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT
    });
  }

  return (
    <section className="panel workQueuePanel" aria-labelledby="fraud-case-work-queue-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Fraud cases</p>
          <h2 id="fraud-case-work-queue-title">Fraud Case Work Queue</h2>
          <p className="sectionCopy">Read-only investigator queue</p>
        </div>
        <div className="workQueueHeaderActions">
          <button className="secondaryButton" type="button" onClick={onRetry} disabled={isLoading}>
            Refresh
          </button>
          <div className="workQueueCounter">
            <strong>{items.length}</strong>
            <span>Loaded cases</span>
          </div>
        </div>
      </div>

      <div className="workQueueSummary" aria-label="Fraud case work queue summary">
        <SummaryMetric label="Open" value={stats.open} tone="neutral" />
        <SummaryMetric label="Critical" value={stats.critical} tone="critical" />
        <SummaryMetric label="Near breach" value={stats.nearBreach} tone="warning" />
        <SummaryMetric label="Breached" value={stats.breached} tone="critical" />
        <SummaryMetric label="Unassigned" value={stats.unassigned} tone="muted" />
      </div>

      <div className="workQueueFilters" aria-label="Fraud case work queue filters">
        <label>
          Status
          <select value={request.status} onChange={(event) => updateField("status", event.target.value)}>
            {STATUS_OPTIONS.map((status) => <option key={status}>{status}</option>)}
          </select>
        </label>
        <label>
          Priority
          <select value={request.priority} onChange={(event) => updateField("priority", event.target.value)}>
            {PRIORITY_OPTIONS.map((priority) => <option key={priority}>{priority}</option>)}
          </select>
        </label>
        <label>
          Risk
          <select value={request.riskLevel} onChange={(event) => updateField("riskLevel", event.target.value)}>
            {RISK_OPTIONS.map((risk) => <option key={risk}>{risk}</option>)}
          </select>
        </label>
        <label>
          Assigned investigator
          <input
            value={request.assignee}
            onChange={(event) => updateField("assignee", event.target.value)}
            placeholder="Investigator ID"
          />
        </label>
        <label>
          Created from
          <input type="datetime-local" value={request.createdFrom} onChange={(event) => updateField("createdFrom", event.target.value)} />
        </label>
        <label>
          Created to
          <input type="datetime-local" value={request.createdTo} onChange={(event) => updateField("createdTo", event.target.value)} />
        </label>
        <label>
          Updated from
          <input type="datetime-local" value={request.updatedFrom} onChange={(event) => updateField("updatedFrom", event.target.value)} />
        </label>
        <label>
          Updated to
          <input type="datetime-local" value={request.updatedTo} onChange={(event) => updateField("updatedTo", event.target.value)} />
        </label>
        <label>
          Linked alert
          <input value={request.linkedAlertId} onChange={(event) => updateField("linkedAlertId", event.target.value)} placeholder="Alert ID" />
        </label>
        <label>
          Sort
          <select value={request.sort} onChange={(event) => updateField("sort", event.target.value)}>
            {FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <label>
          Slice size
          <select value={request.size} onChange={(event) => updateField("size", Number(event.target.value))}>
            {[20, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}
          </select>
        </label>
      </div>

      <div className="workQueueToolbar" aria-label="Fraud case work queue active filters">
        <div className="workQueueChips">
          <span className="tag">Sort: {sortLabel}</span>
          {activeFilters.length === 0 ? (
            <span className="tag">All queue cases</span>
          ) : activeFilters.map((filter) => (
            <span className="tag" key={filter}>{filter}</span>
          ))}
        </div>
        <button className="secondaryButton compactButton" type="button" onClick={clearFilters} disabled={isLoading || activeFilters.length === 0}>
          Clear filters
        </button>
      </div>

      {isLoading && items.length === 0 && (
        <div className="statePanel">
          <div className="spinner" />
          <p>Loading fraud case work queue...</p>
        </div>
      )}

      {!isLoading && error && (
        <WorkQueueError error={error} invalidCursor={invalidCursor} onRetry={invalidCursor ? onRefreshFirstSlice : onRetry} />
      )}

      {!isLoading && !error && items.length === 0 && (
        <div className="statePanel">
          <h3>No fraud cases in this queue view</h3>
          <p>Adjust the read filters to inspect another bounded queue slice.</p>
        </div>
      )}

      {!error && items.length > 0 && (
        <>
          <div className="tableWrap">
            <table className="alertTable workQueueTable">
              <thead>
                <tr>
                  <th>Case</th>
                  <th>Status</th>
                  <th>Priority</th>
                  <th>Risk</th>
                  <th>Investigator</th>
                  <th>Age</th>
                  <th>SLA</th>
                  <th>Alerts</th>
                  <th>Updated</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.caseId}>
                    <td>
                      <div className="workQueueCaseCell">
                        <strong>{item.caseNumber || item.caseId}</strong>
                        <span>{item.caseNumber ? item.caseId : "Case ID"}</span>
                      </div>
                    </td>
                    <td><span className="statusPill">{item.status || "UNKNOWN"}</span></td>
                    <td><span className={`priorityPill priority${item.priority || "UNKNOWN"}`}>{item.priority || "UNKNOWN"}</span></td>
                    <td><span className={`riskBadge risk${item.riskLevel || "UNKNOWN"}`}>{item.riskLevel || "UNKNOWN"}</span></td>
                    <td>{item.assignedInvestigatorId || "Unassigned"}</td>
                    <td>
                      <strong>{formatDuration(item.caseAgeSeconds)}</strong>
                      <span>Updated {formatDuration(item.lastUpdatedAgeSeconds)} ago</span>
                    </td>
                    <td>
                      <span className={`slaBadge sla${item.slaStatus || "UNKNOWN"}`}>{formatSlaStatus(item.slaStatus)}</span>
                      <span>{formatDateTime(item.slaDeadlineAt)}</span>
                    </td>
                    <td className="numericCell">{item.linkedAlertCount ?? 0}</td>
                    <td>{formatDateTime(item.updatedAt || item.createdAt)}</td>
                    <td>
                      <button className="rowButton" type="button" onClick={() => onOpenCase(item.caseId)}>
                        Open case
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="workQueueFooter">
            <span>{queue.hasNext ? "More cases available" : "End of current queue slice"} · {sortLabel}</span>
            <button className="secondaryButton" type="button" disabled={!queue.hasNext || isLoading} onClick={onLoadMore}>
              {isLoading ? "Loading..." : "Load more"}
            </button>
          </div>
        </>
      )}
    </section>
  );
}

function SummaryMetric({ label, value, tone }) {
  return (
    <div className={`workQueueMetric workQueueMetric${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function WorkQueueError({ error, invalidCursor, onRetry }) {
  const message = messageForWorkQueueError(error, invalidCursor);
  return (
    <div className="statePanel errorPanel">
      <h3>{message.title}</h3>
      <p>{message.body}</p>
      <button className="secondaryButton" type="button" onClick={onRetry}>
        {invalidCursor ? "Refresh queue" : "Try again"}
      </button>
    </div>
  );
}

function messageForWorkQueueError(error, invalidCursor) {
  if (error?.status === 401) {
    return { title: "Sign-in required", body: "Start a valid session before loading the fraud case work queue." };
  }
  if (error?.status === 403) {
    return { title: "Access denied", body: "You do not have access to the fraud case work queue." };
  }
  if (error?.status === 503) {
    return { title: "Work queue unavailable", body: "The fraud case work queue is fail-closed because a required service is unavailable." };
  }
  if (invalidCursor) {
    return { title: "Queue position expired", body: "The queue position is no longer valid. Refresh the queue to load the first slice with the current filters." };
  }
  if (error?.status === 400) {
    return { title: "Invalid work queue request", body: error.message || "Adjust the filter or sort selection and retry." };
  }
  return { title: "Unable to load work queue", body: error?.message || "Network error while loading the fraud case work queue." };
}

function isInvalidCursorError(error) {
  const text = `${error?.error || ""} ${error?.message || ""}`;
  return error?.status === 400 && (
    text.includes("INVALID_CURSOR") ||
    text.includes("INVALID_CURSOR_PAGE_COMBINATION")
  );
}

function formatDuration(seconds) {
  if (seconds === null || seconds === undefined) {
    return "Not available";
  }
  const totalSeconds = Math.max(0, Number(seconds));
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  if (days > 0) {
    return `${days}d ${hours}h`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}

function formatSlaStatus(status) {
  return String(status || "UNKNOWN").replaceAll("_", " ");
}

function workQueueStats(items) {
  return items.reduce((stats, item) => {
    if (["OPEN", "IN_REVIEW", "REOPENED"].includes(item.status)) {
      stats.open += 1;
    }
    if (item.riskLevel === "CRITICAL") {
      stats.critical += 1;
    }
    if (item.slaStatus === "NEAR_BREACH") {
      stats.nearBreach += 1;
    }
    if (item.slaStatus === "BREACHED") {
      stats.breached += 1;
    }
    if (!item.assignedInvestigatorId) {
      stats.unassigned += 1;
    }
    return stats;
  }, { open: 0, critical: 0, nearBreach: 0, breached: 0, unassigned: 0 });
}

function activeFilterLabels(request) {
  const filters = [];
  addFilter(filters, "Status", request.status);
  addFilter(filters, "Priority", request.priority);
  addFilter(filters, "Risk", request.riskLevel);
  addFilter(filters, "Investigator", request.assignee);
  addFilter(filters, "Created from", request.createdFrom);
  addFilter(filters, "Created to", request.createdTo);
  addFilter(filters, "Updated from", request.updatedFrom);
  addFilter(filters, "Updated to", request.updatedTo);
  addFilter(filters, "Alert", request.linkedAlertId);
  return filters;
}

function addFilter(filters, label, value) {
  if (value && value !== "ALL") {
    filters.push(`${label}: ${value}`);
  }
}
