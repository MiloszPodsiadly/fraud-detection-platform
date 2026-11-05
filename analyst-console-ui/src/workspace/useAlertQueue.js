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
    if (!current?.owner.enabled || !current.owner.apiClient) {
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
      setState({ identity: current, page: nextPage, isLoading: false, error: null, hasLoaded: true });
      return nextPage;
    } catch (apiError) {
      if (!isCurrent() || isAbortError(apiError)) {
        return null;
      }
      setState({ identity: current, page: INITIAL_ALERT_PAGE, isLoading: false, error: apiError, hasLoaded: false });
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
