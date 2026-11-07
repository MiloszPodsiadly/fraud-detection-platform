import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { ApiError } from "../api/apiError.js";
import { useOptionalWorkspaceRuntime } from "../workspace/useWorkspaceRuntime.js";
import { validateFraudFeedbackRequest } from "./fraudFeedbackValidation.js";

export function useFraudFeedback({
  transactionId,
  scoringOccurrenceToken,
  enabled = true,
  apiClient
} = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const effectiveApiClient = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const normalizedTransactionId = normalizeTransactionId(transactionId);
  const token = typeof scoringOccurrenceToken === "string" ? scoringOccurrenceToken : "";
  const runtimeReady = !runtime || (!runtime.sessionBootstrapPending && runtime.sessionState?.status === "authenticated");
  const canReadFeedback = Boolean(enabled && runtimeReady && normalizedTransactionId
    && typeof effectiveApiClient?.getFraudFeedback === "function");
  const canCreateFeedback = Boolean(canReadFeedback && typeof effectiveApiClient?.createFraudFeedback === "function"
    && /^[0-9a-f]{64}$/.test(token));
  const owner = useMemo(() => ({
    enabled: Boolean(enabled && runtimeReady),
    transactionId: normalizedTransactionId,
    scoringOccurrenceToken: token,
    apiClient: effectiveApiClient,
    session: runtime?.session,
    authProvider: runtime?.authProvider,
    resetKey: runtime?.workspaceSessionResetKey,
    boundaryKey: runtime?.sessionOwnerBoundaryKey
  }), [enabled, effectiveApiClient, normalizedTransactionId, runtime, runtimeReady, token]);
  const [state, setState] = useState(() => initialState(null));
  const currentOwnerRef = useRef(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const submitAbortControllerRef = useRef(null);

  const cancel = useCallback(() => {
    requestSeqRef.current += 1;
    abortControllerRef.current?.abort();
    submitAbortControllerRef.current?.abort();
    abortControllerRef.current = null;
    submitAbortControllerRef.current = null;
  }, []);

  useLayoutEffect(() => {
    currentOwnerRef.current = owner;
    return () => {
      if (currentOwnerRef.current === owner) {
        currentOwnerRef.current = null;
      }
      cancel();
    };
  }, [cancel, owner]);

  const updateState = useCallback((update) => {
    if (currentOwnerRef.current !== owner) {
      return;
    }
    setState((previous) => {
      const current = previous.owner === owner ? previous : initialState(owner);
      return { ...current, ...(typeof update === "function" ? update(current) : update), owner };
    });
  }, [owner]);

  const clear = useCallback(() => {
    if (currentOwnerRef.current !== owner) {
      return;
    }
    cancel();
    setState(initialState(owner));
  }, [cancel, owner]);

  const load = useCallback(async () => {
    if (currentOwnerRef.current !== owner || !canReadFeedback) {
      return null;
    }
    abortControllerRef.current?.abort();
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const sequence = ++requestSeqRef.current;
    const isCurrent = () => currentOwnerRef.current === owner
      && requestSeqRef.current === sequence
      && !controller.signal.aborted;
    updateState({ isLoading: true, error: null });
    try {
      const nextFeedback = await owner.apiClient.getFraudFeedback(owner.transactionId, {
        signal: controller.signal
      });
      if (!isCurrent()) {
        return null;
      }
      updateState({ feedback: nextFeedback, error: null });
      return nextFeedback;
    } catch (apiError) {
      if (!isCurrent() || isAbortError(apiError)) {
        return null;
      }
      if (apiError?.status === 404) {
        updateState({ feedback: null, error: null });
        return null;
      }
      updateState({ feedback: null, error: apiError });
      return null;
    } finally {
      if (isCurrent()) {
        updateState({ isLoading: false });
        if (abortControllerRef.current === controller) {
          abortControllerRef.current = null;
        }
      }
    }
  }, [canReadFeedback, owner, updateState]);

  const submit = useCallback(async (request) => {
    if (currentOwnerRef.current !== owner || !canCreateFeedback) {
      return null;
    }
    const validation = validateFraudFeedbackRequest({
      ...request,
      scoringOccurrenceToken: request?.scoringOccurrenceToken ?? owner.scoringOccurrenceToken
    });
    if (!validation.valid) {
      const validationError = new ApiError({
        status: 400,
        error: validation.reason,
        message: "Feedback request is invalid."
      });
      updateState({ submitState: "validation-error", submitError: validationError });
      return null;
    }
    const currentState = state.owner === owner ? state : initialState(owner);
    if (validation.request.scoringOccurrenceToken !== owner.scoringOccurrenceToken) {
      const validationError = new ApiError({
        status: 400,
        error: "SCORING_OCCURRENCE_TOKEN_MISMATCH",
        message: "Feedback request is invalid."
      });
      updateState({ submitState: "validation-error", submitError: validationError });
      return null;
    }
    if (currentState.feedback) {
      return null;
    }
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
    submitAbortControllerRef.current?.abort();
    const controller = new AbortController();
    submitAbortControllerRef.current = controller;
    const sequence = requestSeqRef.current;
    const isCurrent = () => currentOwnerRef.current === owner
      && requestSeqRef.current === sequence
      && !controller.signal.aborted;
    updateState({ submitState: "submitting", submitError: null });
    try {
      const created = await owner.apiClient.createFraudFeedback(owner.transactionId, validation.request, {
        signal: controller.signal
      });
      if (!isCurrent()) {
        return null;
      }
      updateState({ feedback: created, submitState: "success", submitError: null });
      return created;
    } catch (apiError) {
      if (!isCurrent() || isAbortError(apiError)) {
        return null;
      }
      const changedOccurrence = apiError?.status === 409
        && apiError?.error === "FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED";
      updateState({
        submitState: changedOccurrence ? "stale-occurrence" : (apiError?.status === 409 ? "duplicate" : "error"),
        submitError: apiError
      });
      return null;
    } finally {
      if (isCurrent() && submitAbortControllerRef.current === controller) {
        submitAbortControllerRef.current = null;
      }
    }
  }, [canCreateFeedback, owner, state, updateState]);

  useEffect(() => {
    if (!canReadFeedback) {
      clear();
      return undefined;
    }
    void load();
    return () => {
      if (abortControllerRef.current) {
        cancel();
      }
    };
  }, [canReadFeedback, cancel, clear, load]);

  const ownsState = state.owner === owner && currentOwnerRef.current === owner;
  const currentState = ownsState ? state : initialState(owner);
  return {
    feedback: currentState.feedback,
    isLoading: canReadFeedback && (!ownsState || currentState.isLoading),
    error: currentState.error,
    canReadFeedback,
    canCreateFeedback,
    submitState: currentState.submitState,
    submitError: currentState.submitError,
    refresh: load,
    submit
  };
}

function initialState(owner) {
  return {
    owner,
    feedback: null,
    isLoading: false,
    error: null,
    submitState: "idle",
    submitError: null
  };
}

function normalizeTransactionId(transactionId) {
  return transactionId === null || transactionId === undefined ? "" : String(transactionId).trim();
}
