import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";

const INITIAL_ANALYTICS = {
  status: "UNAVAILABLE",
  window: { from: null, to: null, days: 7 },
  totals: { advisories: 0, reviewed: 0, open: 0 },
  decision_distribution: {},
  lifecycle_distribution: {},
  review_timeliness: {
    status: "LOW_CONFIDENCE",
    time_to_first_review_p50_minutes: 0,
    time_to_first_review_p95_minutes: 0
  }
};

export function useGovernanceAnalytics({ enabled = true, apiClient, session, authProvider } = {}) {
  const [analytics, setAnalytics] = useState(INITIAL_ANALYTICS);
  const [windowDays, setWindowDays] = useState(7);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionIdentity = `${authProvider?.kind || "none"}:${session?.userId || ""}`;

  const load = useCallback(async (days = windowDays) => {
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    try {
      const nextAnalytics = await apiClient.getGovernanceAdvisoryAnalytics({ windowDays: days }, { signal: abortController.signal });
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      setAnalytics(nextAnalytics);
      return nextAnalytics;
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
  }, [apiClient, windowDays]);

  useEffect(() => {
    if (!enabled || !apiClient) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setAnalytics(INITIAL_ANALYTICS);
      setError(null);
      setIsLoading(false);
      return;
    }
    load(windowDays);
  }, [enabled, load, sessionIdentity, windowDays]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return { analytics, windowDays, isLoading, error, setWindowDays, refresh: () => load(windowDays) };
}
