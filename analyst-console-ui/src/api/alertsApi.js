import { ApiError } from "./apiError.js";
import { isAbortError } from "./apiErrors.js";
import { authHeadersForSession } from "../auth/authHeaders.js";
import { getConfiguredAuthProvider } from "../auth/authProvider.js";
import { isValidPromotionReviewReadinessReport } from "../governance/promotionReviewReadinessReportValidation.js";

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
    getScoredTransactionDetail: (transactionId, requestOptions) => getScoredTransactionDetailWithRequest(request, transactionId, requestOptions),
    getFraudFeedback: (transactionId, requestOptions) => getFraudFeedbackWithRequest(request, transactionId, requestOptions),
    createFraudFeedback: (transactionId, feedback, requestOptions) => createFraudFeedbackWithRequest(request, transactionId, feedback, requestOptions),
    listSuspiciousTransactions: (requestParams, requestOptions) => listSuspiciousTransactionsWithRequest(request, requestParams, requestOptions),
    getSuspiciousTransactionSummary: (requestOptions) => request("/internal/suspicious-transactions/summary", requestOptions),
    getSuspiciousTransaction: (suspiciousTransactionId, requestOptions) => request(`/internal/suspicious-transactions/${encodeURIComponent(suspiciousTransactionId)}`, requestOptions),
    getSuspiciousTransactionLinkedAlertContext: (suspiciousTransactionId, requestOptions) => request(
      `/internal/suspicious-transactions/${encodeURIComponent(suspiciousTransactionId)}/linked-alert`,
      linkedAlertContextRequestOptions(requestOptions)
    ),
    getCurrentShadowPerformanceSummary: (requestOptions) => shadowPerformanceSummaryRequest(request, requestOptions),
    getCurrentPromotionReviewReadinessReport: (requestOptions) => promotionReviewReadinessReportRequest(request, requestOptions),
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
    submitEngineIntelligenceFeedback: (transactionId, feedback, requestOptions) =>
      submitEngineIntelligenceFeedbackWithRequest(request, transactionId, feedback, requestOptions),
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

function getScoredTransactionDetailWithRequest(request, transactionId, requestOptions = {}) {
  const normalizedTransactionId = normalizeEngineIntelligenceTransactionId(transactionId);
  if (!isValidEngineIntelligenceTransactionId(normalizedTransactionId)) {
    return Promise.reject(new ApiError({
      status: 400,
      error: "INVALID_TRANSACTION_ID",
      message: "Invalid scored transaction identifier."
    }));
  }
  return request(
    `/api/v1/transactions/scored/${encodeURIComponent(normalizedTransactionId)}`,
    scoredTransactionDetailRequestOptions(requestOptions)
  );
}

function scoredTransactionDetailRequestOptions({ signal } = {}) {
  return {
    ...(signal ? { signal } : {})
  };
}

function getFraudFeedbackWithRequest(request, transactionId, requestOptions = {}) {
  const normalizedTransactionId = normalizeEngineIntelligenceTransactionId(transactionId);
  if (!isValidEngineIntelligenceTransactionId(normalizedTransactionId)) {
    return Promise.reject(new ApiError({
      status: 400,
      error: "INVALID_TRANSACTION_ID",
      message: "Invalid scored transaction identifier."
    }));
  }
  return request(
    `/api/v1/transactions/scored/${encodeURIComponent(normalizedTransactionId)}/feedback`,
    scoredTransactionDetailRequestOptions(requestOptions)
  );
}

