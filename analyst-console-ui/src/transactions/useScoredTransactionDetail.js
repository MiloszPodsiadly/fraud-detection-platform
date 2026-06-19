import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { useOptionalWorkspaceRuntime } from "../workspace/useWorkspaceRuntime.js";
import { validateTransactionRiskIntelligenceDetail } from "./transactionRiskIntelligenceValidation.js";

export function useScoredTransactionDetail({
  transactionId,
  enabled = true,
  session,
  authProvider,
  apiClient
} = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const effectiveSession = session !== undefined ? session : runtime?.session;
  const effectiveAuthProvider = authProvider !== undefined ? authProvider : runtime?.authProvider;
  const normalizedTransactionId = normalizeTransactionId(transactionId);
  const [detail, setDetail] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const sessionIdentity = `${effectiveAuthProvider?.kind || "none"}:${effectiveSession?.userId || ""}`;

  const clear = useCallback(() => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
    setDetail(null);
    setError(null);
    setIsLoading(false);
  }, []);

  const load = useCallback(async () => {
    if (!enabled || !effectiveApiClient || !normalizedTransactionId) {
      clear();
      return null;
    }
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setDetail(null);
    setIsLoading(true);
    setError(null);
    try {
      const nextDetail = await effectiveApiClient.getScoredTransactionDetail(normalizedTransactionId, {
        signal: abortController.signal
      });
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      const validation = validateTransactionRiskIntelligenceDetail(nextDetail);
      if (!validation.valid) {
        const validationError = new Error("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE");
        validationError.code = validation.reason;
        setDetail(null);
        setError(validationError);
        return null;
      }
      setDetail(nextDetail);
      return nextDetail;
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      setDetail(null);
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
  }, [clear, effectiveApiClient, enabled, normalizedTransactionId]);

  useEffect(() => {
    if (!enabled || !effectiveApiClient || !normalizedTransactionId) {
      clear();
      return;
    }
    load();
  }, [clear, effectiveApiClient, enabled, load, normalizedTransactionId, sessionIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
  }, []);

  return {
    detail,
    isLoading,
    error,
    refresh: load
  };
}

function normalizeTransactionId(transactionId) {
  return transactionId === null || transactionId === undefined ? "" : String(transactionId).trim();
}
