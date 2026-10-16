import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INVALID_RESPONSE_ERROR = Object.freeze({ state: "invalid-response" });

export function useExactModelShadowPerformance({ enabled = true, apiClient } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const client = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const [summary, setSummary] = useState(null);
  const [selector, setSelector] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const sequence = useRef(0);
  const controller = useRef(null);

  const clear = useCallback(() => {
    controller.current?.abort();
    controller.current = null;
    sequence.current += 1;
    setSummary(null);
    setSelector(null);
    setError(null);
    setIsLoading(false);
  }, []);

  const load = useCallback(async (nextSelector = selector) => {
    if (!enabled || !client || !nextSelector) return null;
    controller.current?.abort();
    const requestController = new AbortController();
    controller.current = requestController;
    const requestSequence = sequence.current + 1;
    sequence.current = requestSequence;
    setSelector(nextSelector);
    setSummary(null);
    setError(null);
    setIsLoading(true);
    try {
      const response = await client.getExactModelShadowPerformance(nextSelector, {
        signal: requestController.signal
      });
      if (sequence.current !== requestSequence) return null;
      if (response?.state === "invalid-response") {
        setError(INVALID_RESPONSE_ERROR);
        return null;
      }
      setSummary(response);
      return response;
    } catch (requestError) {
      if (sequence.current !== requestSequence || isAbortError(requestError)) return null;
      setError(requestError);
      return null;
    } finally {
      if (sequence.current === requestSequence) {
        setIsLoading(false);
        if (controller.current === requestController) controller.current = null;
      }
    }
  }, [client, enabled, selector]);

  useEffect(() => {
    if (!enabled || !client) clear();
  }, [clear, client, enabled]);

  useEffect(() => () => {
    controller.current?.abort();
    sequence.current += 1;
  }, []);

  return { summary, selector, isLoading, error, load, refresh: () => load(selector), clear };
}
