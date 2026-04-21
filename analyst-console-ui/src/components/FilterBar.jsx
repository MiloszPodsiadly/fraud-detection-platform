const RISK_LEVELS = ["ALL", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const STATUSES = ["ALL", "OPEN", "IN_REVIEW", "ESCALATED", "RESOLVED", "CLOSED"];

export function FilterBar({ filters, onChange }) {
  function updateFilter(field, value) {
    onChange({ ...filters, [field]: value });
  }

  return (
    <div className="filterBar">
      <label>
        Search
        <input
          value={filters.query}
          onChange={(event) => updateFilter("query", event.target.value)}
          placeholder="Alert, transaction, customer, reason"
        />
      </label>
      <label>
        Risk
        <select value={filters.riskLevel} onChange={(event) => updateFilter("riskLevel", event.target.value)}>
          {RISK_LEVELS.map((riskLevel) => (
            <option key={riskLevel} value={riskLevel}>{riskLevel}</option>
          ))}
        </select>
      </label>
      <label>
        Status
        <select value={filters.status} onChange={(event) => updateFilter("status", event.target.value)}>
          {STATUSES.map((status) => (
            <option key={status} value={status}>{status}</option>
          ))}
        </select>
      </label>
    </div>
  );
}
