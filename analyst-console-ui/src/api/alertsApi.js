import { ApiError } from "./apiError.js";
import { authHeadersForSession } from "../auth/authHeaders.js";
import { getConfiguredAuthProvider } from "../auth/authProvider.js";

const API_BASE_URL = import.meta.env.VITE_ALERT_API_BASE_URL ?? "";

export function createAlertsApiClient({
  session = null,
  authProvider = getConfiguredAuthProvider(),
  baseUrl = API_BASE_URL
} = {}) {
  const request = (path, options = {}) => requestWithContext(path, options, {
    baseUrl,
    session,
    authProvider
  });

  return Object.freeze({
    listAlerts: (requestParams, requestOptions) => listAlertsWithRequest(request, requestParams, requestOptions),
    listFraudCaseWorkQueue: (requestParams, requestOptions) => listFraudCaseWorkQueueWithRequest(request, requestParams, requestOptions),
    getFraudCaseWorkQueueSummary: (requestOptions) => request("/api/v1/fraud-cases/work-queue/summary", requestOptions),
    listScoredTransactions: (requestParams, requestOptions) => listScoredTransactionsWithRequest(request, requestParams, requestOptions),
    listGovernanceAdvisories: (requestParams, requestOptions) => listGovernanceAdvisoriesWithRequest(request, requestParams, requestOptions),
    getGovernanceAdvisoryAnalytics: (requestParams, requestOptions) => getGovernanceAdvisoryAnalyticsWithRequest(request, requestParams, requestOptions),
    getGovernanceAdvisoryAudit: (eventId, requestOptions) => request(`/governance/advisories/${encodeURIComponent(eventId)}/audit`, requestOptions),
    recordGovernanceAdvisoryAudit: (eventId, audit) => request(`/governance/advisories/${encodeURIComponent(eventId)}/audit`, {
      method: "POST",
      body: JSON.stringify(audit)
    }),
    getAlert: (alertId) => request(`/api/v1/alerts/${encodeURIComponent(alertId)}`),
    getAssistantSummary: (alertId) => request(`/api/v1/alerts/${encodeURIComponent(alertId)}/assistant-summary`),
    getFraudCase: (caseId) => request(`/api/v1/fraud-cases/${encodeURIComponent(caseId)}`),
    updateFraudCase: (caseId, decision, { idempotencyKey } = {}) => request(`/api/v1/fraud-cases/${encodeURIComponent(caseId)}`, {
      method: "PATCH",
      headers: idempotencyKey ? { "X-Idempotency-Key": idempotencyKey } : {},
      body: JSON.stringify(decision)
    }),
    submitAnalystDecision: (alertId, decision, { idempotencyKey } = {}) => request(`/api/v1/alerts/${encodeURIComponent(alertId)}/decision`, {
      method: "POST",
      headers: idempotencyKey ? { "X-Idempotency-Key": idempotencyKey } : {},
      body: JSON.stringify(decision)
    })
  });
}

async function requestWithContext(path, options = {}, context = {}) {
  const {
    includeAuth = true,
    headers = {},
    ...fetchOptions
  } = options;
  const {
    baseUrl = API_BASE_URL,
    session = null,
    authProvider = getConfiguredAuthProvider()
  } = context;
  const authHeaders = includeAuth ? authHeadersForSession(authProvider, session) : {};
  const credentialOptions = authProvider?.kind === "bff"
    ? { credentials: fetchOptions.credentials || "same-origin" }
    : {};
  let response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      ...credentialOptions,
      ...fetchOptions,
      headers: {
        "Content-Type": "application/json",
        ...authHeaders,
        ...headers
      }
    });
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    throw error;
  }

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

export function isAbortError(error) {
  return error?.name === "AbortError";
}

const defaultApiClient = createAlertsApiClient();

export function listAlerts(requestParams, requestOptions) {
  return defaultApiClient.listAlerts(requestParams, requestOptions);
}

export function listFraudCaseWorkQueue(requestParams, requestOptions) {
  return defaultApiClient.listFraudCaseWorkQueue(requestParams, requestOptions);
}

export function getFraudCaseWorkQueueSummary(requestOptions) {
  return defaultApiClient.getFraudCaseWorkQueueSummary(requestOptions);
}

