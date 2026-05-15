import { useEffect, useMemo, useRef, useState } from "react";
import {
  dedupeByCaseId,
  DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT,
  FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS,
  initialFraudCaseWorkQueueRequest
} from "../fraudCases/workQueueState.js";
import { WorkQueueActiveChips } from "./fraudCaseWorkQueue/WorkQueueActiveChips.jsx";
import { WorkQueueCards } from "./fraudCaseWorkQueue/WorkQueueCards.jsx";
import { WorkQueueErrorPanel } from "./fraudCaseWorkQueue/WorkQueueErrorPanel.jsx";
import { WorkQueueFilters } from "./fraudCaseWorkQueue/WorkQueueFilters.jsx";
import { WorkQueueFooter } from "./fraudCaseWorkQueue/WorkQueueFooter.jsx";
import { WorkQueueStageStats } from "./fraudCaseWorkQueue/WorkQueueStageStats.jsx";
import { WorkQueueTable } from "./fraudCaseWorkQueue/WorkQueueTable.jsx";

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
  onOpenCase,
  headingProps = {}
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

  return (
    <section className="panel workQueuePanel" id="work-queue" aria-labelledby="fraud-case-work-queue-title">
      <div className="panelHeader workQueuePanelHeader">
        <div>
          <p className="eyebrow">Fraud cases</p>
          <h2 id="fraud-case-work-queue-title" {...headingProps}>Fraud Case Work Queue</h2>
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
          <WorkQueueFilters draftRequest={effectiveDraft} onDraftChange={onDraftChange} />
        )}
        {validationError && <p className="formError">{validationError}</p>}

        <WorkQueueActiveChips activeFilters={activeFilters} appliedSortLabel={appliedSortLabel} />
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
          <WorkQueueTable items={items} isLoading={isLoading} onOpenCase={onOpenCase} />
          <WorkQueueCards items={items} isLoading={isLoading} onOpenCase={onOpenCase} />
          <WorkQueueFooter
            hasNext={queue.hasNext}
            isLoading={isLoading}
            appliedSortLabel={appliedSortLabel}
            onLoadMore={onLoadMore}
          />
        </>
      )}
    </section>
  );
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
