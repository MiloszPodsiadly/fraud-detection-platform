import { useCallback, useEffect, useRef, useState } from "react";
import { getFraudCaseWorkQueueSummary, isAbortError, setApiSession } from "../api/alertsApi.js";

const INITIAL_SUMMARY = {
  totalFraudCases: 0,
  generatedAt: null,
  scope: "GLOBAL_FRAUD_CASES",
  snapshotConsistentWithWorkQueue: false
};

export function useFraudCaseWorkQueueSummary({ enabled, canReadFraudCases, session, authProvider } = {}) {
  const [summary, setSummary] = useState(INITIAL_SUMMARY);
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

  const loadSummary = useCallback(async () => {
    if (!enabled || canReadFraudCases === false) {
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
      setApiSession(sessionRef.current, authProviderRef.current);
      const nextSummary = await getFraudCaseWorkQueueSummary({ signal: abortController.signal });
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
  }, [canReadFraudCases, enabled]);

  useEffect(() => {
    if (!enabled || canReadFraudCases === false) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setSummary(INITIAL_SUMMARY);
      setIsLoading(false);
      setError(null);
      return;
    }
    loadSummary();
  }, [canReadFraudCases, enabled, loadSummary, sessionIdentity]);

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
