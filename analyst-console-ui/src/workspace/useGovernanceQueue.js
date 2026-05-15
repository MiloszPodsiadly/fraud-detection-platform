import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INITIAL_QUEUE = {
  status: "UNAVAILABLE",
  count: 0,
  retention_limit: 0,
  advisory_events: []
};

const INITIAL_REQUEST = {
  severity: "ALL",
  modelVersion: "",
  lifecycleStatus: "ALL",
  limit: 25
};

export function useGovernanceQueue({ enabled = true, apiClient, session, authProvider } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const [queue, setQueue] = useState(INITIAL_QUEUE);
  const [request, setRequest] = useState(INITIAL_REQUEST);
  const [auditHistories, setAuditHistories] = useState({});
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionIdentity = `${effectiveAuthProvider?.kind || "none"}:${effectiveSession?.userId || ""}`;

  const load = useCallback(async (nextRequest = request) => {
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    try {
      const nextQueue = await effectiveApiClient.listGovernanceAdvisories(nextRequest, { signal: abortController.signal });
      const histories = await loadAuditHistories(effectiveApiClient, nextQueue.advisory_events || [], abortController.signal);
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      setQueue(nextQueue);
      setAuditHistories(histories);
      return nextQueue;
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setError(apiError);
      setAuditHistories({});
      return null;
    } finally {
      if (requestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (abortControllerRef.current === abortController) {
          abortControllerRef.current = null;
        }
      }
    }
    return null;
  }, [effectiveApiClient, request]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setQueue(INITIAL_QUEUE);
      setAuditHistories({});
      setError(null);
      setIsLoading(false);
      return;
    }
    load(request);
  }, [enabled, effectiveApiClient, load, request, sessionIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return { queue, request, auditHistories, isLoading, error, setRequest, setQueue, setAuditHistories, refresh: () => load(request) };
}

async function loadAuditHistories(apiClient, events, signal) {
  const histories = {};
  await Promise.all(events.map(async (event) => {
    try {
      histories[event.event_id] = await apiClient.getGovernanceAdvisoryAudit(event.event_id, { signal });
    } catch (apiError) {
      if (isAbortError(apiError)) {
        throw apiError;
      }
      histories[event.event_id] = {
        advisory_event_id: event.event_id,
        status: "UNAVAILABLE",
        audit_events: [],
        error: apiError.message
      };
    }
  }));
  return histories;
}
