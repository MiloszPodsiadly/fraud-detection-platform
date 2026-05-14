import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { useOptionalWorkspaceRuntime } from "../workspace/useWorkspaceRuntime.js";

const INITIAL_SUMMARY = {
  totalFraudCases: 0,
  generatedAt: null,
  scope: "GLOBAL_FRAUD_CASES",
  snapshotConsistentWithWorkQueue: false
};

export function useFraudCaseWorkQueueSummary({ enabled, canReadFraudCases, session, authProvider, apiClient } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const effectiveCanReadFraudCases = canReadFraudCases !== undefined ? canReadFraudCases : runtime?.canReadFraudCases;
  const [summary, setSummary] = useState(INITIAL_SUMMARY);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionIdentity = `${effectiveAuthProvider?.kind || "none"}:${effectiveSession?.userId || ""}`;

  const loadSummary = useCallback(async () => {
    if (!enabled || effectiveCanReadFraudCases === false || !effectiveApiClient) {
      setIsLoading(false);
      return;
    }

    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);

    try {
      const nextSummary = await effectiveApiClient.getFraudCaseWorkQueueSummary({ signal: abortController.signal });
      if (requestSeqRef.current !== requestSeq) {
        return;
      }
      setSummary({ ...INITIAL_SUMMARY, ...nextSummary });
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq) {
        return;
      }
      if (isAbortError(apiError)) {
        return;
      }
      setError(apiError);
    } finally {
      if (requestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (abortControllerRef.current === abortController) {
          abortControllerRef.current = null;
        }
      }
    }
  }, [effectiveApiClient, effectiveCanReadFraudCases, enabled]);

  useEffect(() => {
    if (!enabled || effectiveCanReadFraudCases === false || !effectiveApiClient) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setSummary(INITIAL_SUMMARY);
      setIsLoading(false);
      setError(null);
      return;
    }
    loadSummary();
  }, [effectiveApiClient, effectiveCanReadFraudCases, enabled, loadSummary, sessionIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
  }, []);

  return {
    summary,
    isLoading,
    error,
    retry: loadSummary
  };
}
