import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INITIAL_COUNTERS = {
  alerts: null,
  fraudCases: null,
  suspiciousTransactions: null,
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
  includeFraudCases = true,
  includeSuspiciousTransactions = true,
  includeTransactions = true,
  apiClient,
  workspaceSessionResetKey,
  canReadAlerts,
  canReadFraudCases,
  canReadSuspiciousTransactions,
  canReadTransactions
} = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveResetKey = workspaceSessionResetKey !== undefined
    ? workspaceSessionResetKey
    : runtime?.workspaceSessionResetKey;
  const effectiveCanReadAlerts = canReadAlerts !== undefined ? canReadAlerts : runtime?.canReadAlerts;
  const effectiveCanReadFraudCases = canReadFraudCases !== undefined
    ? canReadFraudCases
    : runtime?.canReadFraudCases;
  const effectiveCanReadSuspiciousTransactions = canReadSuspiciousTransactions !== undefined
    ? canReadSuspiciousTransactions
    : runtime?.canReadSuspiciousTransactions;
  const effectiveCanReadTransactions = canReadTransactions !== undefined
    ? canReadTransactions
    : runtime?.canReadTransactions;
  const owner = useMemo(() => ({
    apiClient: effectiveApiClient,
    resetKey: effectiveResetKey,
    enabled: Boolean(enabled && effectiveApiClient)
  }), [effectiveApiClient, effectiveResetKey, enabled]);
  const capabilities = useMemo(() => ({
    canReadAlerts: effectiveCanReadAlerts,
    canReadFraudCases: effectiveCanReadFraudCases,
    canReadSuspiciousTransactions: effectiveCanReadSuspiciousTransactions,
    canReadTransactions: effectiveCanReadTransactions
  }), [
    effectiveCanReadAlerts,
    effectiveCanReadFraudCases,
    effectiveCanReadSuspiciousTransactions,
    effectiveCanReadTransactions
  ]);
  const [ownedState, setOwnedState] = useState(() => ({ owner, value: INITIAL_STATE }));
  const currentOwnerRef = useRef(null);
  const currentCapabilitiesRef = useRef(capabilities);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const state = useMemo(() => ownedState.owner === owner
    ? redactUnavailableCounterState(ownedState.value, capabilities)
    : INITIAL_STATE, [capabilities, owner, ownedState]);

  const writeState = useCallback((update) => {
    setOwnedState((current) => {
      if (currentOwnerRef.current !== owner) {
        return current;
      }
      const currentValue = current.owner === owner ? current.value : INITIAL_STATE;
      return {
        owner,
        value: typeof update === "function" ? update(currentValue) : update
      };
    });
  }, [owner]);

  useLayoutEffect(() => {
    currentOwnerRef.current = owner;
    return () => {
      if (currentOwnerRef.current === owner) {
        currentOwnerRef.current = null;
      }
      requestSeqRef.current += 1;
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
    };
  }, [owner]);

  useLayoutEffect(() => {
    currentCapabilitiesRef.current = capabilities;
    return () => {
      requestSeqRef.current += 1;
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
    };
  }, [capabilities]);

  const refresh = useCallback(async () => {
    if (currentOwnerRef.current !== owner
        || currentCapabilitiesRef.current !== capabilities
        || !owner.enabled) {
      return;
    }

    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    const isCurrentRequest = () => currentOwnerRef.current === owner
      && currentCapabilitiesRef.current === capabilities
      && requestSeqRef.current === requestSeq
      && !abortController.signal.aborted;
    writeState((current) => ({ ...current, isLoading: true }));

    const requests = buildCounterRequests({
      apiClient: owner.apiClient,
      signal: abortController.signal,
      includeAlerts,
      includeFraudCases,
      includeSuspiciousTransactions,
      includeTransactions,
      canReadAlerts: capabilities.canReadAlerts,
      canReadFraudCases: capabilities.canReadFraudCases,
      canReadSuspiciousTransactions: capabilities.canReadSuspiciousTransactions,
      canReadTransactions: capabilities.canReadTransactions
    });

    if (requests.length === 0) {
      if (isCurrentRequest()) {
        writeState((current) => ({
          ...current,
          counters: clearUnavailableCounters(current.counters, {
            includeAlerts,
            includeFraudCases,
            includeSuspiciousTransactions,
            includeTransactions,
            canReadAlerts: capabilities.canReadAlerts,
            canReadFraudCases: capabilities.canReadFraudCases,
            canReadSuspiciousTransactions: capabilities.canReadSuspiciousTransactions,
            canReadTransactions: capabilities.canReadTransactions
          }),
          isLoading: false,
          errorByCounter: {},
          degraded: false,
          stale: false
        }));
      }
      if (abortControllerRef.current === abortController) {
        abortControllerRef.current = null;
      }
      return;
    }

    const results = await Promise.allSettled(requests.map((request) => request.promise));
    if (!isCurrentRequest()) {
      return;
    }

    writeState((current) => reduceCounterResults(current, requests, results, {
      includeAlerts,
      includeFraudCases,
      includeSuspiciousTransactions,
      includeTransactions,
      canReadAlerts: capabilities.canReadAlerts,
      canReadFraudCases: capabilities.canReadFraudCases,
      canReadSuspiciousTransactions: capabilities.canReadSuspiciousTransactions,
      canReadTransactions: capabilities.canReadTransactions
    }));
    if (abortControllerRef.current === abortController) {
      abortControllerRef.current = null;
    }
  }, [
    owner,
    capabilities,
    includeAlerts,
    includeFraudCases,
    includeSuspiciousTransactions,
    includeTransactions,
    writeState
  ]);

  useEffect(() => {
    if (!owner.enabled) {
      return;
    }
    void refresh();
    return () => {
      requestSeqRef.current += 1;
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
    };
  }, [owner.enabled, refresh]);

  const setCounterValue = useCallback((counterName, value) => {
    if (!Object.hasOwn(INITIAL_COUNTERS, counterName)
        || currentOwnerRef.current !== owner
        || currentCapabilitiesRef.current !== capabilities
        || !owner.enabled
        || !ownerCanReadCounter(capabilities, counterName)) {
      return;
    }
    if (!isValidWorkspaceCounterValue(value)) {
      return;
    }
    writeState((current) => ({
      ...current,
      counters: {
        ...current.counters,
        [counterName]: value
      },
      ...counterRecovered(current, counterName)
    }));
  }, [capabilities, owner, writeState]);

  return useMemo(() => ({
    ...state,
    refresh,
    setCounterValue
  }), [refresh, setCounterValue, state]);
}

