import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { ApiError } from "../api/apiError.js";
import { useOptionalWorkspaceRuntime } from "../workspace/useWorkspaceRuntime.js";
import { validateFraudFeedbackRequest } from "./fraudFeedbackValidation.js";

export function useFraudFeedback({
  transactionId,
  enabled = true,
  apiClient
} = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const canReadFeedback = typeof effectiveApiClient?.getFraudFeedback === "function";
  const canCreateFeedback = typeof effectiveApiClient?.createFraudFeedback === "function";
  const normalizedTransactionId = normalizeTransactionId(transactionId);
  const [feedback, setFeedback] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const [submitState, setSubmitState] = useState("idle");
  const [submitError, setSubmitError] = useState(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const submitAbortControllerRef = useRef(null);

  const clear = useCallback(() => {
    abortControllerRef.current?.abort();
    submitAbortControllerRef.current?.abort();
    abortControllerRef.current = null;
    submitAbortControllerRef.current = null;
    requestSeqRef.current += 1;
    setFeedback(null);
    setError(null);
    setIsLoading(false);
    setSubmitState("idle");
    setSubmitError(null);
  }, []);

  const load = useCallback(async () => {
    if (!enabled || !canReadFeedback || !normalizedTransactionId) {
      clear();
      return null;
    }
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    const requestSeq = requestSeqRef.current + 1;
    requestSeqRef.current = requestSeq;
    setIsLoading(true);
    setError(null);
    try {
      const nextFeedback = await effectiveApiClient.getFraudFeedback(normalizedTransactionId, {
        signal: abortController.signal
      });
      if (requestSeqRef.current !== requestSeq) {
        return null;
      }
      setFeedback(nextFeedback);
      return nextFeedback;
    } catch (apiError) {
      if (requestSeqRef.current !== requestSeq || isAbortError(apiError)) {
        return null;
      }
      if (apiError?.status === 404) {
        setFeedback(null);
        setError(null);
        return null;
      }
      setFeedback(null);
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
  }, [canReadFeedback, clear, effectiveApiClient, enabled, normalizedTransactionId]);

  const submit = useCallback(async (request) => {
    const validation = validateFraudFeedbackRequest(request);
    if (!validation.valid) {
      const validationError = new ApiError({
        status: 400,
        error: validation.reason,
        message: "Feedback request is invalid."
      });
      setSubmitState("validation-error");
      setSubmitError(validationError);
      return null;
    }
    if (!canCreateFeedback || !normalizedTransactionId || feedback) {
      return null;
    }
    submitAbortControllerRef.current?.abort();
    const abortController = new AbortController();
    submitAbortControllerRef.current = abortController;
    setSubmitState("submitting");
    setSubmitError(null);
    try {
      const created = await effectiveApiClient.createFraudFeedback(normalizedTransactionId, validation.request, {
        signal: abortController.signal
      });
      setFeedback(created);
      setSubmitState("success");
      return created;
    } catch (apiError) {
      if (isAbortError(apiError)) {
        return null;
      }
      setSubmitState(apiError?.status === 409 ? "duplicate" : "error");
      setSubmitError(apiError);
      return null;
    } finally {
      if (submitAbortControllerRef.current === abortController) {
        submitAbortControllerRef.current = null;
      }
    }
  }, [canCreateFeedback, effectiveApiClient, feedback, normalizedTransactionId]);

  useEffect(() => {
    if (!enabled || !canReadFeedback || !normalizedTransactionId) {
      clear();
      return;
    }
    load();
  }, [canReadFeedback, clear, effectiveApiClient, enabled, load, normalizedTransactionId]);

  useEffect(() => () => {
    abortControllerRef.current?.abort();
    submitAbortControllerRef.current?.abort();
    requestSeqRef.current += 1;
  }, []);

  return {
    feedback,
    isLoading,
    error,
    submitState,
    submitError,
    refresh: load,
    submit
  };
}

function normalizeTransactionId(transactionId) {
  return transactionId === null || transactionId === undefined ? "" : String(transactionId).trim();
}
