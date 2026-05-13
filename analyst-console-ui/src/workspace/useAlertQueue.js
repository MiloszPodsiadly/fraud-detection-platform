import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError, listAlerts, setApiSession } from "../api/alertsApi.js";

const INITIAL_ALERT_PAGE = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  page: 0,
  size: 10
};

export function useAlertQueue({ enabled = true, session, authProvider } = {}) {
  const [page, setPage] = useState(INITIAL_ALERT_PAGE);
  const [request, setRequest] = useState({ page: 0, size: 10 });
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionRef = useRef(session);
  const authProviderRef = useRef(authProvider);

  useEffect(() => {
    sessionRef.current = session;
    authProviderRef.current = authProvider;
  }, [authProvider, session]);

  const load = useCallback(async (nextRequest = request) => {
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    try {
      setApiSession(sessionRef.current, authProviderRef.current);
      const nextPage = await listAlerts(nextRequest, { signal: abortController.signal });
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      setPage(nextPage);
      return nextPage;
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setError(apiError);
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
  }, [request]);

  useEffect(() => {
    if (!enabled) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setIsLoading(false);
      return;
    }
    load(request);
  }, [enabled, load, request]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return {
    page,
    request,
    isLoading,
    error,
    setRequest,
    refresh: () => load(request)
  };
}