export function isValidWorkspaceCounterValue(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function ownerCanReadCounter(capabilities, counterName) {
  switch (counterName) {
    case "alerts":
      return capabilities.canReadAlerts === true;
    case "fraudCases":
      return capabilities.canReadFraudCases === true;
    case "suspiciousTransactions":
      return capabilities.canReadSuspiciousTransactions === true;
    case "transactions":
      return capabilities.canReadTransactions === true;
    default:
      return false;
  }
}

function redactUnavailableCounterState(state, capabilities) {
  const counters = clearUnavailableCounters(state.counters, {
    includeAlerts: true,
    includeFraudCases: true,
    includeSuspiciousTransactions: true,
    includeTransactions: true,
    ...capabilities
  });
  const errorByCounter = Object.fromEntries(Object.entries(state.errorByCounter)
    .filter(([counterName]) => ownerCanReadCounter(capabilities, counterName)));
  const degraded = Object.keys(errorByCounter).length > 0;
  return {
    ...state,
    counters,
    errorByCounter,
    degraded,
    stale: degraded && state.stale
  };
}

function buildCounterRequests({
  apiClient,
  signal,
  includeAlerts,
  includeFraudCases,
  includeSuspiciousTransactions,
  includeTransactions,
  canReadAlerts,
  canReadFraudCases,
  canReadSuspiciousTransactions,
  canReadTransactions
}) {
  const requests = [];
  if (includeAlerts && canReadAlerts === true) {
    requests.push({
      name: "alerts",
      promise: apiClient.listAlerts({ page: 0, size: 1 }, { signal }),
      readValue: (page) => page.totalElements
    });
  }
  if (includeFraudCases && canReadFraudCases === true && typeof apiClient.getFraudCaseWorkQueueSummary === "function") {
    requests.push({
      name: "fraudCases",
      promise: apiClient.getFraudCaseWorkQueueSummary({ signal }),
      readValue: (summary) => summary.totalFraudCases
    });
  }
  if (
    includeSuspiciousTransactions
    && canReadSuspiciousTransactions === true
    && typeof apiClient.getSuspiciousTransactionSummary === "function"
  ) {
    requests.push({
      name: "suspiciousTransactions",
      promise: apiClient.getSuspiciousTransactionSummary({ signal }),
      readValue: readSuspiciousTransactionSummaryTotal
    });
  }
  if (includeTransactions && canReadTransactions === true) {
    requests.push({
      name: "transactions",
      promise: apiClient.listScoredTransactions({
        page: 0,
        size: 1,
        query: "",
        riskLevel: "ALL",
        status: "ALL"
      }, { signal }),
      readValue: (page) => page.totalElements
    });
  }
  return requests;
}

function reduceCounterResults(current, requests, results, authorityState) {
  const counters = clearUnavailableCounters(current.counters, authorityState);
  const errorByCounter = {};
  let hasSuccessfulRefresh = false;
  let retainedStaleValue = false;
  let hasNonAbortFailure = false;

  requests.forEach((request, index) => {
    const result = results[index];
    if (result.status === "fulfilled") {
      try {
        const value = request.readValue(result.value);
        if (!isValidWorkspaceCounterValue(value)) {
          throw new Error("Counter response is invalid");
        }
        counters[request.name] = value;
        hasSuccessfulRefresh = true;
      } catch (error) {
        hasNonAbortFailure = true;
        if (recordCounterFailure(errorByCounter, request.name, error, current, counters)) {
          retainedStaleValue = true;
        }
      }
      return;
    }
    if (isAbortError(result.reason)) {
      return;
    }
    hasNonAbortFailure = true;
    if (recordCounterFailure(errorByCounter, request.name, result.reason, current, counters)) {
      retainedStaleValue = true;
    }
  });

  if (!hasSuccessfulRefresh && !hasNonAbortFailure) {
    return {
      ...current,
      counters,
      isLoading: false
    };
  }

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
  includeFraudCases,
  includeSuspiciousTransactions,
  includeTransactions,
  canReadAlerts,
  canReadFraudCases,
  canReadSuspiciousTransactions,
  canReadTransactions
}) {
  return {
    alerts: includeAlerts && canReadAlerts !== true ? null : counters.alerts,
    fraudCases: includeFraudCases && canReadFraudCases !== true ? null : counters.fraudCases,
    suspiciousTransactions: includeSuspiciousTransactions && canReadSuspiciousTransactions !== true
      ? null
      : counters.suspiciousTransactions,
    transactions: includeTransactions && canReadTransactions !== true ? null : counters.transactions
  };
}

function readSuspiciousTransactionSummaryTotal(summary) {
  if (summary?.freshness === "UNAVAILABLE") {
    throw new Error("Summary temporarily unavailable");
  }
  return summary?.totalSuspiciousTransactions;
}

function recordCounterFailure(errorByCounter, counterName, reason, current, counters) {
  errorByCounter[counterName] = counterFailureMessage(counterName, reason);
  if (isValidWorkspaceCounterValue(current.counters[counterName])) {
    counters[counterName] = current.counters[counterName];
    return true;
  }
  counters[counterName] = null;
  return false;
}

function counterFailureMessage(counterName, reason) {
  if (counterName === "suspiciousTransactions") {
    return "Summary temporarily unavailable";
  }
  return reason?.message || "Counter unavailable";
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
