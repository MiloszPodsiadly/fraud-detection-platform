import {
  DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT,
  FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS,
  initialFraudCaseWorkQueueRequest
} from "../../fraudCases/workQueueState.js";

const STATUS_OPTIONS = ["ALL", "OPEN", "IN_REVIEW", "ESCALATED", "RESOLVED", "CLOSED", "REOPENED"];
const PRIORITY_OPTIONS = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const RISK_OPTIONS = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];

export function WorkQueueFilters({ draftRequest, onDraftChange }) {
  const effectiveDraft = draftRequest || initialFraudCaseWorkQueueRequest();
  const updateField = (name, value) => onDraftChange(name, value);

  return (
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
        <select value={effectiveDraft.sort || DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT} onChange={(event) => updateField("sort", event.target.value)}>
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
  );
}
