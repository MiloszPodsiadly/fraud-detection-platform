import { formatDateTime } from "../../utils/format.js";
import { formatAgeAgo, formatDurationFromSeconds } from "../../fraudCases/workQueueFormat.js";
import { WorkQueueBadge } from "./WorkQueueBadge.jsx";
import { WorkQueueEmptyState } from "./WorkQueueEmptyState.jsx";
import { WorkQueueSkeletonRows } from "./WorkQueueSkeleton.jsx";

export function WorkQueueTable({ items, isLoading, onOpenCase }) {
  return (
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
          {isLoading && items.length === 0 && <WorkQueueSkeletonRows />}
          {!isLoading && items.length === 0 && (
            <tr>
              <td colSpan={10}>
                <WorkQueueEmptyState />
              </td>
            </tr>
          )}
          {items.map((item) => (
            <WorkQueueRow item={item} key={item.caseId} onOpenCase={onOpenCase} />
          ))}
        </tbody>
      </table>
    </div>
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
