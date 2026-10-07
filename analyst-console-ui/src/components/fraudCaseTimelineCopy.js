export function timelineEventTitle(eventType) {
  switch (normalizeTimelineEventType(eventType)) {
    case "FRAUD_CASE_CREATED":
      return "Fraud case created";
    case "LINKED_ALERT_CONTEXT":
      return "Linked alert context";
    case "LINKED_ALERT_CONTEXT_UNAVAILABLE":
      return "Linked alert context unavailable";
    case "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE":
      return "Alert evidence snapshot available";
    case "ALERT_EVIDENCE_SNAPSHOT_PARTIAL":
      return "Alert evidence snapshot partial";
    case "ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE":
      return "Alert evidence snapshot unavailable";
    default:
      return "Timeline event";
  }
}

export function timelineEventDescription(eventType) {
  switch (normalizeTimelineEventType(eventType)) {
    case "FRAUD_CASE_CREATED":
      return "Read-only timeline event derived from existing fraud-case read data.";
    case "LINKED_ALERT_CONTEXT":
      return "Read-only linked alert context derived from existing alert read data.";
    case "LINKED_ALERT_CONTEXT_UNAVAILABLE":
      return "No linked alert context was available for this fraud case.";
    case "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE":
      return "Bounded evidence snapshot context derived from linked alert data.";
    case "ALERT_EVIDENCE_SNAPSHOT_PARTIAL":
      return "Bounded partial evidence snapshot context derived from linked alert data.";
    case "ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE":
      return "Structured evidence snapshot was unavailable for this linked alert.";
    default:
      return "Read-only timeline context derived from available read data.";
  }
}

export function timelineStateNotice(state) {
  switch (state) {
    case "empty":
      return "No evidence timeline events are available for this case.";
    case "partial":
      return "Partial timeline. Some linked evidence context is incomplete or unavailable.";
    case "truncated":
      return "Truncated timeline. Only the first bounded set of evidence timeline events was included.";
    case "unavailable":
    case "error":
      return "Evidence timeline unavailable.";
    default:
      return "";
  }
}

function normalizeTimelineEventType(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  return [
    "FRAUD_CASE_CREATED",
    "LINKED_ALERT_CONTEXT",
    "LINKED_ALERT_CONTEXT_UNAVAILABLE",
    "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE",
    "ALERT_EVIDENCE_SNAPSHOT_PARTIAL",
    "ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE"
  ].includes(normalized) ? normalized : "UNKNOWN";
}
