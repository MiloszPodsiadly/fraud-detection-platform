import { ApiError } from "./apiError.js";
import { demoAuthHeaders } from "../auth/demoSession.js";

const API_BASE_URL = import.meta.env.VITE_ALERT_API_BASE_URL ?? "";
let activeSession = null;

export function setApiSession(session) {
  activeSession = session;
}

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...demoAuthHeaders(activeSession),
      ...options.headers
    },
    ...options
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
