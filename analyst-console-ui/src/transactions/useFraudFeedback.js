import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { isAbortError } from "../api/alertsApi.js";
import { ApiError } from "../api/apiError.js";
import { useOptionalWorkspaceRuntime } from "../workspace/useWorkspaceRuntime.js";
import { validateFraudFeedbackRequest } from "./fraudFeedbackValidation.js";
import { isValidFraudFeedbackResponse } from "./fraudFeedbackResponseValidation.js";

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
  const canReadFraudFeedback = runtime?.canReadFraudFeedback === true;
  const canWriteFraudFeedback = runtime?.canWriteFraudFeedback === true;
  const ownerKey = feedbackOwnerKeyFor(runtime);
  const writeCoordinator = runtime?.fraudFeedbackWriteCoordinator;
  const subscribeToWrites = useCallback((listener) => (
    typeof writeCoordinator?.subscribe === "function" ? writeCoordinator.subscribe(listener) : () => {}
  ), [writeCoordinator]);
  const getWriteStatus = useCallback(() => (
    ownerKey && typeof writeCoordinator?.getStatus === "function"
      ? writeCoordinator.getStatus(ownerKey, normalizedTransactionId)
      : "unavailable"
  ), [normalizedTransactionId, ownerKey, writeCoordinator]);
  const writeStatus = useSyncExternalStore(subscribeToWrites, getWriteStatus, getWriteStatus);
  const canReadFeedback = Boolean(enabled && runtimeReady && canReadFraudFeedback && ownerKey && normalizedTransactionId
    && typeof effectiveApiClient?.getFraudFeedback === "function");
  const canCreateFeedback = Boolean(canReadFeedback && canWriteFraudFeedback
    && typeof effectiveApiClient?.createFraudFeedback === "function"
    && /^[0-9a-f]{64}$/.test(token) && writeStatus === "idle");
  const owner = useMemo(() => ({
    enabled: Boolean(enabled && runtimeReady),
    transactionId: normalizedTransactionId,
    scoringOccurrenceToken: token,
    ownerKey,
    apiClient: effectiveApiClient,
    writeCoordinator,
    session: runtime?.session,
    authProvider: runtime?.authProvider,
    resetKey: runtime?.workspaceSessionResetKey,
    boundaryKey: runtime?.sessionOwnerBoundaryKey
  }), [enabled, effectiveApiClient, normalizedTransactionId, ownerKey, runtime, runtimeReady, token, writeCoordinator]);
  const [state, setState] = useState(() => initialState(null));
  const stateRef = useRef(state);
  const currentOwnerRef = useRef(null);
  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef(null);
  const submitAbortControllerRef = useRef(null);
  const inFlightWriteRef = useRef(null);
  const reconciliationInFlightRef = useRef(null);

  const cancel = useCallback(() => {
    requestSeqRef.current += 1;
    abortControllerRef.current?.abort();
    const inFlightWrite = inFlightWriteRef.current;
    if (inFlightWrite) {
      inFlightWrite.coordinator.markUncertain(
        inFlightWrite.ownerKey,
        inFlightWrite.transactionId,
        inFlightWrite.token
      );
      inFlightWriteRef.current = null;
    }
    submitAbortControllerRef.current?.abort();
    abortControllerRef.current = null;
    submitAbortControllerRef.current = null;
    reconciliationInFlightRef.current = null;
  }, []);

  useLayoutEffect(() => {
    stateRef.current = state;
  }, [state]);

  useLayoutEffect(() => {
    currentOwnerRef.current = owner;
    setState((previous) => previous.owner === owner ? previous : stateForOwner(owner, getWriteStatus()));
    return () => {
      if (currentOwnerRef.current === owner) {
        currentOwnerRef.current = null;
      }
      cancel();
    };
  }, [cancel, getWriteStatus, owner]);

  const updateState = useCallback((update) => {
    if (currentOwnerRef.current !== owner) {
      return;
    }
    setState((previous) => {
      const current = previous.owner === owner ? previous : stateForOwner(owner, getWriteStatus());
      return { ...current, ...(typeof update === "function" ? update(current) : update), owner };
    });
  }, [getWriteStatus, owner]);

  const clear = useCallback(() => {
    if (currentOwnerRef.current !== owner) {
      return;
    }
    cancel();
    setState(stateForOwner(owner, getWriteStatus()));
  }, [cancel, getWriteStatus, owner]);

  const load = useCallback(async () => {
    if (currentOwnerRef.current !== owner || !canReadFeedback
        || owner.writeCoordinator.getStatus(owner.ownerKey, owner.transactionId) === "uncertain") {
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
      if (!isValidFraudFeedbackResponse(nextFeedback, owner.transactionId)) {
        updateState({
          feedback: null,
          error: new ApiError({
            status: 502,
            error: "INVALID_FEEDBACK_RESPONSE",
            message: "Feedback response could not be validated."
          })
        });
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
    if (currentOwnerRef.current !== owner || !canCreateFeedback
        || typeof owner.writeCoordinator?.reserve !== "function"
        || !/^[0-9a-f]{64}$/.test(owner.scoringOccurrenceToken)) {
      return null;
    }
    if (state.owner === owner && ([401, 403].includes(state.error?.status)
        || state.error?.error === "INVALID_FEEDBACK_RESPONSE")) {
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
    const operationToken = owner.writeCoordinator.reserve(owner.ownerKey, owner.transactionId);
    if (!operationToken) {
      const currentStatus = owner.writeCoordinator.getStatus(owner.ownerKey, owner.transactionId);
      if (currentStatus === "uncertain") {
        updateState({ submitState: "outcome-unknown", reconciliationState: "ready", submitError: null });
      } else if (currentStatus === "recorded") {
        updateState({ submitState: "duplicate", submitError: null });
      } else if (currentStatus === "in-flight") {
        updateState({ submitState: "submitting", submitError: null });
      } else {
        updateState({ submitState: "error", submitError: new ApiError({
          status: 503,
          error: "Service Unavailable",
          message: "Feedback submission is temporarily unavailable."
        }) });
      }
      return null;
    }
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    requestSeqRef.current += 1;
    submitAbortControllerRef.current?.abort();
    const controller = new AbortController();
    submitAbortControllerRef.current = controller;
    const sequence = requestSeqRef.current;
    inFlightWriteRef.current = {
      coordinator: owner.writeCoordinator,
      ownerKey: owner.ownerKey,
      transactionId: owner.transactionId,
      token: operationToken
    };
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
      const outcome = classifyFeedbackWriteOutcome({ response: created, transactionId: owner.transactionId });
      if (outcome === "uncertain") {
        owner.writeCoordinator.markUncertain(owner.ownerKey, owner.transactionId, operationToken);
        inFlightWriteRef.current = null;
        updateState({
          feedback: null,
          submitState: "outcome-unknown",
          reconciliationState: "ready",
          submitError: null
        });
        return null;
      }
      if (inFlightWriteRef.current?.token === operationToken) {
        inFlightWriteRef.current = null;
      }
      updateState({ feedback: created, submitState: "success", submitError: null });
      owner.writeCoordinator.markRecorded(owner.ownerKey, owner.transactionId, operationToken);
      return created;
    } catch (apiError) {
      if (!isCurrent()) {
        return null;
      }
      const outcome = classifyFeedbackWriteOutcome({ error: apiError, transactionId: owner.transactionId });
      const changedOccurrence = apiError?.status === 409
        && apiError?.reasonCode === "FRAUD_FEEDBACK_SCORING_OCCURRENCE_CHANGED";
      const duplicate = apiError?.status === 409
        && apiError?.reasonCode === "FRAUD_FEEDBACK_ALREADY_RECORDED";
      if (outcome === "uncertain") {
        owner.writeCoordinator.markUncertain(owner.ownerKey, owner.transactionId, operationToken);
      } else if (duplicate) {
        owner.writeCoordinator.markRecorded(owner.ownerKey, owner.transactionId, operationToken);
      } else {
        owner.writeCoordinator.release(owner.ownerKey, owner.transactionId, operationToken);
      }
      if (inFlightWriteRef.current?.token === operationToken) {
        inFlightWriteRef.current = null;
      }
      updateState({
        feedback: null,
        submitState: outcome === "uncertain"
          ? "outcome-unknown"
          : changedOccurrence ? "stale-occurrence" : (duplicate ? "duplicate" : "error"),
        reconciliationState: outcome === "uncertain" ? "ready" : "idle",
        submitError: outcome === "uncertain" ? null : apiError
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
        || owner.writeCoordinator.getStatus(owner.ownerKey, owner.transactionId) !== "uncertain") {
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
      if (!isValidFraudFeedbackResponse(nextFeedback, owner.transactionId)) {
        updateState({ reconciliationState: "unresolved", reconciliationError: "invalid-response", feedback: null });
        return null;
      }
      updateState({
        feedback: nextFeedback,
        submitState: "reconciled",
        reconciliationState: "resolved",
        reconciliationError: null,
        submitError: null
      });
      owner.writeCoordinator.markRecorded(owner.ownerKey, owner.transactionId);
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
    if (writeStatus === "uncertain" || writeStatus === "in-flight"
        || (writeStatus === "recorded" && stateRef.current.owner === owner && stateRef.current.feedback)) {
      return undefined;
    }
    void load();
    return () => {
      if (abortControllerRef.current) {
        cancel();
      }
    };
  }, [canReadFeedback, cancel, clear, load, owner, writeStatus]);

  const ownsState = state.owner === owner && currentOwnerRef.current === owner;
  const currentState = ownsState ? stateWithWriteStatus(state, writeStatus) : stateForOwner(owner, writeStatus);
  return {
    feedback: currentState.feedback,
    isLoading: canReadFeedback && currentState.isLoading,
    error: currentState.error,
    canReadFeedback,
    canCreateFeedback: canCreateFeedback
      && ![401, 403].includes(currentState.error?.status)
      && currentState.error?.error !== "INVALID_FEEDBACK_RESPONSE",
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

function stateForOwner(owner, writeStatus) {
  const state = initialState(owner);
  if (writeStatus === "uncertain") {
    state.submitState = "outcome-unknown";
    state.reconciliationState = "ready";
  } else if (writeStatus === "in-flight") {
    state.submitState = "submitting";
  } else if (writeStatus === "recorded") {
    state.submitState = "duplicate";
  }
  return state;
}

function stateWithWriteStatus(state, writeStatus) {
  if (writeStatus === "uncertain" && state.submitState !== "outcome-unknown") {
    return { ...state, feedback: null, submitState: "outcome-unknown", reconciliationState: "ready" };
  }
  if (writeStatus === "in-flight" && state.submitState !== "submitting") {
    return { ...state, submitState: "submitting" };
  }
  if (writeStatus === "recorded" && !state.feedback
      && !["success", "reconciled", "duplicate"].includes(state.submitState)) {
    return { ...state, submitState: "duplicate" };
  }
  return state;
}

function feedbackOwnerKeyFor(runtime) {
  const session = runtime?.session;
  const authProviderKind = runtime?.authProvider?.kind;
  if (typeof session?.userId !== "string" || session.userId.trim() === ""
      || typeof authProviderKind !== "string" || authProviderKind.trim() === ""
      || typeof runtime?.sessionOwnerBoundaryKey !== "string" || runtime.sessionOwnerBoundaryKey.trim() === "") {
    return null;
  }
  return JSON.stringify([runtime.sessionOwnerBoundaryKey, session.userId.trim()]);
}

function classifyFeedbackWriteOutcome({ error, response, transactionId }) {
  if (!error) {
    return isValidFraudFeedbackResponse(response, transactionId) ? "confirmed" : "uncertain";
  }
  if (error?.status === 503 && error?.reasonCode === "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN") {
    return "uncertain";
  }
  const status = error?.status;
  if (!Number.isInteger(status) || status === 0 || status === 408 || status === 429 || status >= 500) {
    return "uncertain";
  }
  return "rejected";
}

function normalizeTransactionId(transactionId) {
  return transactionId === null || transactionId === undefined ? "" : String(transactionId).trim();
}
