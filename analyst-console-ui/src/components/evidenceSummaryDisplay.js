export function safeArray(value) {
  return Array.isArray(value) ? value : [];
}

export function normalizeEvidenceCode(value) {
  const normalized = normalizeText(value);
  if (!normalized) {
    return "UNKNOWN";
  }

  return /^[A-Z0-9_:-]{1,80}$/.test(normalized) ? normalized : "UNKNOWN";
}

export function safeTruncationReason(value) {
  return normalizeEvidenceCode(value) === "LINKED_ALERT_LIMIT_EXCEEDED"
    ? "LINKED_ALERT_LIMIT_EXCEEDED"
    : "Bounded summary limit reached";
}

export function formatCount(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) && numeric >= 0 ? String(numeric) : "0";
}

export function toCountItem(item, labelKey) {
  if (!item || typeof item !== "object" || Array.isArray(item)) {
    return { label: "UNKNOWN", count: "0" };
  }

  return {
    label: normalizeEvidenceCode(item[labelKey]),
    count: formatCount(item.count)
  };
}

function normalizeText(value) {
  return typeof value === "string" ? value.trim() : "";
}
