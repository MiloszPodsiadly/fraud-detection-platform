import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError, listScoredTransactions, setApiSession } from "../api/alertsApi.js";

const INITIAL_TRANSACTION_PAGE = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  page: 0,
  size: 25
};

const INITIAL_TRANSACTION_REQUEST = {
  page: 0,
  size: 25,
  query: "",
  riskLevel: "ALL",
  status: "ALL"
};

export function useScoredTransactionStream({ enabled = true, session, authProvider } = {}) {
  const [page, setPage] = useState(INITIAL_TRANSACTION_PAGE);
  const [request, setRequest] = useState(INITIAL_TRANSACTION_REQUEST);
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
  const sessionIdentity = `${authProvider?.kind || "none"}:${session?.userId || ""}`;

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
      const nextPage = await listScoredTransactions(nextRequest, { signal: abortController.signal });
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
      setPage(INITIAL_TRANSACTION_PAGE);
      setError(null);
      setIsLoading(false);
      return;
    }
    load(request);
  }, [enabled, load, request, sessionIdentity]);

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
