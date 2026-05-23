import { timelineEventDescription, timelineEventTitle } from "./fraudCaseTimelineCopy.js";

export function safeTimelineArray(value) {
  return Array.isArray(value) ? value : [];
}

export function normalizeTimelineCode(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  if (!normalized) {
    return "UNKNOWN";
  }

  return /^[A-Z0-9_:-]{1,80}$/.test(normalized) ? normalized : "UNKNOWN";
}

export function safeTruncationReason(value) {
  return normalizeTimelineCode(value) === "TIMELINE_EVENT_LIMIT_EXCEEDED"
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
  const eventType = normalizeTimelineCode(item?.eventType);
  return {
    renderKey: typeof item?.eventKey === "string" && item.eventKey.trim() ? item.eventKey : `${eventType}-fallback`,
    eventType,
    source: normalizeTimelineCode(item?.source),
    evidenceStatus: normalizeTimelineCode(item?.evidenceStatus),
    linkedEntityType: normalizeTimelineCode(item?.linkedEntityType),
    time: formatTimelineTime(item?.occurredAt, item?.approximateTime === true),
    title: timelineEventTitle(eventType),
    description: timelineEventDescription(eventType)
  };
}
