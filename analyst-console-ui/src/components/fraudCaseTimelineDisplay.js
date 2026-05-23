import { timelineEventDescription, timelineEventTitle } from "./fraudCaseTimelineCopy.js";

const TIMELINE_EVENT_TYPES = new Set([
  "FRAUD_CASE_CREATED",
  "LINKED_ALERT_CONTEXT",
  "ALERT_EVIDENCE_SNAPSHOT_AVAILABLE",
  "ALERT_EVIDENCE_SNAPSHOT_PARTIAL",
  "ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE",
  "LEGACY_CONTEXT"
]);

const TIMELINE_SOURCES = new Set([
  "FEATURE_ENRICHER",
  "FRAUD_SCORING_SERVICE",
  "ML_INFERENCE_SERVICE",
  "ALERT_SERVICE",
  "LEGACY_SCORING_PAYLOAD"
]);

const TIMELINE_EVIDENCE_STATUSES = new Set([
  "AVAILABLE",
  "PARTIAL",
  "UNAVAILABLE",
  "STALE",
  "ERROR",
  "NOT_APPLICABLE",
  "LEGACY",
  "UNKNOWN"
]);

const TIMELINE_LINKED_ENTITY_TYPES = new Set([
  "FRAUD_CASE",
  "FRAUD_ALERT",
  "EVIDENCE_SNAPSHOT",
  "LEGACY_CONTEXT",
  "UNKNOWN"
]);

export function safeTimelineArray(value) {
  return Array.isArray(value) ? value : [];
}

export function normalizeTimelineEventType(value) {
  return normalizeAllowedTimelineValue(value, TIMELINE_EVENT_TYPES);
}

export function normalizeTimelineSource(value) {
  return normalizeAllowedTimelineValue(value, TIMELINE_SOURCES);
}

export function normalizeTimelineEvidenceStatus(value) {
  return normalizeAllowedTimelineValue(value, TIMELINE_EVIDENCE_STATUSES);
}

export function normalizeTimelineLinkedEntityType(value) {
  return normalizeAllowedTimelineValue(value, TIMELINE_LINKED_ENTITY_TYPES);
}

function normalizeAllowedTimelineValue(value, allowedValues) {
  const normalized = typeof value === "string" ? value.trim() : "";
  if (!normalized) {
    return "UNKNOWN";
  }

  return allowedValues.has(normalized) ? normalized : "UNKNOWN";
}

export function safeTruncationReason(value) {
  return value === "TIMELINE_EVENT_LIMIT_EXCEEDED"
    ? "TIMELINE_EVENT_LIMIT_EXCEEDED"
    : "Bounded timeline window reached";
}

export function formatTimelineTime(occurredAt, approximateTime) {
  const date = typeof occurredAt === "string" || occurredAt instanceof Date ? new Date(occurredAt) : null;
  const validDate = date && Number.isFinite(date.getTime());
  if (!validDate) {
    return {
      label: "Time unavailable",
      qualifier: approximateTime === true ? "Approximate time" : null
    };
  }

  return {
    label: new Intl.DateTimeFormat(undefined, {
      dateStyle: "medium",
      timeStyle: "short"
    }).format(date),
    qualifier: approximateTime === true ? "Approximate time" : null
  };
}

export function toTimelineEvent(item) {
  const eventType = normalizeTimelineEventType(item?.eventType);
  return {
    renderKey: typeof item?.eventKey === "string" && item.eventKey.trim() ? item.eventKey : `${eventType}-fallback`,
    eventType,
    source: normalizeTimelineSource(item?.source),
    evidenceStatus: normalizeTimelineEvidenceStatus(item?.evidenceStatus),
    linkedEntityType: normalizeTimelineLinkedEntityType(item?.linkedEntityType),
    time: formatTimelineTime(item?.occurredAt, item?.approximateTime === true),
    title: timelineEventTitle(eventType),
    description: timelineEventDescription(eventType)
  };
}
