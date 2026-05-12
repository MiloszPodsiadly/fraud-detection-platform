import { useEffect, useMemo, useState } from "react";
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
  const [draftRequest, setDraftRequest] = useState(() => editableWorkQueueRequest(request));
  const [validationError, setValidationError] = useState(null);
  const appliedSortLabel = sortLabelFor(request.sort);
  const draftChanged = useMemo(
    () => !sameEditableRequest(draftRequest, editableWorkQueueRequest(request)),
    [draftRequest, request]
  );
  const duplicateCaseIds = queue.duplicateCaseIds || [];

  useEffect(() => {
    setDraftRequest(editableWorkQueueRequest(request));
    setValidationError(null);
  }, [request]);

  function updateField(name, value) {
    setDraftRequest((current) => ({
      ...current,
      [name]: value
    }));
    setValidationError(null);
  }

  function applyFilters() {
    const nextValidationError = validateDraftRequest(draftRequest);
    if (nextValidationError) {
      setValidationError(nextValidationError);
      return;
    }
    onRequestChange({ ...draftRequest, cursor: null });
  }

  function resetFilters() {
    const nextRequest = editableWorkQueueRequest({
      size: 20,
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
    setDraftRequest(nextRequest);
    setValidationError(null);
    onRequestChange({ ...nextRequest, cursor: null });
  }

  return (
    <section className="panel workQueuePanel" aria-labelledby="fraud-case-work-queue-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Fraud cases</p>
          <h2 id="fraud-case-work-queue-title">Fraud Case Work Queue</h2>
          <p className="sectionCopy">Read-only investigator queue. Counts reflect loaded cases only.</p>
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
        <SummaryMetric label="Loaded open" value={stats.open} tone="neutral" />
        <SummaryMetric label="Loaded critical" value={stats.critical} tone="critical" />
        <SummaryMetric label="Loaded near breach" value={stats.nearBreach} tone="warning" />
        <SummaryMetric label="Loaded breached" value={stats.breached} tone="critical" />
        <SummaryMetric label="Loaded unassigned" value={stats.unassigned} tone="muted" />
      </div>

      <div className="workQueueFilters" aria-label="Fraud case work queue filters">
        <label>
          Status
          <select value={draftRequest.status} onChange={(event) => updateField("status", event.target.value)}>
            {STATUS_OPTIONS.map((status) => <option key={status}>{status}</option>)}
          </select>
        </label>
        <label>
          Priority
          <select value={draftRequest.priority} onChange={(event) => updateField("priority", event.target.value)}>
            {PRIORITY_OPTIONS.map((priority) => <option key={priority}>{priority}</option>)}
          </select>
        </label>
        <label>
          Risk
          <select value={draftRequest.riskLevel} onChange={(event) => updateField("riskLevel", event.target.value)}>
            {RISK_OPTIONS.map((risk) => <option key={risk}>{risk}</option>)}
          </select>
        </label>
        <label>
          Assigned investigator
          <input
            value={draftRequest.assignee}
            onChange={(event) => updateField("assignee", event.target.value)}
            placeholder="Investigator ID"
          />
        </label>
        <label>
          Created from
          <input type="datetime-local" value={draftRequest.createdFrom} onChange={(event) => updateField("createdFrom", event.target.value)} />
        </label>
        <label>
          Created to
          <input type="datetime-local" value={draftRequest.createdTo} onChange={(event) => updateField("createdTo", event.target.value)} />
        </label>
        <label>
          Updated from
          <input type="datetime-local" value={draftRequest.updatedFrom} onChange={(event) => updateField("updatedFrom", event.target.value)} />
        </label>
        <label>
          Updated to
          <input type="datetime-local" value={draftRequest.updatedTo} onChange={(event) => updateField("updatedTo", event.target.value)} />
        </label>
        <label>
          Linked alert
          <input value={draftRequest.linkedAlertId} onChange={(event) => updateField("linkedAlertId", event.target.value)} placeholder="Alert ID" />
        </label>
        <label>
          Sort
          <select value={draftRequest.sort} onChange={(event) => updateField("sort", event.target.value)}>
            {FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <label>
          Slice size
          <select value={draftRequest.size} onChange={(event) => updateField("size", Number(event.target.value))}>
            {[20, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}
          </select>
        </label>
      </div>
      <p className="sectionCopy workQueueFilterNote">Date filters use your local time and are sent as UTC instants.</p>
      {validationError && <p className="formError">{validationError}</p>}

      <div className="workQueueToolbar" aria-label="Fraud case work queue active filters">
        <div className="workQueueChips">
          <span className="tag">Sort: {appliedSortLabel}</span>
          {activeFilters.length === 0 ? (
            <span className="tag">All queue cases</span>
          ) : activeFilters.map((filter) => (
            <span className="tag" key={filter}>{filter}</span>
          ))}
        </div>
        <div className="workQueueToolbarActions">
          <button className="secondaryButton compactButton" type="button" onClick={applyFilters} disabled={isLoading || !draftChanged}>
            Apply filters
          </button>
          <button className="secondaryButton compactButton" type="button" onClick={resetFilters} disabled={isLoading || (!draftChanged && activeFilters.length === 0)}>
            Reset filters
          </button>
        </div>
      </div>

      {duplicateCaseIds.length > 0 && (
        <div className="statePanel warningPanel" role="alert">
          <h3>Queue changed while loading.</h3>
          <p>Refresh from first slice.</p>
          <button className="secondaryButton" type="button" onClick={onRefreshFirstSlice}>
            Refresh from first slice
          </button>
        </div>
      )}

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
                        {!item.caseNumber && <span>Case ID</span>}
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
            <span>{queue.hasNext ? "More cases available" : "End of current queue slice"} - {appliedSortLabel}</span>
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
  addFilter(filters, "Investigator", request.assignee, "filter set");
  addFilter(filters, "Created from", request.createdFrom, "local time set");
  addFilter(filters, "Created to", request.createdTo, "local time set");
  addFilter(filters, "Updated from", request.updatedFrom, "local time set");
  addFilter(filters, "Updated to", request.updatedTo, "local time set");
  addFilter(filters, "Alert", request.linkedAlertId, "filter set");
  return filters;
}

function addFilter(filters, label, value, displayValue = value) {
  if (value && value !== "ALL") {
    filters.push(`${label}: ${displayValue}`);
  }
}

function sortLabelFor(sort) {
  return FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS.find((option) => option.value === sort)?.label || sort;
}

function editableWorkQueueRequest(request = {}) {
  return {
    size: request.size ?? 20,
    status: request.status || "ALL",
    priority: request.priority || "ALL",
    riskLevel: request.riskLevel || "ALL",
    assignee: request.assignee || "",
    createdFrom: request.createdFrom || "",
    createdTo: request.createdTo || "",
    updatedFrom: request.updatedFrom || "",
    updatedTo: request.updatedTo || "",
    linkedAlertId: request.linkedAlertId || "",
    sort: request.sort || DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT
  };
}

function sameEditableRequest(left, right) {
  return JSON.stringify(editableWorkQueueRequest(left)) === JSON.stringify(editableWorkQueueRequest(right));
}

function validateDraftRequest(request) {
  const dateFields = [
    ["Created from", request.createdFrom],
    ["Created to", request.createdTo],
    ["Updated from", request.updatedFrom],
    ["Updated to", request.updatedTo]
  ];
  const invalidDate = dateFields.find(([, value]) => value && Number.isNaN(new Date(value).getTime()));
  if (invalidDate) {
    return `${invalidDate[0]} is not a valid local date and time.`;
  }
  return null;
}
