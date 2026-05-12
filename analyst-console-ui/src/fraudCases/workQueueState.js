export const DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT = "createdAt,desc";

export const FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS = [
  { value: "createdAt,desc", label: "Created newest" },
  { value: "createdAt,asc", label: "Created oldest" },
  { value: "updatedAt,desc", label: "Updated newest" },
  { value: "updatedAt,asc", label: "Updated oldest" },
  { value: "priority,desc", label: "Priority descending" },
  { value: "priority,asc", label: "Priority ascending" },
  { value: "riskLevel,desc", label: "Risk descending" },
  { value: "riskLevel,asc", label: "Risk ascending" },
  { value: "caseNumber,asc", label: "Case number A-Z" },
  { value: "caseNumber,desc", label: "Case number Z-A" }
];

export function initialFraudCaseWorkQueue() {
  return {
    content: [],
    size: 20,
    hasNext: false,
    nextCursor: null,
    sort: DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT,
    duplicateCaseIds: []
  };
}

export function initialFraudCaseWorkQueueRequest() {
  return {
    size: 20,
    status: "ALL",
    priority: "ALL",
    riskLevel: "ALL",
    assignee: "",
    createdFrom: "",
    createdTo: "",
    updatedFrom: "",
    updatedTo: "",
    linkedAlertId: "",
    sort: DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT,
    cursor: null
  };
}

export function mergeWorkQueueSlice(currentQueue, nextSlice, { append = false } = {}) {
  const incoming = Array.isArray(nextSlice?.content) ? nextSlice.content : [];
  const { content, duplicateCaseIds } = dedupeByCaseIdWithDuplicates(
    append ? [...(currentQueue.content || []), ...incoming] : incoming
  );
  const existingDuplicateCaseIds = append ? currentQueue.duplicateCaseIds || [] : [];
  return {
    content,
    size: nextSlice?.size ?? currentQueue.size ?? 20,
    hasNext: Boolean(nextSlice?.hasNext),
    nextCursor: nextSlice?.nextCursor || null,
    sort: nextSlice?.sort || currentQueue.sort || DEFAULT_FRAUD_CASE_WORK_QUEUE_SORT,
    duplicateCaseIds: [...new Set([...existingDuplicateCaseIds, ...duplicateCaseIds])]
  };
}

export function resetWorkQueueRequestForFilterChange(current, patch) {
  return {
    ...current,
    ...patch,
    cursor: null,
    size: Math.min(Math.max(Number(patch.size ?? current.size) || 20, 1), 100)
  };
}

export function isInvalidWorkQueueCursorError(error) {
  const text = `${error?.error || ""} ${error?.message || ""}`;
  return error?.status === 400 && (
    text.includes("INVALID_CURSOR") ||
    text.includes("INVALID_CURSOR_PAGE_COMBINATION")
  );
}

export function dedupeByCaseId(items) {
  return dedupeByCaseIdWithDuplicates(items).content;
}

function dedupeByCaseIdWithDuplicates(items) {
  const seen = new Set();
  const duplicateCaseIds = [];
  const content = items.filter((item) => {
    const key = item?.caseId;
    if (!key) {
      return true;
    }
    if (seen.has(key)) {
      duplicateCaseIds.push(key);
      return false;
    }
    seen.add(key);
    return true;
  });
  return { content, duplicateCaseIds };
}