function createFraudFeedbackWithRequest(request, transactionId, feedback, { signal } = {}) {
  const normalizedTransactionId = normalizeEngineIntelligenceTransactionId(transactionId);
  if (!isValidEngineIntelligenceTransactionId(normalizedTransactionId)) {
    return Promise.reject(new ApiError({
      status: 400,
      error: "INVALID_TRANSACTION_ID",
      message: "Invalid scored transaction identifier."
    }));
  }
  return request(`/api/v1/transactions/scored/${encodeURIComponent(normalizedTransactionId)}/feedback`, {
    method: "POST",
    signal,
    body: JSON.stringify(feedback)
  });
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

function shadowPerformanceSummaryRequest(request, { signal } = {}) {
  return request("/api/v1/governance/shadow-performance/summary/current", {
    ...(signal ? { signal } : {})
  });
}

async function promotionReviewReadinessReportRequest(request, { signal } = {}) {
  const response = await request("/api/v1/governance/promotion-review-readiness/current", {
    ...(signal ? { signal } : {})
  });
  if (!isValidPromotionReviewReadinessReport(response)) {
    return Object.freeze({ state: "invalid-response" });
  }
  return response;
}

const ENGINE_INTELLIGENCE_TRANSACTION_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const MAX_ENGINE_INTELLIGENCE_ENGINES = 2;
const MAX_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNALS = 5;
const MAX_ENGINE_INTELLIGENCE_WARNINGS = 10;
const MAX_ENGINE_INTELLIGENCE_REASON_CODES = 5;
const AGREEMENT_STATUSES = new Set([
  "AGREEMENT",
  "ADJACENT_RISK_VARIANCE",
  "DISAGREEMENT",
  "PARTIAL",
  "INSUFFICIENT_DATA",
  "REQUIRED_ENGINE_NOT_COMPARABLE"
]);
const RISK_MISMATCH_STATUSES = new Set([
  "SAME_RISK_LEVEL",
  "ADJACENT_RISK_LEVEL",
  "MATERIAL_RISK_MISMATCH",
  "NOT_COMPARABLE"
]);
const SCORE_DELTA_BUCKETS = new Set(["NONE", "SMALL", "MEDIUM", "LARGE", "UNAVAILABLE"]);
const ENGINE_STATUSES = new Set(["AVAILABLE", "UNAVAILABLE", "DEGRADED", "TIMEOUT", "FALLBACK_USED", "SKIPPED"]);
const SCORE_BUCKETS = new Set(["NONE", "LOW", "MEDIUM", "HIGH", "VERY_HIGH", "UNAVAILABLE"]);
const SIGNAL_CATEGORIES = new Set(["FRAUD_SIGNAL", "OPERATIONAL_SIGNAL"]);
const ENGINE_TYPES = new Set(["RULES", "ML_MODEL"]);
const RISK_LEVELS = new Set(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
const ENGINE_INTELLIGENCE_FEEDBACK_TYPES = new Set([
  "ENGINE_INTELLIGENCE_USEFULNESS",
  "ENGINE_DISAGREEMENT_REVIEW",
  "OPERATIONAL_STATUS_REVIEW",
  "MISSING_INTELLIGENCE_REVIEW"
]);
const ENGINE_INTELLIGENCE_FEEDBACK_USEFULNESS = new Set([
  "HELPFUL",
  "SOMEWHAT_HELPFUL",
  "NOT_HELPFUL",
  "NOT_SURE"
]);
const ENGINE_INTELLIGENCE_FEEDBACK_ACCURACY = new Set([
  "SIGNALS_LOOK_CORRECT",
  "SIGNALS_LOOK_PARTIALLY_CORRECT",
  "SIGNALS_LOOK_INCORRECT",
  "NOT_ENOUGH_INFORMATION",
  "OPERATIONAL_ISSUE_AFFECTED_REVIEW"
]);
const WARNING_CODES = new Set([
  "ENGINE_RESULT_LIMIT_APPLIED",
  "REASON_CODE_NULL_DROPPED",
  "REASON_CODE_BLANK_DROPPED",
  "REASON_CODE_UNSUPPORTED_DROPPED",
  "REASON_CODE_LIMIT_APPLIED",
  "EVIDENCE_LIMIT_APPLIED",
  "EVIDENCE_TEXT_TRUNCATED",
  "EVIDENCE_UNSAFE_DROPPED",
  "EVIDENCE_UNSUPPORTED_REASON_CODE_DROPPED",
  "CONTRIBUTION_LIMIT_APPLIED",
  "CONTRIBUTION_TEXT_TRUNCATED",
  "CONTRIBUTION_UNSAFE_DROPPED",
  "CONTRIBUTION_VALUE_DROPPED"
]);
const FORBIDDEN_ENGINE_INTELLIGENCE_TERMS = [
  "rawEvidence",
  "rawContribution",
  "featureSnapshot",
  "featureVector",
  "rawPayload",
  "payload",
  "endpoint",
  "token",
  "secret",
  "stacktrace",
  "exceptionMessage",
  "internalAggregation",
  "EngineIntelligenceProjection",
  "FraudEngine" + "AggregationResult",
  "NormalizedFraudEngine" + "Result",
  "Scoring" + "Context",
  "rawMlResponse",
  "platformVerdict",
  "finalDecision",
  "recommendedAction",
  "approve",
  "decline",
  "block",
  "winningEngine",
  "paymentAuthorization",
  "modelTrainingLabel",
  "groundTruth",
  "ruleUpdate"
];
const FORBIDDEN_COMPACT_ENGINE_INTELLIGENCE_TERMS = FORBIDDEN_ENGINE_INTELLIGENCE_TERMS
  .map((term) => compactEngineIntelligenceText(term));

async function getEngineIntelligenceWithRequest(request, transactionId, requestOptions = {}) {
  const normalizedTransactionId = normalizeEngineIntelligenceTransactionId(transactionId);
  if (!isValidEngineIntelligenceTransactionId(normalizedTransactionId)) {
    return Object.freeze({
      state: "not-found",
      available: false,
      transactionId: normalizedTransactionId
    });
  }
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

async function submitEngineIntelligenceFeedbackWithRequest(request, transactionId, feedback, { idempotencyKey, signal } = {}) {
  const normalizedTransactionId = normalizeEngineIntelligenceTransactionId(transactionId);
  const payload = normalizeEngineIntelligenceFeedbackPayload(feedback);
  if (!isValidEngineIntelligenceTransactionId(normalizedTransactionId) || !payload || !safeString(idempotencyKey)) {
    return Object.freeze({ state: "validation-error" });
  }
  try {
    const response = await request(
      `/api/v1/transactions/scored/${encodeURIComponent(normalizedTransactionId)}/engine-intelligence/feedback`,
      {
        method: "POST",
        signal,
        headers: { "X-Idempotency-Key": idempotencyKey },
        body: JSON.stringify(payload)
      }
    );
    return normalizeEngineIntelligenceFeedbackResponse(response);
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    return engineIntelligenceFeedbackFailureState(error);
  }
}

function normalizeEngineIntelligenceFeedbackPayload(feedback) {
  if (!feedback || typeof feedback !== "object") {
    return null;
  }
  const feedbackType = normalizedAllowedValue(feedback.feedbackType, ENGINE_INTELLIGENCE_FEEDBACK_TYPES);
  const usefulness = normalizedAllowedValue(feedback.usefulness, ENGINE_INTELLIGENCE_FEEDBACK_USEFULNESS);
  const accuracyAssessment = normalizedAllowedValue(feedback.accuracyAssessment, ENGINE_INTELLIGENCE_FEEDBACK_ACCURACY);
  if (!feedbackType || !usefulness || !accuracyAssessment || typeof feedback.engineIntelligenceAvailable !== "boolean") {
    return null;
  }
  const selectedReasonCodes = feedback.selectedReasonCodes === undefined
    ? []
    : normalizeReasonCodes(feedback.selectedReasonCodes);
  if (!selectedReasonCodes) {
    return null;
  }
  return Object.freeze({
    feedbackType,
    usefulness,
    accuracyAssessment,
    engineIntelligenceAvailable: feedback.engineIntelligenceAvailable,
    selectedReasonCodes
  });
}

function normalizeEngineIntelligenceFeedbackResponse(response) {
  if (!response || typeof response !== "object") {
    return Object.freeze({ state: "unavailable" });
  }
  if (response.operationStatus === "CREATED" || response.operationStatus === "EXISTING") {
    return Object.freeze({
      state: "saved",
      operationStatus: response.operationStatus,
      feedbackId: safeString(response.feedbackId),
      transactionId: safeString(response.transactionId)
    });
  }
  return Object.freeze({ state: "unavailable" });
}

function engineIntelligenceFeedbackFailureState(error) {
  if (error instanceof ApiError && (error.status === 400 || error.status === 422)) {
    return Object.freeze({ state: "validation-error" });
  }
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    return Object.freeze({ state: "unauthorized" });
  }
  if (error instanceof ApiError && error.status === 404) {
    return Object.freeze({ state: "not-found" });
  }
  if (error instanceof ApiError && error.status === 409) {
    return Object.freeze({ state: "validation-error" });
  }
  return Object.freeze({ state: "unavailable" });
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

  const transactionId = safeRenderableString(response.transactionId) || fallbackTransactionId;
  if (!isValidEngineIntelligenceTransactionId(transactionId)) {
    return unavailableEngineIntelligence(fallbackTransactionId);
  }
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
      agreementStatus: normalizedAllowedValue(response.comparison.agreementStatus, AGREEMENT_STATUSES),
      riskMismatchStatus: normalizedAllowedValue(response.comparison.riskMismatchStatus, RISK_MISMATCH_STATUSES),
      scoreDeltaBucket: normalizedAllowedValue(response.comparison.scoreDeltaBucket, SCORE_DELTA_BUCKETS)
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
  if (values.length > MAX_ENGINE_INTELLIGENCE_ENGINES) {
    return null;
  }
  const normalized = [];
  for (const value of values) {
    const engineId = safeRenderableString(value?.engineId);
    const engineType = normalizedAllowedValue(value?.engineType, ENGINE_TYPES);
    const status = normalizedAllowedValue(value?.status, ENGINE_STATUSES);
    const scoreBucket = normalizedAllowedValue(value?.scoreBucket, SCORE_BUCKETS);
    if (!engineId || !engineType || !status || !scoreBucket) {
      return null;
    }
    const riskLevel = normalizedOptionalAllowedValue(value?.riskLevel, RISK_LEVELS);
    if (riskLevel === null) {
      return null;
    }
    const reasonCodes = normalizeReasonCodes(value?.reasonCodes);
    if (!reasonCodes || !isEngineResultOperationallyConsistent(status, scoreBucket, riskLevel)) {
      return null;
    }
    normalized.push(Object.freeze({
      engineId,
      engineType,
      status,
      scoreBucket,
      riskLevel,
      reasonCodes: Object.freeze(reasonCodes)
    }));
  }
  return normalized;
}

function normalizeDiagnosticSignals(values) {
  if (!Array.isArray(values)) {
    return null;
  }
  if (values.length > MAX_ENGINE_INTELLIGENCE_DIAGNOSTIC_SIGNALS) {
    return null;
  }
  const normalized = [];
  for (const value of values) {
    const signalCategory = normalizedAllowedValue(value?.signalCategory, SIGNAL_CATEGORIES);
    const engineId = safeRenderableString(value?.engineId);
    const engineType = normalizedAllowedValue(value?.engineType, ENGINE_TYPES);
    const engineStatus = normalizedAllowedValue(value?.engineStatus, ENGINE_STATUSES);
    const scoreBucket = normalizedAllowedValue(value?.scoreBucket, SCORE_BUCKETS);
    if (!signalCategory || !engineId || !engineType || !engineStatus || !scoreBucket) {
      return null;
    }
    const riskLevel = normalizedOptionalAllowedValue(value?.riskLevel, RISK_LEVELS);
    if (riskLevel === null) {
      return null;
    }
    if (value?.reasonCodes !== undefined && !Array.isArray(value.reasonCodes)) {
      return null;
    }
    const reasonCodes = normalizeReasonCodes([
      ...safeStringArray(value?.reasonCodes),
      safeString(value?.reasonCode)
    ].filter(Boolean));
    if (!reasonCodes || !isDiagnosticSignalOperationallyConsistent(signalCategory, engineStatus, scoreBucket, riskLevel)) {
      return null;
    }
    normalized.push(Object.freeze({
      signalCategory,
      engineId,
      engineType,
      engineStatus,
      scoreBucket,
      riskLevel,
      reasonCodes: Object.freeze(reasonCodes)
    }));
  }
  return normalized;
}

function normalizeEngineWarnings(values) {
  if (!Array.isArray(values)) {
    return null;
  }
  if (values.length > MAX_ENGINE_INTELLIGENCE_WARNINGS) {
    return null;
  }
  const normalized = [];
  for (const value of values) {
    const warningCode = normalizedAllowedValue(value?.warningCode, WARNING_CODES);
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
  const agreementStatus = normalizedAllowedValue(value?.agreementStatus, AGREEMENT_STATUSES);
  const riskMismatchStatus = normalizedAllowedValue(value?.riskMismatchStatus, RISK_MISMATCH_STATUSES);
  const scoreDeltaBucket = normalizedAllowedValue(value?.scoreDeltaBucket, SCORE_DELTA_BUCKETS);
  return Boolean(
    value
      && typeof value === "object"
      && agreementStatus
      && riskMismatchStatus
      && scoreDeltaBucket
  );
}

function normalizeEngineIntelligenceTransactionId(transactionId) {
  return transactionId === null || transactionId === undefined ? "" : String(transactionId).trim();
}

function isValidEngineIntelligenceTransactionId(transactionId) {
  return ENGINE_INTELLIGENCE_TRANSACTION_ID_PATTERN.test(transactionId)
    && !containsForbiddenEngineIntelligenceTerm(transactionId);
}

function normalizedAllowedValue(value, allowedValues) {
  const normalized = safeRenderableString(value);
  return normalized && allowedValues.has(normalized) ? normalized : "";
}

function normalizedOptionalAllowedValue(value, allowedValues) {
  const normalized = safeString(value);
  if (!normalized) {
    return "";
  }
  if (containsForbiddenEngineIntelligenceTerm(normalized) || !allowedValues.has(normalized)) {
    return null;
  }
  return normalized;
}

function normalizeReasonCodes(values) {
  if (!Array.isArray(values) || values.length > MAX_ENGINE_INTELLIGENCE_REASON_CODES) {
    return null;
  }
  const normalized = [];
  for (const value of values) {
    const reasonCode = safeRenderableString(value);
    if (!reasonCode) {
      return null;
    }
    normalized.push(reasonCode);
  }
  return normalized;
}

function isEngineResultOperationallyConsistent(status, scoreBucket, riskLevel) {
  if (status === "AVAILABLE") {
    return true;
  }
  return scoreBucket === "UNAVAILABLE" && !riskLevel;
}

function isDiagnosticSignalOperationallyConsistent(signalCategory, engineStatus, scoreBucket, riskLevel) {
  if (engineStatus !== "AVAILABLE" || signalCategory === "OPERATIONAL_SIGNAL") {
    return scoreBucket === "UNAVAILABLE" && !riskLevel;
  }
  return true;
}

function safeRenderableString(value) {
  const normalized = safeString(value);
  return normalized && !containsForbiddenEngineIntelligenceTerm(normalized) ? normalized : "";
}

function safeString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function safeStringArray(values) {
  return Array.isArray(values) ? values.map(safeString).filter(Boolean) : [];
}

function containsForbiddenEngineIntelligenceTerm(value) {
  const compact = compactEngineIntelligenceText(value);
  return FORBIDDEN_COMPACT_ENGINE_INTELLIGENCE_TERMS.some((term) => compact.includes(term));
}

function compactEngineIntelligenceText(value) {
  return String(value || "").replace(/[^A-Za-z0-9]/g, "").toLowerCase();
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
