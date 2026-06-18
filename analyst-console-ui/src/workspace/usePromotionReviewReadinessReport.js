import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INVALID_RESPONSE_ERROR = Object.freeze({ state: "invalid-response" });

export function usePromotionReviewReadinessReport({ enabled = true, apiClient, session, authProvider } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const [report, setReport] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionIdentity = [
    effectiveAuthProvider?.kind || "none",
    effectiveSession?.userId || "",
    Array.isArray(effectiveSession?.authorities) ? effectiveSession.authorities.join(",") : ""
  ].join(":");

  const clearState = useCallback(() => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
    setReport(null);
    setError(null);
    setIsLoading(false);
  }, []);

  const load = useCallback(async () => {
    if (!enabled || !effectiveApiClient) {
      clearState();
      return null;
    }
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    setReport(null);
    try {
      const nextReport = await effectiveApiClient.getCurrentPromotionReviewReadinessReport({
        signal: abortController.signal
      });
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      if (nextReport?.state === "invalid-response") {
        setError(INVALID_RESPONSE_ERROR);
        setReport(null);
        return null;
      }
      setReport(nextReport);
      return nextReport;
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setError(apiError);
      setReport(null);
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
  }, [clearState, enabled, effectiveApiClient]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient) {
      clearState();
      return;
    }
    load();
  }, [clearState, enabled, effectiveApiClient, load, sessionIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return { report, isLoading, error, refresh: load };
}
