import { useEffect, useMemo, useRef, useState } from "react";
import { formatDateTime } from "../utils/format.js";
import { formatAgeAgo, formatDurationFromSeconds } from "../fraudCases/workQueueFormat.js";
import {
  dedupeByCaseId,
  DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT,
  FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS,
  initialFraudCaseWorkQueueRequest
} from "../fraudCases/workQueueState.js";
import { WorkQueueBadge } from "./fraudCaseWorkQueue/WorkQueueBadge.jsx";
import { WorkQueueErrorPanel } from "./fraudCaseWorkQueue/WorkQueueErrorPanel.jsx";
import { WorkQueueStageStats } from "./fraudCaseWorkQueue/WorkQueueStageStats.jsx";

const STATUS_OPTIONS = ["ALL", "OPEN", "IN_REVIEW", "ESCALATED", "RESOLVED", "CLOSED", "REOPENED"];
const PRIORITY_OPTIONS = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const RISK_OPTIONS = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
export function FraudCaseWorkQueuePanel({
  queue,
  request,
  draftRequest,
  isLoading,
  error,
  warning,
  validationError,
  lastRefreshedAt,
  onDraftChange,
  onApplyFilters,
  onResetFilters,
  onLoadMore,
  onRetry,
  onRefreshFirstSlice,
  onOpenCase
}) {
  const items = dedupeByCaseId(queue.content || []);
  const [filtersExpanded, setFiltersExpanded] = useState(true);
  const alertPanelRef = useRef(null);
  const invalidCursor = isInvalidCursorError(error);
  const stages = workQueueStages(items);
  const activeFilters = activeFilterLabels(request);
  const appliedSortLabel = sortLabelFor(request.sort);
  const effectiveDraft = draftRequest || initialFraudCaseWorkQueueRequest();
  const draftChanged = useMemo(
    () => !sameEditableRequest(effectiveDraft, editableWorkQueueRequest(request)),
    [effectiveDraft, request]
  );
  const hasInvalidFilters = Boolean(validationError);
  const hasDuplicateWarning = warning?.type === "DUPLICATE_SLICE" || (queue.duplicateCaseIds || []).length > 0;

  useEffect(() => {
    if ((error?.status === 403 || error?.status === 503 || invalidCursor) && alertPanelRef.current) {
      alertPanelRef.current.focus();
    }
  }, [error, invalidCursor]);

  function updateField(name, value) {
    onDraftChange(name, value);
  }

  return (
    <section className="panel workQueuePanel" id="work-queue" aria-labelledby="fraud-case-work-queue-title">
      <div className="panelHeader workQueuePanelHeader">
        <div>
          <p className="eyebrow">Fraud cases</p>
          <h2 id="fraud-case-work-queue-title">Fraud Case Work Queue</h2>
          <p className="sectionCopy">Read-only investigator queue. Counts reflect loaded cases only.</p>
        </div>
        <div className="workQueueHeaderActions">
          <button
            className="secondaryButton"
            type="button"
            onClick={onRetry}
            disabled={isLoading}
            aria-label="Refresh fraud case work queue from first slice"
          >
            Refresh
          </button>
          <div className="workQueueCounter">
            <strong>{items.length}</strong>
            <span>Loaded cases</span>
          </div>
        </div>
      </div>

      <WorkQueueStageStats stages={stages} />

      <div className="workQueueFilterShell">
        <div className="workQueueToolbar workQueueFilterHeader">
          <div>
            <h3>Filters</h3>
            <p className="sectionCopy">Date filters use your local time and are sent as UTC instants.</p>
          </div>
          <div className="workQueueToolbarActions">
            <button
              className="secondaryButton compactButton"
              type="button"
              onClick={() => setFiltersExpanded((current) => !current)}
              aria-expanded={filtersExpanded}
            >
              {filtersExpanded ? "Hide filters" : "Show filters"}
            </button>
            <button
              className="secondaryButton compactButton"
              type="button"
              onClick={onApplyFilters}
              disabled={isLoading || !draftChanged || hasInvalidFilters}
              aria-label="Apply fraud case work queue filters"
            >
              Apply filters
            </button>
            <button
              className="secondaryButton compactButton"
              type="button"
              onClick={onResetFilters}
              disabled={isLoading || (!draftChanged && activeFilters.length === 0)}
              aria-label="Reset fraud case work queue filters"
            >
              Reset filters
            </button>
          </div>
        </div>

        {filtersExpanded && (
          <div className="workQueueFilters" aria-label="Fraud case work queue filters">
            <label>
              Status
              <select value={effectiveDraft.status} onChange={(event) => updateField("status", event.target.value)}>
                {STATUS_OPTIONS.map((status) => <option key={status}>{status}</option>)}
              </select>
            </label>
            <label>
              Priority
              <select value={effectiveDraft.priority} onChange={(event) => updateField("priority", event.target.value)}>
                {PRIORITY_OPTIONS.map((priority) => <option key={priority}>{priority}</option>)}
              </select>
            </label>
            <label>
              Risk
              <select value={effectiveDraft.riskLevel} onChange={(event) => updateField("riskLevel", event.target.value)}>
                {RISK_OPTIONS.map((risk) => <option key={risk}>{risk}</option>)}
              </select>
            </label>
            <label>
              Assigned investigator
              <input
                value={effectiveDraft.assignee}
                onChange={(event) => updateField("assignee", event.target.value)}
                placeholder="Investigator ID"
              />
            </label>
            <label>
              Created from
              <input type="datetime-local" value={effectiveDraft.createdFrom} onChange={(event) => updateField("createdFrom", event.target.value)} />
            </label>
            <label>
              Created to
              <input type="datetime-local" value={effectiveDraft.createdTo} onChange={(event) => updateField("createdTo", event.target.value)} />
            </label>
            <label>
              Updated from
              <input type="datetime-local" value={effectiveDraft.updatedFrom} onChange={(event) => updateField("updatedFrom", event.target.value)} />
            </label>
            <label>
              Updated to
              <input type="datetime-local" value={effectiveDraft.updatedTo} onChange={(event) => updateField("updatedTo", event.target.value)} />
            </label>
            <label>
              Linked alert
              <input value={effectiveDraft.linkedAlertId} onChange={(event) => updateField("linkedAlertId", event.target.value)} placeholder="Alert ID" />
            </label>
            <label>
              Sort
              <select value={effectiveDraft.sort} onChange={(event) => updateField("sort", event.target.value)}>
                {FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
            <label>
              Slice size
              <select value={effectiveDraft.size} onChange={(event) => updateField("size", Number(event.target.value))}>
                {[20, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}
              </select>
            </label>
          </div>
        )}
        {validationError && <p className="formError">{validationError}</p>}

        <div className="workQueueChips" aria-label="Applied fraud case work queue filters">
          <span className="tag">Sort: {appliedSortLabel}</span>
          {activeFilters.length === 0 ? (
            <span className="tag">All queue cases</span>
          ) : activeFilters.map((filter) => (
            <span className="tag" key={filter}>{filter}</span>
          ))}
        </div>
      </div>

      <div className="srOnly" aria-live="polite">
        {isLoading ? "Loading fraud case work queue" : lastRefreshedAt ? "Work queue refreshed" : ""}
      </div>

      {hasDuplicateWarning && (
        <div className="statePanel warningPanel" role="alert">
          <h3>Queue changed while loading.</h3>
          <p>Some duplicate rows were skipped. Refresh from the first slice for a clean view.</p>
          <button className="secondaryButton" type="button" onClick={onRefreshFirstSlice} aria-label="Refresh fraud case work queue from first slice">
            Refresh from first slice
          </button>
        </div>
      )}

      {!isLoading && error && (
        <WorkQueueErrorPanel
          refTarget={alertPanelRef}
          error={error}
          invalidCursor={invalidCursor}
          onRetry={invalidCursor ? onRefreshFirstSlice : onRetry}
        />
      )}

      {!error && (
        <>
          <div className="tableWrap workQueueTableWrap">
            <table className="alertTable workQueueTable" aria-label="Fraud case work queue table">
              <caption>Read-only fraud case work queue</caption>
              <thead>
                <tr>
                  <th scope="col">Case</th>
                  <th scope="col">Status</th>
                  <th scope="col">Priority</th>
                  <th scope="col">Risk</th>
                  <th scope="col">Assignee</th>
                  <th scope="col">Age</th>
                  <th scope="col">Updated</th>
                  <th scope="col">SLA</th>
                  <th scope="col">Alerts</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {isLoading && items.length === 0 && <SkeletonRows />}
                {!isLoading && items.length === 0 && (
                  <tr>
                    <td colSpan={10}>
                      <div className="statePanel workQueueEmptyState">
                        <h3>No cases match the current queue view.</h3>
                        <p>Adjust filters or refresh from the first slice.</p>
                      </div>
                    </td>
                  </tr>
                )}
                {items.map((item) => (
                  <WorkQueueRow item={item} key={item.caseId} onOpenCase={onOpenCase} />
                ))}
              </tbody>
            </table>
          </div>

          <div className="workQueueCards" aria-label="Fraud case work queue cards">
            {isLoading && items.length === 0 && <SkeletonCards />}
            {!isLoading && items.length === 0 && (
              <div className="statePanel workQueueEmptyState">
                <h3>No cases match the current queue view.</h3>
                <p>Adjust filters or refresh from the first slice.</p>
              </div>
            )}
            {items.map((item) => (
              <WorkQueueCard item={item} key={item.caseId} onOpenCase={onOpenCase} />
            ))}
          </div>

          <div className="workQueueFooter">
            <span>{queue.hasNext ? "More cases available" : "End of loaded queue"} - {appliedSortLabel}</span>
            <button
              className="secondaryButton"
              type="button"
              disabled={!queue.hasNext || isLoading}
              onClick={onLoadMore}
              aria-label="Load more fraud cases"
            >
              {isLoading ? "Loading..." : "Load more"}
            </button>
          </div>
        </>
      )}
    </section>
  );
}

function WorkQueueRow({ item, onOpenCase }) {
  const caseLabel = item.caseNumber || "Unknown case";
  return (
    <tr tabIndex={0}>
      <td>
        <div className="workQueueCaseCell">
          <strong>{caseLabel}</strong>
        </div>
      </td>
      <td><WorkQueueBadge type="status" value={item.status} /></td>
      <td><WorkQueueBadge type="priority" value={item.priority} /></td>
      <td><WorkQueueBadge type="risk" value={item.riskLevel} /></td>
      <td>{item.assignedInvestigatorId || "Unassigned"}</td>
      <td><strong>{formatDurationFromSeconds(item.caseAgeSeconds)}</strong></td>
      <td>{formatAgeAgo(item.lastUpdatedAgeSeconds)}</td>
      <td>
        <WorkQueueBadge type="sla" value={item.slaStatus} />
        <span>{formatDateTime(item.slaDeadlineAt)}</span>
      </td>
      <td className="numericCell">{item.linkedAlertCount ?? 0}</td>
      <td>
        <button className="rowButton" type="button" onClick={() => onOpenCase(item.caseId)} aria-label={`Open fraud case ${caseLabel}`}>
          Open case
        </button>
      </td>
    </tr>
  );
}

function WorkQueueCard({ item, onOpenCase }) {
  const caseLabel = item.caseNumber || "Unknown case";
  return (
    <article className="workQueueCard">
      <div className="workQueueCardHeader">
        <strong>{caseLabel}</strong>
        <WorkQueueBadge type="sla" value={item.slaStatus} />
      </div>
      <dl>
        <div><dt>Status</dt><dd><WorkQueueBadge type="status" value={item.status} /></dd></div>
        <div><dt>Priority</dt><dd><WorkQueueBadge type="priority" value={item.priority} /></dd></div>
        <div><dt>Risk</dt><dd><WorkQueueBadge type="risk" value={item.riskLevel} /></dd></div>
        <div><dt>Assignee</dt><dd>{item.assignedInvestigatorId || "Unassigned"}</dd></div>
        <div><dt>Age</dt><dd>{formatDurationFromSeconds(item.caseAgeSeconds)}</dd></div>
        <div><dt>Updated</dt><dd>{formatAgeAgo(item.lastUpdatedAgeSeconds)}</dd></div>
        <div><dt>Alerts</dt><dd>{item.linkedAlertCount ?? 0}</dd></div>
      </dl>
      <button className="rowButton" type="button" onClick={() => onOpenCase(item.caseId)} aria-label={`Open fraud case ${caseLabel}`}>
        Open case
      </button>
    </article>
  );
}

function SkeletonRows() {
  return Array.from({ length: 5 }, (_, index) => (
    <tr className="skeletonRow" key={index}>
      {Array.from({ length: 10 }, (__, cellIndex) => (
        <td key={cellIndex}><span className="skeletonBlock" /></td>
      ))}
    </tr>
  ));
}

function SkeletonCards() {
  return Array.from({ length: 3 }, (_, index) => (
    <div className="workQueueCard skeletonCard" key={index}>
      <span className="skeletonBlock" />
      <span className="skeletonBlock" />
      <span className="skeletonBlock" />
    </div>
  ));
}

function isInvalidCursorError(error) {
  const text = `${error?.error || ""} ${error?.message || ""}`;
  return error?.status === 400 && (
    text.includes("INVALID_CURSOR") ||
    text.includes("INVALID_CURSOR_PAGE_COMBINATION")
  );
}

function workQueueStages(items) {
  return items.reduce((stages, item) => {
    if (["RESOLVED", "CLOSED"].includes(item.status)) {
      stages.readyToSubmit += 1;
    } else if (item.assignedInvestigatorId || ["IN_REVIEW", "ESCALATED"].includes(item.status)) {
      stages.inProgress += 1;
    } else {
      stages.unstarted += 1;
    }
    return stages;
  }, { unstarted: 0, inProgress: 0, readyToSubmit: 0 });
}

function activeFilterLabels(request) {
  const filters = [];
  addFilter(filters, "Status", request.status);
  addFilter(filters, "Priority", request.priority);
  addFilter(filters, "Risk", request.riskLevel);
  addFilter(filters, "Assignee", request.assignee, "set");
  addFilter(filters, "Created from", request.createdFrom, "set");
  addFilter(filters, "Created to", request.createdTo, "set");
  addFilter(filters, "Updated from", request.updatedFrom, "set");
  addFilter(filters, "Updated to", request.updatedTo, "set");
  addFilter(filters, "Linked alert", request.linkedAlertId, "set");
  return filters;
}

function addFilter(filters, label, value, displayValue = value) {
  if (value && value !== "ALL") {
    filters.push(`${label}: ${displayValue}`);
  }
}

function sortLabelFor(sort) {
  return FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS.find((option) => option.value === sort)?.label || sort || DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT;
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