export function listScoredTransactions(requestParams, requestOptions) {
  return defaultApiClient.listScoredTransactions(requestParams, requestOptions);
}

export function listGovernanceAdvisories(requestParams, requestOptions) {
  return defaultApiClient.listGovernanceAdvisories(requestParams, requestOptions);
}

export function getGovernanceAdvisoryAnalytics(requestParams, requestOptions) {
  return defaultApiClient.getGovernanceAdvisoryAnalytics(requestParams, requestOptions);
}

export function getGovernanceAdvisoryAudit(eventId, requestOptions) {
  return defaultApiClient.getGovernanceAdvisoryAudit(eventId, requestOptions);
}

export function recordGovernanceAdvisoryAudit(eventId, audit) {
  return defaultApiClient.recordGovernanceAdvisoryAudit(eventId, audit);
}

export function getAlert(alertId) {
  return defaultApiClient.getAlert(alertId);
}

export function getAssistantSummary(alertId) {
  return defaultApiClient.getAssistantSummary(alertId);
}

export function getFraudCase(caseId) {
  return defaultApiClient.getFraudCase(caseId);
}

export function updateFraudCase(caseId, decision, options) {
  return defaultApiClient.updateFraudCase(caseId, decision, options);
}

export function submitAnalystDecision(alertId, decision, options) {
  return defaultApiClient.submitAnalystDecision(alertId, decision, options);
}

function listAlertsWithRequest(request, { page = 0, size = 10 } = {}, { signal } = {}) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size)
  });
  return request(`/api/v1/alerts?${params.toString()}`, { signal });
}

function listFraudCaseWorkQueueWithRequest(request, {
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
} = {}, { signal } = {}) {
  const params = new URLSearchParams();
  params.set("size", String(Math.min(Math.max(Number(size) || 20, 1), 100)));
  appendOptionalParam(params, "cursor", cursor);
  appendOptionalParam(params, "status", status);
  appendOptionalParam(params, "priority", priority);
  appendOptionalParam(params, "riskLevel", riskLevel);
  appendOptionalParam(params, "assignee", assignee);
  appendOptionalParam(params, "assignedInvestigatorId", assignedInvestigatorId);
  appendOptionalParam(params, "createdFrom", toUtcInstantParam(createdFrom));
  appendOptionalParam(params, "createdTo", toUtcInstantParam(createdTo));
  appendOptionalParam(params, "updatedFrom", toUtcInstantParam(updatedFrom));
  appendOptionalParam(params, "updatedTo", toUtcInstantParam(updatedTo));
  appendOptionalParam(params, "linkedAlertId", linkedAlertId);
  params.set("sort", sort || "createdAt,desc");

  return request(`/api/v1/fraud-cases/work-queue?${params.toString()}`, { signal });
}

function listScoredTransactionsWithRequest(request, { page = 0, size = 25, query, riskLevel, status, classification } = {}, { signal } = {}) {
  const params = new URLSearchParams({
    page: String(Math.min(Math.max(Number(page) || 0, 0), 1000)),
    size: String(Math.min(Math.max(Number(size) || 25, 1), 100))
  });
  appendOptionalParam(params, "query", query);
  appendOptionalParam(params, "riskLevel", riskLevel);
  appendOptionalParam(params, "classification", classification || status);
  return request(`/api/v1/transactions/scored?${params.toString()}`, { signal });
}

function listGovernanceAdvisoriesWithRequest(request, { severity = "ALL", modelVersion = "", lifecycleStatus = "ALL", limit = 25 } = {}, { signal } = {}) {
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
  return request(`/governance/advisories?${params.toString()}`, { signal });
}

function getGovernanceAdvisoryAnalyticsWithRequest(request, { windowDays = 7 } = {}, { signal } = {}) {
  const params = new URLSearchParams({
    window_days: String(windowDays)
  });
  return request(`/governance/advisories/analytics?${params.toString()}`, { signal });
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

export function toUtcInstantParam(value) {
  if (value === undefined || value === null || String(value).trim() === "") {
    return null;
  }
  const date = new Date(String(value).trim());
  if (Number.isNaN(date.getTime())) {
    throw new ApiError({
      status: 400,
      error: "INVALID_LOCAL_DATETIME",
      message: "Invalid local date filter."
    });
  }
  return date.toISOString();
}
