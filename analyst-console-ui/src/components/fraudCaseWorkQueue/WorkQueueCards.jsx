import { formatAgeAgo, formatDurationFromSeconds } from "../../fraudCases/workQueueFormat.js";
import { WorkQueueBadge } from "./WorkQueueBadge.jsx";
import { WorkQueueEmptyState } from "./WorkQueueEmptyState.jsx";
import { WorkQueueSkeletonCards } from "./WorkQueueSkeleton.jsx";

export function WorkQueueCards({ items, isLoading, onOpenCase }) {
  return (
    <div className="workQueueCards" aria-label="Fraud case work queue cards">
      {isLoading && items.length === 0 && <WorkQueueSkeletonCards />}
      {!isLoading && items.length === 0 && <WorkQueueEmptyState />}
      {items.map((item) => (
        <WorkQueueCard item={item} key={item.caseId} onOpenCase={onOpenCase} />
      ))}
    </div>
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
      <button
        className="rowButton"
        type="button"
        data-detail-origin={`fraud-case-${item.caseId}`}
        onClick={() => onOpenCase(item.caseId)}
        aria-label={`Open fraud case ${caseLabel}`}
      >
        Open case
      </button>
    </article>
  );
}
