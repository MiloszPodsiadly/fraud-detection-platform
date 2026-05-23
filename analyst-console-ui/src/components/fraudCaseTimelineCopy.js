export function timelineEventTitle(eventType) {
  switch (normalizeTimelineCode(eventType)) {
    case "FRAUD_CASE_CREATED":
      return "Fraud case created";
    case "LINKED_ALERT_CONTEXT":
      return "Linked alert context";
    case "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE":
      return "Alert evidence snapshot available";
    case "ALERT_EVIDENCE_SNAPSHOT_PARTIAL":
      return "Alert evidence snapshot partial";
    case "ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE":
      return "Alert evidence snapshot unavailable";
    case "LEGACY_CONTEXT":
      return "Legacy case context";
    default:
      return "Timeline event";
  }
}

export function timelineEventDescription(eventType) {
  switch (normalizeTimelineCode(eventType)) {
    case "FRAUD_CASE_CREATED":
      return "Read-only timeline event derived from existing fraud-case read data.";
    case "LINKED_ALERT_CONTEXT":
      return "Read-only linked alert context derived from existing alert read data.";
    case "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE":
      return "Bounded evidence snapshot context derived from linked alert data.";
    case "ALERT_EVIDENCE_SNAPSHOT_PARTIAL":
      return "Bounded partial evidence snapshot context derived from linked alert data.";
    case "ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE":
      return "Structured evidence snapshot was unavailable for this linked alert.";
    case "LEGACY_CONTEXT":
      return "Legacy case may not have structured evidence timeline data.";
    default:
      return "Read-only timeline context derived from available read data.";
  }
}

export function timelineStateNotice(state) {
  switch (state) {
    case "empty":
      return "No evidence timeline events are available for this case.";
    case "legacy":
      return "Legacy context. This case may not have structured evidence timeline data.";
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

function normalizeTimelineCode(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  if (!normalized) {
    return "UNKNOWN";
  }

  return /^[A-Z0-9_:-]{1,80}$/.test(normalized) ? normalized : "UNKNOWN";
}
