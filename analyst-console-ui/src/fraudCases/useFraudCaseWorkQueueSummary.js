import { useCallback, useEffect, useRef, useState } from "react";
import { getFraudCaseWorkQueueSummary, setApiSession } from "../api/alertsApi.js";

const INITIAL_SUMMARY = {
  totalFraudCases: 0,
  generatedAt: null,
  scope: "GLOBAL_FRAUD_CASES",
  snapshotConsistentWithWorkQueue: false
};

export function useFraudCaseWorkQueueSummary({ enabled, session, authProvider } = {}) {
  const [summary, setSummary] = useState(INITIAL_SUMMARY);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const sessionRef = useRef(session);
  const authProviderRef = useRef(authProvider);

  useEffect(() => {
    sessionRef.current = session;
    authProviderRef.current = authProvider;
  }, [authProvider, session]);

  const loadSummary = useCallback(async () => {
    if (!enabled) {
      setIsLoading(false);
      return;
    }

    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);

    try {
      setApiSession(sessionRef.current, authProviderRef.current);
      const nextSummary = await getFraudCaseWorkQueueSummary();
      if (requestSeqRef.current !== requestSeq) {
        return;
      }
      setSummary({ ...INITIAL_SUMMARY, ...nextSummary });
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq) {
        return;
      }
      setError(apiError);
    } finally {
      if (requestSeqRef.current === requestSeq) {
        setIsLoading(false);
      }
    }
  }, [enabled]);

  useEffect(() => {
    if (!enabled) {
      requestSeqRef.current += 1;
      setIsLoading(false);
      setError(null);
      return;
    }
    loadSummary();
  }, [enabled, loadSummary]);

  return {
    summary,
    isLoading,
    error,
    retry: loadSummary
  };
}
