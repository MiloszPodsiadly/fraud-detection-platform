import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INITIAL_ALERT_PAGE = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  page: 0,
  size: 10
};
const INITIAL_ALERT_REQUEST = { page: 0, size: 10 };
const INVALID_ALERT_PAGE = "INVALID_ALERT_PAGE";
const ALERT_PAGE_UNAVAILABLE_MESSAGE = "Alerts are temporarily unavailable. Please retry.";
const ALERT_RISK_LEVELS = new Set(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
const ALERT_STATUSES = new Set(["OPEN", "IN_REVIEW", "ESCALATED", "RESOLVED", "CLOSED"]);
const ALERT_SECURITY_ERROR_CODES = new Set(["session_expired", "auth_provider_error"]);
const ALERT_SECURITY_REASONS = new Set([
  "insufficient_authority",
  "missing_credentials",
  "invalid_demo_auth",
  "invalid_jwt"
]);

export function useAlertQueue({ enabled = true, session, authProvider, apiClient } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const resetKey = runtime?.workspaceSessionResetKey;
  const owner = useMemo(() => ({
    enabled: Boolean(enabled),
    apiClient: effectiveApiClient,
    session: effectiveSession,
    authProvider: effectiveAuthProvider,
    resetKey
  }), [authProvider, effectiveApiClient, effectiveAuthProvider, effectiveSession, enabled, resetKey, session]);
  const viewIdentity = useMemo(() => ({}), [owner]);
  const [requestState, setRequestState] = useState(() => ({ owner, request: INITIAL_ALERT_REQUEST }));
  const request = requestState.owner === owner ? requestState.request : INITIAL_ALERT_REQUEST;
  const identity = useMemo(() => ({ owner, request }), [owner, request]);
  const [state, setState] = useState({
    identity: null,
    page: INITIAL_ALERT_PAGE,
    isLoading: false,
    error: null,
    hasLoaded: false
  });
  const currentIdentityRef = useRef(null);
  const activeRequestRef = useRef(null);
  const requestSeqRef = useRef(0);
  const canRequest = Boolean(owner.enabled && owner.apiClient);

  const cancel = useCallback(() => {
    requestSeqRef.current += 1;
    activeRequestRef.current?.controller.abort();
    activeRequestRef.current = null;
  }, []);

  useLayoutEffect(() => {
    currentIdentityRef.current = identity;
    return () => {
      currentIdentityRef.current = null;
      cancel();
    };
  }, [cancel, identity]);

  const setRequest = useCallback((update) => {
    if (currentIdentityRef.current?.owner !== owner || !owner.enabled || !owner.apiClient) {
      return;
    }
    setRequestState((current) => {
      const previous = current.owner === owner ? current.request : INITIAL_ALERT_REQUEST;
      const next = normalizeRequest(typeof update === "function" ? update(previous) : update);
      return current.owner === owner && sameRequest(previous, next) ? current : { owner, request: next };
    });
  }, [owner]);

  const refresh = useCallback(async () => {
    const current = currentIdentityRef.current;
    if (current?.owner !== owner || !owner.enabled || !owner.apiClient) {
      return null;
    }

    activeRequestRef.current?.controller.abort();
    const controller = new AbortController();
    const sequence = ++requestSeqRef.current;
    activeRequestRef.current = { identity: current, controller, sequence };
    const isCurrent = () => currentIdentityRef.current === current
      && requestSeqRef.current === sequence
      && !controller.signal.aborted;
    setState((previous) => ({
      identity: current,
      page: previous.identity === current ? previous.page : INITIAL_ALERT_PAGE,
      isLoading: true,
      error: null,
      hasLoaded: previous.identity === current && previous.hasLoaded
    }));

    try {
      const nextPage = await current.owner.apiClient.listAlerts(current.request, { signal: controller.signal });
      if (!isCurrent()) {
        return null;
      }
      if (!isValidAlertPage(nextPage, current.request)) {
        throw new Error(INVALID_ALERT_PAGE);
      }
      if (isOutOfRangeAlertPage(nextPage, current.request)) {
        setRequest({ page: 0, size: current.request.size });
        return null;
      }
      setState({ identity: current, page: nextPage, isLoading: false, error: null, hasLoaded: true });
      return nextPage;
    } catch (apiError) {
      if (!isCurrent() || isAbortError(apiError)) {
        return null;
      }
      setState({
        identity: current,
        page: INITIAL_ALERT_PAGE,
        isLoading: false,
        error: safeAlertQueueError(apiError),
        hasLoaded: false
      });
      return null;
    } finally {
      if (isCurrent()) {
        setState((previous) => previous.identity === current ? { ...previous, isLoading: false } : previous);
        if (activeRequestRef.current?.sequence === sequence) {
          activeRequestRef.current = null;
        }
      }
    }
  }, [owner, setRequest]);

  useEffect(() => {
    if (!canRequest) {
      setState({ identity, page: INITIAL_ALERT_PAGE, isLoading: false, error: null, hasLoaded: false });
    } else if (activeRequestRef.current?.identity !== identity) {
      void refresh();
    }
    return () => {
      if (activeRequestRef.current?.identity === identity) {
        cancel();
      }
    };
  }, [canRequest, cancel, identity, refresh]);

  const ownsState = state.identity === identity;
  return {
    page: ownsState && canRequest ? state.page : INITIAL_ALERT_PAGE,
    viewIdentity,
    request,
    isLoading: canRequest && (!ownsState || state.isLoading),
    error: ownsState && canRequest ? state.error : null,
    hasLoaded: ownsState && canRequest && state.hasLoaded,
    setRequest,
    refresh
  };
}

function normalizeRequest(value = {}) {
  return {
    page: Math.min(Math.max(Math.trunc(Number(value.page) || 0), 0), 1000),
    size: Math.min(Math.max(Math.trunc(Number(value.size) || 10), 1), 100)
  };
}

function sameRequest(left, right) {
  return left.page === right.page && left.size === right.size;
}

function isValidAlertPage(value, request) {
  return isRecord(value)
    && Array.isArray(value.content)
    && value.content.length <= request.size
    && value.content.every(isValidAlertSummary)
    && Number.isSafeInteger(value.totalElements) && value.totalElements >= 0
    && Number.isSafeInteger(value.totalPages) && value.totalPages >= 0
    && Number.isSafeInteger(value.page) && value.page === request.page
    && Number.isSafeInteger(value.size) && value.size === request.size
    && value.totalPages === Math.ceil(value.totalElements / request.size)
    && (value.page < value.totalPages || value.content.length === 0);
}

function isOutOfRangeAlertPage(value, request) {
  return request.page > 0
    && value.content.length === 0
    && request.page >= value.totalPages;
}

function isValidAlertSummary(value) {
  if (!isRecord(value) || typeof value.alertId !== "string" || !value.alertId.trim()) {
    return false;
  }

  return isOptionalString(value.transactionId)
    && isOptionalString(value.customerId)
    && isOptionalString(value.alertReason)
    && isOptionalEnum(value.riskLevel, ALERT_RISK_LEVELS)
    && isOptionalEnum(value.alertStatus, ALERT_STATUSES)
    && isOptionalFraudScore(value.fraudScore)
    && isOptionalAlertTimestamp(value.alertTimestamp);
}

function isOptionalString(value) {
  return value === undefined || value === null || typeof value === "string";
}

function isOptionalEnum(value, validValues) {
  return value === undefined || value === null || (typeof value === "string" && validValues.has(value));
}

function isOptionalFraudScore(value) {
  return value === undefined || value === null
    || (typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1);
}

function isOptionalAlertTimestamp(value) {
  if (value === undefined || value === null) {
    return true;
  }
  if (typeof value !== "string") {
    return false;
  }

  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|([+-])(\d{2}):(\d{2}))$/.exec(value);
  if (!match) {
    return false;
  }
  const [, yearText, monthText, dayText, hourText, minuteText, secondText, , , offsetHourText, offsetMinuteText] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const offsetHour = Number(offsetHourText || 0);
  const offsetMinute = Number(offsetMinuteText || 0);
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

  return month >= 1 && month <= 12
    && day >= 1 && day <= daysInMonth[month - 1]
    && hour <= 23 && minute <= 59 && second <= 59
    && offsetHour <= 18 && offsetMinute <= 59 && (offsetHour < 18 || offsetMinute === 0)
    && Number.isFinite(new Date(value).getTime());
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function safeAlertQueueError(apiError) {
  const safeError = new Error(ALERT_PAGE_UNAVAILABLE_MESSAGE);
  if (Number.isInteger(apiError?.status) && apiError.status >= 400 && apiError.status <= 599) {
    safeError.status = apiError.status;
  }
  if (ALERT_SECURITY_ERROR_CODES.has(apiError?.error)) {
    safeError.error = apiError.error;
  }
  const safeReasons = Array.isArray(apiError?.details)
    ? apiError.details.slice(0, 16).filter((detail) => typeof detail === "string"
      && detail.startsWith("reason:")
      && ALERT_SECURITY_REASONS.has(detail.slice("reason:".length))).slice(0, 4)
    : [];
  if (safeReasons.length > 0) {
    safeError.details = safeReasons;
  }
  return safeError;
}
