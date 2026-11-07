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
  const uncertainTransactionIdRef = useRef(null);
  const reconciliationInFlightRef = useRef(null);

  const cancel = useCallback(() => {
    requestSeqRef.current += 1;
    abortControllerRef.current?.abort();
    submitAbortControllerRef.current?.abort();
    abortControllerRef.current = null;
    submitAbortControllerRef.current = null;
    reconciliationInFlightRef.current = null;
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
      const current = previous.owner === owner ? previous : stateForOwner(owner, uncertainTransactionIdRef.current);
      return { ...current, ...(typeof update === "function" ? update(current) : update), owner };
    });
  }, [owner]);

  const clear = useCallback(() => {
    if (currentOwnerRef.current !== owner) {
      return;
    }
    cancel();
    setState(stateForOwner(owner, uncertainTransactionIdRef.current));
  }, [cancel, owner]);

  const load = useCallback(async () => {
    if (currentOwnerRef.current !== owner || !canReadFeedback
        || uncertainTransactionIdRef.current === owner.transactionId) {
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
    if (uncertainTransactionIdRef.current === owner.transactionId) {
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
        && apiError?.reasonCode === "FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED";
      const duplicate = apiError?.status === 409
        && apiError?.reasonCode === "FRAUD_FEEDBACK_ALREADY_RECORDED";
      const outcomeUnknown = apiError?.status === 503
        && apiError?.reasonCode === "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN";
      if (outcomeUnknown) {
        uncertainTransactionIdRef.current = owner.transactionId;
      }
      updateState({
        submitState: outcomeUnknown
          ? "outcome-unknown"
          : changedOccurrence ? "stale-occurrence" : (duplicate ? "duplicate" : "error"),
        reconciliationState: outcomeUnknown ? "ready" : "idle",
        submitError: apiError
      });
      return null;
    } finally {
      if (isCurrent() && submitAbortControllerRef.current === controller) {
        submitAbortControllerRef.current = null;
      }
    }
  }, [canCreateFeedback, owner, state, updateState]);

  const checkRecordedFeedback = useCallback(async () => {
    if (currentOwnerRef.current !== owner || !canReadFeedback
        || uncertainTransactionIdRef.current !== owner.transactionId) {
      return null;
    }
    if (state.owner === owner && state.reconciliationState === "access-blocked") {
      return null;
    }
    if (reconciliationInFlightRef.current?.owner === owner) {
      return null;
    }

    abortControllerRef.current?.abort();
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const sequence = ++requestSeqRef.current;
    const request = { owner, controller, sequence };
    reconciliationInFlightRef.current = request;
    const isCurrent = () => currentOwnerRef.current === owner
      && requestSeqRef.current === sequence
      && !controller.signal.aborted;
    updateState({ reconciliationState: "checking", reconciliationError: null });
    try {
      const nextFeedback = await owner.apiClient.getFraudFeedback(owner.transactionId, {
        signal: controller.signal
      });
      if (!isCurrent()) {
        return null;
      }
      if (!isValidFeedbackResponse(nextFeedback, owner.transactionId)) {
        updateState({ reconciliationState: "unresolved", reconciliationError: "invalid-response", feedback: null });
        return null;
      }
      uncertainTransactionIdRef.current = null;
      updateState({
        feedback: nextFeedback,
        submitState: "reconciled",
        reconciliationState: "resolved",
        reconciliationError: null,
        submitError: null
      });
      return nextFeedback;
    } catch (apiError) {
      if (!isCurrent() || isAbortError(apiError)) {
        return null;
      }
      if (apiError?.status === 404) {
        updateState({ reconciliationState: "not-found", reconciliationError: null, feedback: null });
      } else if (apiError?.status === 401 || apiError?.status === 403) {
        updateState({ reconciliationState: "access-blocked", reconciliationError: null, feedback: null });
      } else {
        updateState({ reconciliationState: "unresolved", reconciliationError: "unavailable", feedback: null });
      }
      return null;
    } finally {
      if (isCurrent()) {
        if (abortControllerRef.current === controller) {
          abortControllerRef.current = null;
        }
        if (reconciliationInFlightRef.current === request) {
          reconciliationInFlightRef.current = null;
        }
      }
    }
  }, [canReadFeedback, owner, state, updateState]);

  useEffect(() => {
    if (!canReadFeedback) {
      clear();
      return undefined;
    }
    if (uncertainTransactionIdRef.current === owner.transactionId) {
      return undefined;
    }
    void load();
    return () => {
      if (abortControllerRef.current) {
        cancel();
      }
    };
  }, [canReadFeedback, cancel, clear, load, owner]);

  const ownsState = state.owner === owner && currentOwnerRef.current === owner;
  const currentState = ownsState ? state : stateForOwner(owner, uncertainTransactionIdRef.current);
  return {
    feedback: currentState.feedback,
    isLoading: canReadFeedback && (!ownsState || currentState.isLoading),
    error: currentState.error,
    canReadFeedback,
    canCreateFeedback: canCreateFeedback && uncertainTransactionIdRef.current !== owner.transactionId,
    submitState: currentState.submitState,
    submitError: currentState.submitError,
    reconciliationState: currentState.reconciliationState,
    reconciliationError: currentState.reconciliationError,
    refresh: load,
    checkRecordedFeedback,
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
    submitError: null,
    reconciliationState: "idle",
    reconciliationError: null
  };
}

function stateForOwner(owner, uncertainTransactionId) {
  const state = initialState(owner);
  if (owner?.transactionId && owner.transactionId === uncertainTransactionId) {
    state.submitState = "outcome-unknown";
    state.reconciliationState = "ready";
  }
  return state;
}

function isValidFeedbackResponse(feedback, transactionId) {
  return feedback !== null
    && typeof feedback === "object"
    && !Array.isArray(feedback)
    && feedback.transactionId === transactionId
    && isBoundedText(feedback.feedbackId, 160)
    && isBoundedText(feedback.feedbackLabel, 80)
    && isBoundedText(feedback.analystDecision, 80)
    && isBoundedText(feedback.labelSource, 80)
    && isBoundedText(feedback.feedbackStatus, 80)
    && typeof feedback.createdAt === "string"
    && Number.isFinite(Date.parse(feedback.createdAt))
    && Array.isArray(feedback.decisionReasonCodes)
    && feedback.decisionReasonCodes.length <= 32
    && feedback.decisionReasonCodes.every((reason) => isBoundedText(reason, 120))
    && typeof feedback.notesPresent === "boolean"
    && ["CURRENT", "PRIOR", "UNKNOWN"].includes(feedback.scoringOccurrenceRelation);
}

function isBoundedText(value, maxLength) {
  return typeof value === "string" && value.trim().length > 0 && value.length <= maxLength;
}

function normalizeTransactionId(transactionId) {
  return transactionId === null || transactionId === undefined ? "" : String(transactionId).trim();
}
