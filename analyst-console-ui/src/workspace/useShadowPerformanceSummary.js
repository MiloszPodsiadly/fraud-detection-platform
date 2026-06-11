import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

export function useShadowPerformanceSummary({ enabled = true, apiClient, session, authProvider } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const [summary, setSummary] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionIdentity = `${effectiveAuthProvider?.kind || "none"}:${effectiveSession?.userId || ""}`;

  const load = useCallback(async () => {
    if (!enabled || !effectiveApiClient) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setSummary(null);
      setError(null);
      setIsLoading(false);
      return null;
    }
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    setSummary(null);
    try {
      const nextSummary = await effectiveApiClient.getCurrentShadowPerformanceSummary({
        signal: abortController.signal
      });
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      setSummary(nextSummary);
      return nextSummary;
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setError(apiError);
      setSummary(null);
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
  }, [enabled, effectiveApiClient]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setSummary(null);
      setError(null);
      setIsLoading(false);
      return;
    }
    load();
  }, [enabled, effectiveApiClient, load, sessionIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return { summary, isLoading, error, refresh: load };
}
