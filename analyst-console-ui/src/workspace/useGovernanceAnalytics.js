import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

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
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const [analytics, setAnalytics] = useState(INITIAL_ANALYTICS);
  const [windowDays, setWindowDays] = useState(7);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionIdentity = `${effectiveAuthProvider?.kind || "none"}:${effectiveSession?.userId || ""}`;

  const load = useCallback(async (days = windowDays) => {
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    try {
      const nextAnalytics = await effectiveApiClient.getGovernanceAdvisoryAnalytics({ windowDays: days }, { signal: abortController.signal });
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
  }, [effectiveApiClient, windowDays]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setAnalytics(INITIAL_ANALYTICS);
      setError(null);
      setIsLoading(false);
      return;
    }
    load(windowDays);
  }, [enabled, effectiveApiClient, load, sessionIdentity, windowDays]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return { analytics, windowDays, isLoading, error, setWindowDays, refresh: () => load(windowDays) };
}
