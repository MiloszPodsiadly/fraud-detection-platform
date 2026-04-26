import { ApiError } from "./apiError.js";
import { authHeadersForSession } from "../auth/authHeaders.js";
import { getConfiguredAuthProvider } from "../auth/authProvider.js";

const API_BASE_URL = import.meta.env.VITE_ALERT_API_BASE_URL ?? "";
const ML_API_BASE_URL = import.meta.env.VITE_ML_API_BASE_URL ?? "";
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
    ...fetchOptions
  } = options;
  const authHeaders = includeAuth ? authHeadersForSession(activeAuthProvider, activeSession) : {};
  const response = await fetch(`${baseUrl}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...authHeaders,
      ...fetchOptions.headers
    },
    ...fetchOptions
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

export function listScoredTransactions({ page = 0, size = 25 } = {}) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size)
  });
  return request(`/api/v1/transactions/scored?${params.toString()}`);
}

export function listGovernanceAdvisories({ severity = "ALL", modelVersion = "", limit = 25 } = {}) {
  const params = new URLSearchParams({
    limit: String(limit)
  });
  if (severity && severity !== "ALL") {
    params.set("severity", severity);
  }
  if (modelVersion && modelVersion.trim()) {
    params.set("model_version", modelVersion.trim());
  }
  return request(`/governance/advisories?${params.toString()}`, {
    baseUrl: ML_API_BASE_URL,
    includeAuth: false
  });
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

export function updateFraudCase(caseId, decision) {
  return request(`/api/v1/fraud-cases/${encodeURIComponent(caseId)}`, {
    method: "PATCH",
    body: JSON.stringify(decision)
  });
}

export function submitAnalystDecision(alertId, decision) {
  return request(`/api/v1/alerts/${encodeURIComponent(alertId)}/decision`, {
    method: "POST",
    body: JSON.stringify(decision)
  });
}
