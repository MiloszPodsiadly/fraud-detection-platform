const BADGE_VALUES = {
  status: ["OPEN", "IN_REVIEW", "ESCALATED", "RESOLVED", "CLOSED", "REOPENED"],
  priority: ["LOW", "MEDIUM", "HIGH", "CRITICAL"],
  risk: ["LOW", "MEDIUM", "HIGH", "CRITICAL"],
  sla: ["WITHIN_SLA", "NEAR_BREACH", "BREACHED", "NOT_APPLICABLE"]
};

export function WorkQueueBadge({ type, value }) {
  const normalized = normalizeBadgeValue(type, value);
  return (
    <span className={`queueBadge queueBadge${type} queueBadge${type}${normalized}`}>
      {normalized.replaceAll("_", " ")}
    </span>
  );
}

function normalizeBadgeValue(type, value) {
  const normalized = String(value || "UNKNOWN").trim().toUpperCase();
  return BADGE_VALUES[type]?.includes(normalized) ? normalized : "UNKNOWN";
}
