import { ApiError } from "./apiError.js";
import { isAbortError } from "./apiErrors.js";
import { authHeadersForSession } from "../auth/authHeaders.js";
import { getConfiguredAuthProvider } from "../auth/authProvider.js";

export { isAbortError } from "./apiErrors.js";

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
    listSuspiciousTransactions: (requestParams, requestOptions) => listSuspiciousTransactionsWithRequest(request, requestParams, requestOptions),
    getSuspiciousTransactionSummary: (requestOptions) => request("/internal/suspicious-transactions/summary", requestOptions),
    getSuspiciousTransaction: (suspiciousTransactionId, requestOptions) => request(`/internal/suspicious-transactions/${encodeURIComponent(suspiciousTransactionId)}`, requestOptions),
    getSuspiciousTransactionLinkedAlertContext: (suspiciousTransactionId, requestOptions) => request(
      `/internal/suspicious-transactions/${encodeURIComponent(suspiciousTransactionId)}/linked-alert`,
      linkedAlertContextRequestOptions(requestOptions)
    ),
    listGovernanceAdvisories: (requestParams, requestOptions) => listGovernanceAdvisoriesWithRequest(request, requestParams, requestOptions),
    getGovernanceAdvisoryAnalytics: (requestParams, requestOptions) => getGovernanceAdvisoryAnalyticsWithRequest(request, requestParams, requestOptions),
    getGovernanceAdvisoryAudit: (eventId, requestOptions) => request(`/governance/advisories/${encodeURIComponent(eventId)}/audit`, requestOptions),
    recordGovernanceAdvisoryAudit: (eventId, audit, requestOptions = {}) => request(`/governance/advisories/${encodeURIComponent(eventId)}/audit`, {
      ...requestOptions,
      method: "POST",
      body: JSON.stringify(audit)
    }),
    getAlert: (alertId, requestOptions) => request(`/api/v1/alerts/${encodeURIComponent(alertId)}`, requestOptions),
    getAssistantSummary: (alertId, requestOptions) => request(`/api/v1/alerts/${encodeURIComponent(alertId)}/assistant-summary`, requestOptions),
    getFraudCase: (caseId, requestOptions) => request(`/api/v1/fraud-cases/${encodeURIComponent(caseId)}`, requestOptions),
    getFraudCaseEvidenceSummary: (caseId, requestOptions) => request(
      `/api/v1/fraud-cases/${encodeURIComponent(caseId)}/evidence-summary`,
      evidenceSummaryRequestOptions(requestOptions)
    ),
    getFraudCaseEvidenceTimeline: (caseId, requestOptions) => request(
      `/api/v1/fraud-cases/${encodeURIComponent(caseId)}/evidence-timeline`,
      evidenceTimelineRequestOptions(requestOptions)
    ),
    getEngineIntelligence: (transactionId, requestOptions) => getEngineIntelligenceWithRequest(request, transactionId, requestOptions),
    updateFraudCase: (caseId, decision, { idempotencyKey, signal } = {}) => request(`/api/v1/fraud-cases/${encodeURIComponent(caseId)}`, {
      method: "PATCH",
      signal,
      headers: idempotencyKey ? { "X-Idempotency-Key": idempotencyKey } : {},
      body: JSON.stringify(decision)
    }),
    submitAnalystDecision: (alertId, decision, { idempotencyKey, signal } = {}) => request(`/api/v1/alerts/${encodeURIComponent(alertId)}/decision`, {
      method: "POST",
      signal,
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
  const mergedHeaders = {
    "Content-Type": "application/json",
    ...authHeaders,
    ...headers
  };
  if (authProvider?.kind === "bff") {
    delete mergedHeaders.Authorization;
    delete mergedHeaders.authorization;
  }
  const response = await fetch(`${baseUrl}${path}`, {
    ...fetchOptions,
    ...(authProvider?.kind === "bff" ? { credentials: "same-origin" } : {}),
    headers: mergedHeaders
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

function listSuspiciousTransactionsWithRequest(request, {
  size = 20,
  cursor,
  status,
  riskLevel,
  customerId,
  linkedAlertId,
  detectedFrom,
  detectedTo
} = {}, { signal } = {}) {
  const params = new URLSearchParams();
  params.set("size", String(Math.min(Math.max(Number(size) || 20, 1), 100)));
  appendOptionalParam(params, "cursor", cursor);
  appendOptionalParam(params, "status", status);
  appendOptionalParam(params, "riskLevel", riskLevel);
  appendOptionalParam(params, "customerId", customerId);
  appendOptionalParam(params, "linkedAlertId", linkedAlertId);
  appendOptionalParam(params, "detectedFrom", toUtcInstantParam(detectedFrom));
  appendOptionalParam(params, "detectedTo", toUtcInstantParam(detectedTo));
  return request(`/internal/suspicious-transactions?${params.toString()}`, { signal });
}

function linkedAlertContextRequestOptions({ signal } = {}) {
  return {
    ...(signal ? { signal } : {})
  };
}

function evidenceSummaryRequestOptions({ signal } = {}) {
  return {
    ...(signal ? { signal } : {})
  };
}

function evidenceTimelineRequestOptions({ signal } = {}) {
  return {
    ...(signal ? { signal } : {})
  };
}

async function getEngineIntelligenceWithRequest(request, transactionId, requestOptions = {}) {
  const normalizedTransactionId = String(transactionId || "").trim();
  try {
    const response = await request(
      `/api/v1/transactions/scored/${encodeURIComponent(normalizedTransactionId)}/engine-intelligence`,
      engineIntelligenceRequestOptions(requestOptions)
    );
    return normalizeEngineIntelligenceResponse(response, normalizedTransactionId);
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    return engineIntelligenceFailureState(error, normalizedTransactionId);
  }
}

function engineIntelligenceRequestOptions({ signal } = {}) {
  return {
    ...(signal ? { signal } : {})
  };
}

function normalizeEngineIntelligenceResponse(response, fallbackTransactionId) {
  if (!response || typeof response !== "object") {
    return unavailableEngineIntelligence(fallbackTransactionId);
  }

  const transactionId = safeString(response.transactionId) || fallbackTransactionId;
  if (response.available === false && response.reason === "NOT_PROJECTED") {
    return Object.freeze({
      state: "not-projected",
      available: false,
      transactionId,
      reason: "NOT_PROJECTED"
    });
  }

  if (response.available !== true || !isValidComparison(response.comparison)) {
    return unavailableEngineIntelligence(transactionId);
  }

  const engines = normalizeEngineResults(response.engines);
  const diagnosticSignals = normalizeDiagnosticSignals(response.diagnosticSignals);
  const warnings = normalizeEngineWarnings(response.warnings);
  if (!engines || !diagnosticSignals || !warnings) {
    return unavailableEngineIntelligence(transactionId);
  }

  return Object.freeze({
    state: "available",
    available: true,
    transactionId,
    contractVersion: Number.isFinite(Number(response.contractVersion)) ? Number(response.contractVersion) : null,
    generatedAt: safeString(response.generatedAt),
    comparison: Object.freeze({
      agreementStatus: response.comparison.agreementStatus,
      riskMismatchStatus: response.comparison.riskMismatchStatus,
      scoreDeltaBucket: response.comparison.scoreDeltaBucket
    }),
    engineCount: engines.length,
    diagnosticSignalCount: diagnosticSignals.length,
    warningCount: warnings.length,
    engines: Object.freeze(engines),
    diagnosticSignals: Object.freeze(diagnosticSignals),
    warnings: Object.freeze(warnings)
  });
}

function engineIntelligenceFailureState(error, transactionId) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    return Object.freeze({ state: "unauthorized", available: false, transactionId });
  }
  if (error instanceof ApiError && error.status === 404) {
    return Object.freeze({ state: "not-found", available: false, transactionId });
  }
  return unavailableEngineIntelligence(transactionId);
}

function unavailableEngineIntelligence(transactionId) {
  return Object.freeze({
    state: "unavailable",
    available: false,
    transactionId
  });
}

function normalizeEngineResults(values) {
  if (!Array.isArray(values)) {
    return null;
  }
  const normalized = [];
  for (const value of values.slice(0, 2)) {
    const engineId = safeString(value?.engineId);
    const engineType = safeString(value?.engineType);
    const status = safeString(value?.status);
    const scoreBucket = safeString(value?.scoreBucket);
    if (!engineId || !engineType || !status || !scoreBucket) {
      return null;
    }
    normalized.push(Object.freeze({
      engineId,
      engineType,
      status,
      scoreBucket,
      riskLevel: safeString(value?.riskLevel),
      reasonCodes: Object.freeze(safeStringArray(value?.reasonCodes).slice(0, 5))
    }));
  }
  return normalized;
}

function normalizeDiagnosticSignals(values) {
  if (!Array.isArray(values)) {
    return null;
  }
  const normalized = [];
  for (const value of values.slice(0, 5)) {
    const signalType = safeString(value?.signalType) || safeString(value?.signalCategory);
    const scoreBucket = safeString(value?.scoreBucket);
    if (!signalType || !scoreBucket) {
      return null;
    }
    normalized.push(Object.freeze({
      signalType,
      engineId: safeString(value?.engineId),
      engineType: safeString(value?.engineType),
      engineStatus: safeString(value?.engineStatus),
      scoreBucket,
      riskLevel: safeString(value?.riskLevel),
      reasonCodes: Object.freeze([
        ...safeStringArray(value?.reasonCodes),
        safeString(value?.reasonCode)
      ].filter(Boolean).slice(0, 5))
    }));
  }
  return normalized;
}

function normalizeEngineWarnings(values) {
  if (!Array.isArray(values)) {
    return null;
  }
  const normalized = [];
  for (const value of values.slice(0, 10)) {
    const warningCode = safeString(value?.warningCode);
    if (!warningCode || !Number.isFinite(Number(value?.count))) {
      return null;
    }
    normalized.push(Object.freeze({
      warningCode,
      count: Number(value.count)
    }));
  }
  return normalized;
}

function isValidComparison(value) {
  return Boolean(
    value
      && typeof value === "object"
      && safeString(value.agreementStatus)
      && safeString(value.riskMismatchStatus)
      && safeString(value.scoreDeltaBucket)
  );
}

function safeString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function safeStringArray(values) {
  return Array.isArray(values) ? values.map(safeString).filter(Boolean) : [];
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
