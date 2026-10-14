import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const INVALID_RESPONSE_ERROR = Object.freeze({ state: "invalid-response" });

export function useModelCard({ enabled = true, apiClient, session, authProvider } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const [card, setCard] = useState(null);
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
    setCard(null);
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
    setCard(null);
    try {
      const nextCard = await effectiveApiClient.getCurrentModelCard({ signal: abortController.signal });
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      if (nextCard?.state === "invalid-response") {
        setError(INVALID_RESPONSE_ERROR);
        return null;
      }
      setCard(nextCard);
      return nextCard;
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setError(apiError);
      setCard(null);
      return null;
    } finally {
      if (requestSeqRef.current === requestSeq) {
        setIsLoading(false);
        if (abortControllerRef.current === abortController) {
          abortControllerRef.current = null;
        }
      }
    }
  }, [clearState, effectiveApiClient, enabled]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient) {
      clearState();
      return;
    }
    load();
  }, [clearState, effectiveApiClient, enabled, load, sessionIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return { card, isLoading, error, refresh: load };
}
