import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INITIAL_COUNTERS = {
  alerts: null,
  transactions: null
};

const INITIAL_STATE = {
  counters: INITIAL_COUNTERS,
  isLoading: false,
  errorByCounter: {},
  degraded: false,
  stale: false,
  lastRefreshedAt: null
};

export function useWorkspaceCounters({
  enabled = true,
  includeAlerts = true,
  includeTransactions = true,
  apiClient,
  workspaceSessionResetKey,
  canReadAlerts,
  canReadTransactions
} = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveResetKey = workspaceSessionResetKey !== undefined
    ? workspaceSessionResetKey
    : runtime?.workspaceSessionResetKey;
  const effectiveCanReadAlerts = canReadAlerts !== undefined ? canReadAlerts : runtime?.canReadAlerts;
  const effectiveCanReadTransactions = canReadTransactions !== undefined
    ? canReadTransactions
    : runtime?.canReadTransactions;
  const [state, setState] = useState(INITIAL_STATE);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const clearState = useCallback(() => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
    setState(INITIAL_STATE);
  }, []);

  const refresh = useCallback(async () => {
    if (!enabled || !effectiveApiClient) {
      clearState();
      return;
    }

    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setState((current) => ({ ...current, isLoading: true }));

    const requests = buildCounterRequests({
      apiClient: effectiveApiClient,
      signal: abortController.signal,
      includeAlerts,
      includeTransactions,
      canReadAlerts: effectiveCanReadAlerts,
      canReadTransactions: effectiveCanReadTransactions
    });

    if (requests.length === 0) {
      if (requestSeqRef.current === requestSeq) {
        setState((current) => ({
          ...current,
          counters: clearUnavailableCounters(current.counters, {
            includeAlerts,
            includeTransactions,
            canReadAlerts: effectiveCanReadAlerts,
            canReadTransactions: effectiveCanReadTransactions
          }),
          isLoading: false,
          errorByCounter: {},
          degraded: false,
          stale: false
        }));
      }
      return;
    }

    const results = await Promise.allSettled(requests.map((request) => request.promise));
    if (requestSeqRef.current !== requestSeq || abortController.signal.aborted) {
      return;
    }

    setState((current) => reduceCounterResults(current, requests, results, {
      includeAlerts,
      includeTransactions,
      canReadAlerts: effectiveCanReadAlerts,
      canReadTransactions: effectiveCanReadTransactions
    }));
    if (abortControllerRef.current === abortController) {
      abortControllerRef.current = null;
    }
  }, [
    clearState,
    effectiveApiClient,
    effectiveCanReadAlerts,
    effectiveCanReadTransactions,
    enabled,
    includeAlerts,
    includeTransactions
  ]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient) {
      clearState();
      return;
    }
    refresh();
    return () => {
      abortControllerRef.current?.abort();
      requestSeqRef.current += 1;
    };
  }, [clearState, effectiveApiClient, effectiveResetKey, enabled, refresh]);

  const setCounterValue = useCallback((counterName, value) => {
    if (!Object.hasOwn(INITIAL_COUNTERS, counterName)) {
      return;
    }
    setState((current) => ({
      ...current,
      counters: {
        ...current.counters,
        [counterName]: value ?? current.counters[counterName]
      },
      ...counterRecovered(current, counterName)
    }));
  }, []);

  return useMemo(() => ({
    ...state,
    refresh,
    setCounterValue
  }), [refresh, setCounterValue, state]);
}

function buildCounterRequests({
  apiClient,
  signal,
  includeAlerts,
  includeTransactions,
  canReadAlerts,
  canReadTransactions
}) {
  const requests = [];
  if (includeAlerts && canReadAlerts !== false) {
    requests.push({
      name: "alerts",
      promise: apiClient.listAlerts({ page: 0, size: 1 }, { signal })
    });
  }
  if (includeTransactions && canReadTransactions !== false) {
    requests.push({
      name: "transactions",
      promise: apiClient.listScoredTransactions({
        page: 0,
        size: 1,
        query: "",
        riskLevel: "ALL",
        status: "ALL"
      }, { signal })
    });
  }
  return requests;
}

function reduceCounterResults(current, requests, results, authorityState) {
  const counters = clearUnavailableCounters(current.counters, authorityState);
  const errorByCounter = {};
  let hasSuccessfulRefresh = false;
  let retainedStaleValue = false;

  requests.forEach((request, index) => {
    const result = results[index];
    if (result.status === "fulfilled") {
      counters[request.name] = result.value.totalElements ?? counters[request.name];
      hasSuccessfulRefresh = true;
      return;
    }
    if (isAbortError(result.reason)) {
      return;
    }
    errorByCounter[request.name] = result.reason?.message || "Counter unavailable";
    if (current.counters[request.name] !== null && current.counters[request.name] !== undefined) {
      retainedStaleValue = true;
      counters[request.name] = current.counters[request.name];
    }
  });

  const degraded = Object.keys(errorByCounter).length > 0;
  return {
    counters,
    isLoading: false,
    errorByCounter,
    degraded,
    stale: degraded && retainedStaleValue,
    lastRefreshedAt: hasSuccessfulRefresh ? new Date().toISOString() : current.lastRefreshedAt
  };
}

function clearUnavailableCounters(counters, {
  includeAlerts,
  includeTransactions,
  canReadAlerts,
  canReadTransactions
}) {
  return {
    alerts: includeAlerts && canReadAlerts === false ? null : counters.alerts,
    transactions: includeTransactions && canReadTransactions === false ? null : counters.transactions
  };
}

function counterRecovered(current, counterName) {
  if (!Object.hasOwn(current.errorByCounter, counterName)) {
    return {};
  }
  const errorByCounter = { ...current.errorByCounter };
  delete errorByCounter[counterName];
  const degraded = Object.keys(errorByCounter).length > 0;
  return {
    errorByCounter,
    degraded,
    stale: degraded && current.stale
  };
}
