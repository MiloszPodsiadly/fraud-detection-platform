import { ApiError } from "./apiError.js";
import { authHeadersForSession } from "../auth/authHeaders.js";
import { getConfiguredAuthProvider } from "../auth/authProvider.js";

const API_BASE_URL = import.meta.env.VITE_ALERT_API_BASE_URL ?? "";
let activeSession = null;
let activeAuthProvider = getConfiguredAuthProvider();

export function setApiSession(session, authProvider = activeAuthProvider) {
  activeSession = session;
  activeAuthProvider = authProvider;
}

async function request(path, options = {}) {
  const {
    baseUrl = API_BASE_URL,
    includeAuth = true,
    headers = {},
    ...fetchOptions
  } = options;
  const authHeaders = includeAuth ? authHeadersForSession(activeAuthProvider, activeSession) : {};
  const response = await fetch(`${baseUrl}${path}`, {
    ...fetchOptions,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders,
      ...headers
    }
  });

  if (!response.ok) {
    const fallback = `Request failed with status ${response.status}`;
    try {
      const errorBody = await response.json();
      const details = errorBody.details || errorBody.validationErrors || [];
      const detailText = Array.isArray(details) && details.length > 0 ? ` ${details.join(" ")}` : "";
      throw new ApiError({
        status: response.status,
        error: errorBody.error,
        message: `${errorBody.message || fallback}${detailText}`,
        details
      });
    } catch (error) {
      if (error instanceof SyntaxError) {
        throw new ApiError({ status: response.status, message: fallback });
      }
      throw error;
    }
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

export function listAlerts({ page = 0, size = 10 } = {}) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size)
  });
  return request(`/api/v1/alerts?${params.toString()}`);
}

export function listFraudCases({ page = 0, size = 4 } = {}) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size)
  });
  return request(`/api/v1/fraud-cases?${params.toString()}`);
}

export function listFraudCaseWorkQueue({
  size = 20,
  cursor,
  status,
  priority,
  riskLevel,
  assignee,
  assignedInvestigatorId,
  createdFrom,
  createdTo,
  updatedFrom,
  updatedTo,
  linkedAlertId,
  sort = "createdAt,desc"
} = {}) {
  const params = new URLSearchParams();
  params.set("size", String(Math.min(Math.max(Number(size) || 20, 1), 100)));
  appendOptionalParam(params, "cursor", cursor);
  appendOptionalParam(params, "status", status);
  appendOptionalParam(params, "priority", priority);
  appendOptionalParam(params, "riskLevel", riskLevel);
  appendOptionalParam(params, "assignee", assignee);
  appendOptionalParam(params, "assignedInvestigatorId", assignedInvestigatorId);
  appendOptionalParam(params, "createdFrom", createdFrom);
  appendOptionalParam(params, "createdTo", createdTo);
  appendOptionalParam(params, "updatedFrom", updatedFrom);
  appendOptionalParam(params, "updatedTo", updatedTo);
  appendOptionalParam(params, "linkedAlertId", linkedAlertId);
  params.set("sort", sort || "createdAt,desc");

  return request(`/api/v1/fraud-cases/work-queue?${params.toString()}`);
}

export function listScoredTransactions({ page = 0, size = 25, query, riskLevel, status, classification } = {}) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size)
  });
  appendOptionalParam(params, "query", query);
  appendOptionalParam(params, "riskLevel", riskLevel);
  appendOptionalParam(params, "classification", classification || status);
  return request(`/api/v1/transactions/scored?${params.toString()}`);
}

export function listGovernanceAdvisories({ severity = "ALL", modelVersion = "", lifecycleStatus = "ALL", limit = 25 } = {}) {
  const params = new URLSearchParams({
    limit: String(limit)
  });
  if (severity && severity !== "ALL") {
    params.set("severity", severity);
  }
  if (modelVersion && modelVersion.trim()) {
    params.set("model_version", modelVersion.trim());
  }
  if (lifecycleStatus && lifecycleStatus !== "ALL") {
    params.set("lifecycle_status", lifecycleStatus);
  }
  return request(`/governance/advisories?${params.toString()}`);
}

export function getGovernanceAdvisoryAnalytics({ windowDays = 7 } = {}) {
  const params = new URLSearchParams({
    window_days: String(windowDays)
  });
  return request(`/governance/advisories/analytics?${params.toString()}`);
}

export function getGovernanceAdvisoryAudit(eventId) {
  return request(`/governance/advisories/${encodeURIComponent(eventId)}/audit`);
}

export function recordGovernanceAdvisoryAudit(eventId, audit) {
  return request(`/governance/advisories/${encodeURIComponent(eventId)}/audit`, {
    method: "POST",
    body: JSON.stringify(audit)
  });
}

export function getAlert(alertId) {
  return request(`/api/v1/alerts/${encodeURIComponent(alertId)}`);
}

export function getAssistantSummary(alertId) {
  return request(`/api/v1/alerts/${encodeURIComponent(alertId)}/assistant-summary`);
}

export function getFraudCase(caseId) {
  return request(`/api/v1/fraud-cases/${encodeURIComponent(caseId)}`);
}

export function updateFraudCase(caseId, decision, { idempotencyKey } = {}) {
  return request(`/api/v1/fraud-cases/${encodeURIComponent(caseId)}`, {
    method: "PATCH",
    headers: idempotencyKey ? { "X-Idempotency-Key": idempotencyKey } : {},
    body: JSON.stringify(decision)
  });
}

export function submitAnalystDecision(alertId, decision, { idempotencyKey } = {}) {
  return request(`/api/v1/alerts/${encodeURIComponent(alertId)}/decision`, {
    method: "POST",
    headers: idempotencyKey ? { "X-Idempotency-Key": idempotencyKey } : {},
    body: JSON.stringify(decision)
  });
}

function appendOptionalParam(params, name, value) {
  if (value === undefined || value === null) {
    return;
  }
  const normalized = typeof value === "string" ? value.trim() : String(value);
  if (!normalized || normalized === "ALL") {
    return;
  }
  params.set(name, normalized);
}
