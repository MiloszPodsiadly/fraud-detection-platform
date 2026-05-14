import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import {
  initialFraudCaseWorkQueue,
  initialFraudCaseWorkQueueRequest,
  isInvalidWorkQueueCursorError,
  mergeWorkQueueSlice,
  resetWorkQueueRequestForFilterChange
} from "./workQueueState.js";

const noop = () => {};

export function useFraudCaseWorkQueue({
  enabled = true,
  session,
  authProvider,
  apiClient,
  onSessionError = noop
} = {}) {
  const [queue, setQueue] = useState(initialFraudCaseWorkQueue);
  const [draftFilters, setDraftFilters] = useState(initialFraudCaseWorkQueueRequest);
  const [committedFilters, setCommittedFilters] = useState(initialFraudCaseWorkQueueRequest);
  const [isLoading, setIsLoading] = useState(Boolean(enabled));
  const [error, setError] = useState(null);
  const [warning, setWarning] = useState(null);
  const [filterError, setFilterError] = useState(null);
  const [lastRefreshedAt, setLastRefreshedAt] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const skipNextReloadRef = useRef(false);
  const sessionIdentity = `${authProvider?.kind || "none"}:${session?.userId || ""}`;

  const loadQueue = useCallback(async (request, { append = false } = {}) => {
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    try {
      const nextQueue = await apiClient.listFraudCaseWorkQueue(request, { signal: abortController.signal });
      if (requestSeqRef.current !== requestSeq) {
        return;
      }
      setQueue((current) => {
        const merged = mergeWorkQueueSlice(current, nextQueue, { append });
        setWarning(merged.duplicateCaseIds.length > 0 ? { type: "DUPLICATE_SLICE" } : null);
        return merged;
      });
      setLastRefreshedAt(new Date().toISOString());
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq) {
        return;
      }
      if (isAbortError(apiError)) {
        return;
      }
      setError(apiError);
      setWarning(null);
      setQueue((current) => ({
        ...initialFraudCaseWorkQueue(),
        size: current.size,
        sort: current.sort
      }));
      if (isInvalidWorkQueueCursorError(apiError)) {
        skipNextReloadRef.current = true;
        setCommittedFilters((current) => ({ ...current, cursor: null }));
        setDraftFilters((current) => ({ ...current, cursor: null }));
      }
      onSessionError(apiError);
    } finally {
      if (requestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (abortControllerRef.current === abortController) {
          abortControllerRef.current = null;
        }
      }
    }
  }, [apiClient, onSessionError]);

  useEffect(() => {
    if (!enabled || !apiClient) {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      requestSeqRef.current += 1;
      setQueue(initialFraudCaseWorkQueue());
      setError(null);
      setWarning(null);
      setFilterError(null);
      setLastRefreshedAt(null);
      setIsLoading(false);
      return;
    }
    if (skipNextReloadRef.current) {
      skipNextReloadRef.current = false;
      return;
    }
    loadQueue(committedFilters, { append: Boolean(committedFilters.cursor) });
  }, [enabled, committedFilters, loadQueue, sessionIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
  }, []);

  function updateDraftFilter(name, value) {
    setDraftFilters((current) => resetWorkQueueRequestForFilterChange(current, { [name]: value }));
    setFilterError(null);
  }

  function applyFilters() {
    const nextFilterError = validateWorkQueueFilters(draftFilters);
    if (nextFilterError) {
      setFilterError(nextFilterError);
      return;
    }
    setQueue(initialFraudCaseWorkQueue());
    setWarning(null);
    setCommittedFilters(resetWorkQueueRequestForFilterChange(draftFilters, { cursor: null }));
  }

  function resetFilters() {
    const nextFilters = initialFraudCaseWorkQueueRequest();
    setDraftFilters(nextFilters);
    setFilterError(null);
    setWarning(null);
    setQueue(initialFraudCaseWorkQueue());
    setCommittedFilters(nextFilters);
  }

  function loadMore() {
    if (!queue.nextCursor) {
      return;
    }
    setCommittedFilters((current) => ({
      ...current,
      cursor: queue.nextCursor
    }));
  }

  function refreshFirstSlice() {
    setQueue(initialFraudCaseWorkQueue());
    setWarning(null);
    const nextFilters = { ...committedFilters, cursor: null };
    setCommittedFilters(nextFilters);
    setDraftFilters((current) => ({ ...current, cursor: null }));
  }

  return {
    queue,
    draftFilters,
    committedFilters,
    isLoading,
    error,
    warning,
    filterError,
    lastRefreshedAt,
    updateDraftFilter,
    applyFilters,
    resetFilters,
    loadMore,
    refreshFirstSlice
  };
}

function validateWorkQueueFilters(filters) {
  const dateFields = [
    ["Created from", filters.createdFrom],
    ["Created to", filters.createdTo],
    ["Updated from", filters.updatedFrom],
    ["Updated to", filters.updatedTo]
  ];
  const invalidDate = dateFields.find(([, value]) => value && Number.isNaN(new Date(value).getTime()));
  if (invalidDate) {
    return `${invalidDate[0]} is not a valid local date and time.`;
  }
  return null;
}
