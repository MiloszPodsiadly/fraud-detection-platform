import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INITIAL_TRANSACTION_PAGE = {
  content: [], totalElements: 0, totalPages: 0, page: 0, size: 25
};
const INITIAL_TRANSACTION_REQUEST = {
  page: 0, size: 25, query: "", riskLevel: "ALL", status: "ALL"
};

export function useScoredTransactionStream({ enabled = true, session, authProvider, apiClient } = {}) {
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
  }), [enabled, effectiveApiClient, effectiveSession, effectiveAuthProvider, resetKey]);
  // This token resets local controls without exposing session/auth objects to the page.
  const viewIdentity = useMemo(() => ({}), [owner]);
  const [requestState, setRequestState] = useState(() => ({ owner, request: INITIAL_TRANSACTION_REQUEST }));
  const request = requestState.owner === owner ? requestState.request : INITIAL_TRANSACTION_REQUEST;
  const identity = useMemo(() => ({ owner, request }), [owner, request]);
  const [state, setState] = useState({ identity: null, page: INITIAL_TRANSACTION_PAGE, isLoading: false, error: null });
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
      const previous = current.owner === owner ? current.request : INITIAL_TRANSACTION_REQUEST;
      const next = normalizeRequest(typeof update === "function" ? update(previous) : update);
      return current.owner === owner && sameRequest(previous, next) ? current : { owner, request: next };
    });
  }, [owner]);

  const refresh = useCallback(async () => {
    const current = currentIdentityRef.current;
    if (!current?.owner.enabled || !current.owner.apiClient) {
      return null;
    }
    activeRequestRef.current?.controller.abort();
    const controller = new AbortController();
    const sequence = ++requestSeqRef.current;
    activeRequestRef.current = { identity: current, controller, sequence };
    const isCurrent = () => currentIdentityRef.current === current
      && requestSeqRef.current === sequence && !controller.signal.aborted;
    setState({ identity: current, page: INITIAL_TRANSACTION_PAGE, isLoading: true, error: null });
    try {
      const nextPage = await current.owner.apiClient.listScoredTransactions(current.request, { signal: controller.signal });
      if (!isCurrent()) {
        return null;
      }
      if (!validPage(nextPage, current.request)) {
        throw new Error("INVALID_SCORED_TRANSACTION_PAGE");
      }
      setState({ identity: current, page: nextPage, isLoading: false, error: null });
      return nextPage;
    } catch (apiError) {
      if (!isCurrent() || isAbortError(apiError)) {
        return null;
      }
      setState({ identity: current, page: INITIAL_TRANSACTION_PAGE, isLoading: false, error: safeReadError(apiError) });
      return null;
    } finally {
      if (isCurrent()) {
        setState((previous) => previous.identity === current ? { ...previous, isLoading: false } : previous);
        if (activeRequestRef.current?.sequence === sequence) {
          activeRequestRef.current = null;
        }
      }
    }
  }, []);

  useEffect(() => {
    if (!canRequest) {
      setState({ identity, page: INITIAL_TRANSACTION_PAGE, isLoading: false, error: null });
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
    page: ownsState && canRequest ? state.page : INITIAL_TRANSACTION_PAGE,
    request,
    isLoading: canRequest && (!ownsState || state.isLoading),
    error: ownsState && canRequest ? state.error : null,
    viewIdentity,
    setRequest,
    refresh
  };
}

function normalizeRequest(value = {}) {
  return {
    page: Math.min(Math.max(Math.trunc(Number(value.page) || 0), 0), 1000),
    size: Math.min(Math.max(Math.trunc(Number(value.size) || 25), 1), 100),
    query: typeof value.query === "string" ? value.query.trim() : "",
    riskLevel: value.riskLevel || "ALL",
    status: value.status || value.classification || "ALL"
  };
}

function sameRequest(left, right) {
  return Object.keys(INITIAL_TRANSACTION_REQUEST).every((key) => left[key] === right[key]);
}

function validPage(value, request) {
  return isRecord(value)
    && Array.isArray(value.content)
    && value.content.length <= request.size
    && value.page === request.page && value.size === request.size
    && Number.isSafeInteger(value.totalElements) && value.totalElements >= 0
    && Number.isSafeInteger(value.totalPages) && value.totalPages >= 0
    && value.content.every(validRow);
}

function validRow(row) {
  return isRecord(row)
    && typeof row.transactionId === "string" && /^[A-Za-z0-9._:-]{1,128}$/.test(row.transactionId)
    && optionalText(row.customerId)
    && (row.riskLevel == null || ["LOW", "MEDIUM", "HIGH", "CRITICAL"].includes(row.riskLevel))
    && (row.fraudScore == null || Number.isFinite(row.fraudScore))
    && (row.alertRecommended == null || typeof row.alertRecommended === "boolean")
    && (row.scoredAt == null || (optionalText(row.scoredAt) && Number.isFinite(Date.parse(row.scoredAt))))
    && validAmount(row.transactionAmount);
}

function validAmount(money) {
  if (money == null) {
    return true;
  }
  return isRecord(money) && (money.amount == null || (
    ["number", "string"].includes(typeof money.amount) && Number.isFinite(Number(money.amount))
    && typeof money.currency === "string" && /^[A-Z]{3}$/.test(money.currency)
  ));
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function optionalText(value) {
  return value == null || (typeof value === "string" && value.length <= 128
    && !/[\u0000-\u001f\u007f-\u009f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/u.test(value));
}

function safeReadError(error) {
  const result = new Error(error?.message === "INVALID_SCORED_TRANSACTION_PAGE"
    ? "The scored transaction response is malformed."
    : "Scored transactions are temporarily unavailable.");
  if (Number.isInteger(error?.status) && error.status >= 400 && error.status <= 599) {
    result.status = error.status;
  }
  if (["session_expired", "auth_provider_error"].includes(error?.error)) {
    result.error = error.error;
  }
  return result;
}
