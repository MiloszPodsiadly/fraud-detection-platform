import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
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
  const requestIdentity = useMemo(() => ({
    transactionId: normalizedTransactionId,
    enabled: Boolean(enabled),
    apiClient: effectiveApiClient,
    session: effectiveSession,
    authProvider: effectiveAuthProvider
  }), [
    effectiveApiClient,
    effectiveAuthProvider,
    effectiveSession,
    enabled,
    normalizedTransactionId
  ]);
  const [requestState, setRequestState] = useState({
    identity: null,
    detail: null,
    isLoading: false,
    error: null
  });
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const requestIdentityRef = useRef(requestIdentity);

  useLayoutEffect(() => {
    requestIdentityRef.current = requestIdentity;
  }, [requestIdentity]);

  const clear = useCallback((identity) => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
    setRequestState({ identity, detail: null, isLoading: false, error: null });
  }, []);

  const canRequest = Boolean(
    requestIdentity.enabled
    && requestIdentity.apiClient
    && requestIdentity.transactionId
  );

  const load = useCallback(async () => {
    const identity = requestIdentity;
    if (requestIdentityRef.current !== identity) {
      return null;
    }
    if (!canRequest) {
      clear(identity);
      return null;
    }

    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setRequestState({ identity, detail: null, isLoading: true, error: null });

    try {
      const nextDetail = await identity.apiClient.getScoredTransactionDetail(identity.transactionId, {
        signal: abortController.signal
      });
      if (requestSeqRef.current !== requestSeq || requestIdentityRef.current !== identity) {
        return null;
      }
      const validation = validateTransactionRiskIntelligenceDetail(nextDetail);
      const responseTransactionId = validation.valid ? normalizeTransactionId(nextDetail.transactionId) : null;
      if (!validation.valid || responseTransactionId !== identity.transactionId) {
        const validationError = new Error("INVALID_TRANSACTION_RISK_INTELLIGENCE_RESPONSE");
        validationError.code = validation.valid ? "TRANSACTION_ID_MISMATCH" : validation.reason;
        setRequestState({ identity, detail: null, isLoading: true, error: validationError });
        return null;
      }
      setRequestState({ identity, detail: nextDetail, isLoading: true, error: null });
      return nextDetail;
    } catch (apiError) {
      if (
        requestSeqRef.current !== requestSeq
        || requestIdentityRef.current !== identity
        || isAbortError(apiError)
      ) {
        return null;
      }
      setRequestState({ identity, detail: null, isLoading: true, error: apiError });
      return null;
    } finally {
      if (requestSeqRef.current === requestSeq && requestIdentityRef.current === identity) {
        setRequestState((current) => current.identity === identity
          ? { ...current, isLoading: false }
          : current);
        if (abortControllerRef.current === abortController) {
          abortControllerRef.current = null;
        }
      }
    }
  }, [canRequest, clear, requestIdentity]);

  useEffect(() => {
    if (!canRequest) {
      clear(requestIdentity);
      return;
    }
    void load();
  }, [canRequest, clear, load, requestIdentity]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
  }, []);

  const ownsCurrentRequest = requestState.identity === requestIdentity;
  return {
    detail: ownsCurrentRequest ? requestState.detail : null,
    isLoading: canRequest && (!ownsCurrentRequest || requestState.isLoading),
    error: ownsCurrentRequest ? requestState.error : null,
    refresh: load
  };
}

function normalizeTransactionId(transactionId) {
  return transactionId === null || transactionId === undefined ? "" : String(transactionId).trim();
}
