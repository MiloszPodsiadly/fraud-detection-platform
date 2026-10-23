import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { createIdempotencyKey } from "../utils/idempotencyKey.js";
import { confirmPolicyAssertion } from "../governance/policyWebAuthn.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const SIGNED_PENDING_KEY = "model-promotion-pending-signed-review";
function rememberSignedReview(payload, proposalId, selection) {
  try { sessionStorage.setItem(SIGNED_PENDING_KEY, JSON.stringify({
    payload: { operationId: payload.operationId, approvalId: payload.approvalId,
      outcome: payload.outcome, rationaleSha256: payload.rationaleSha256 },
    proposalId, mutationSelection: selection
  })); } catch { /* The open view still retains the operation. */ }
}
function restoredSignedReview(client) {
  try {
    const value = JSON.parse(sessionStorage.getItem(SIGNED_PENDING_KEY) || "null");
    if (!value?.payload?.operationId || !value.proposalId || !value.mutationSelection
        || !["APPROVED", "REJECTED"].includes(value.payload.outcome)) return null;
    return { ...value, checkOnly: true,
      recovery: (payload, id) => verifySignedReviewReceipt(client, payload, id) };
  } catch { return null; }
}
function forgetSignedReview(operationId) {
  try {
    if (JSON.parse(sessionStorage.getItem(SIGNED_PENDING_KEY) || "null")?.payload?.operationId === operationId)
      sessionStorage.removeItem(SIGNED_PENDING_KEY);
  } catch { /* Browser storage is optional. */ }
}

export function useModelPromotionWorkflow({ enabled = true, apiClient } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const client = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const [state, setState] = useState(null);
  const [assessment, setAssessment] = useState(null);
  const [stateError, setStateError] = useState(null);
  const [assessmentError, setAssessmentError] = useState(null);
  const [mutationError, setMutationError] = useState(null);
  const [hasRetryableMutation, setHasRetryableMutation] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [isMutating, setIsMutating] = useState(false);
  const selectionRef = useRef(null);
  const sequenceRef = useRef(0);
  const controllerRef = useRef(null);
  const mutationInFlightRef = useRef(false);
  const mutationSequenceRef = useRef(0);
  const retryableMutationRef = useRef(null);

  const clear = useCallback((discardPending = false) => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    sequenceRef.current += 1;
    mutationSequenceRef.current += 1;
    const unresolved = !discardPending && retryableMutationRef.current?.checkOnly;
    if (!unresolved) retryableMutationRef.current = null;
    selectionRef.current = null;
    setState(null); setAssessment(null); setStateError(null); setAssessmentError(null);
    if (!unresolved) setMutationError(null);
    setHasRetryableMutation(Boolean(unresolved)); setIsLoading(false); setIsMutating(mutationInFlightRef.current);
  }, []);

  const load = useCallback(async (selection = selectionRef.current, { preserveMutationError = false } = {}) => {
    if (!enabled || !client || !selection) return null;
    if (!retryableMutationRef.current) {
      const restored = restoredSignedReview(client);
      if (restored) {
        retryableMutationRef.current = restored;
        setHasRetryableMutation(true);
        const uncertain = new Error(`Outcome uncertain for operation ${restored.payload.operationId}. Check its durable receipt.`);
        uncertain.code = "UNCERTAIN_OUTCOME";
        setMutationError(uncertain);
      }
    }
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const sequence = sequenceRef.current + 1;
    sequenceRef.current = sequence;
    selectionRef.current = selection;
    setIsLoading(true); setState(null); setAssessment(null); setStateError(null); setAssessmentError(null);
    if (!preserveMutationError && !retryableMutationRef.current?.checkOnly) {
      retryableMutationRef.current = null;
      setMutationError(null);
      setHasRetryableMutation(false);
    }
    const [stateResult, assessmentResult] = await Promise.allSettled([
      client.getModelPromotionState(selection, { signal: controller.signal }),
      client.getModelPromotionAssessment(selection, { signal: controller.signal })
    ]);
    if (sequenceRef.current !== sequence) return null;
    if (stateResult.status === "fulfilled") setState(stateResult.value);
    else if (!isAbortError(stateResult.reason)) setStateError(stateResult.reason);
    if (assessmentResult.status === "fulfilled") setAssessment(assessmentResult.value);
    else if (!isAbortError(assessmentResult.reason)) setAssessmentError(assessmentResult.reason);
    if (sequenceRef.current === sequence) {
      setIsLoading(false);
      if (controllerRef.current === controller) controllerRef.current = null;
    }
    return null;
  }, [client, enabled]);

  const mutate = useCallback(async (method, payload, proposalId, mutationSelection = selectionRef.current,
    recovery = null) => {
    if (!client || !mutationSelection || mutationInFlightRef.current
        || retryableMutationRef.current?.checkOnly) return { ok: false };
    const mutationSequence = mutationSequenceRef.current;
    mutationInFlightRef.current = true;
    setIsMutating(true); setMutationError(null);
    retryableMutationRef.current = null;
    setHasRetryableMutation(false);
    try {
      await method(payload, proposalId);
      if (mutationSequence === mutationSequenceRef.current) {
        if (sameSelection(mutationSelection, selectionRef.current)) await load(selectionRef.current);
        retryableMutationRef.current = null;
        setHasRetryableMutation(false);
      }
      return { ok: true };
    } catch (error) {
      if (recovery && isRetryableMutationError(error)) {
        try {
          await recovery(payload, proposalId);
          forgetSignedReview(payload.operationId);
          if (mutationSequence === mutationSequenceRef.current) {
            if (sameSelection(mutationSelection, selectionRef.current)) await load(selectionRef.current);
            setMutationError(null);
          }
          return { ok: true, recovered: true };
        } catch (lookupFailure) {
          const uncertain = new Error(`Outcome uncertain for operation ${payload.operationId}. Check its durable receipt; the passkey assertion was discarded.`);
          uncertain.code = "UNCERTAIN_OUTCOME";
          if (mutationSequence === mutationSequenceRef.current) {
            retryableMutationRef.current = { payload, proposalId, mutationSelection, recovery, checkOnly: true };
            rememberSignedReview(payload, proposalId, mutationSelection);
            setHasRetryableMutation(true);
            setMutationError(lookupFailure?.code === "RECEIPT_CONFLICT" ? lookupFailure : uncertain);
          }
          return { ok: false, error: lookupFailure?.code === "RECEIPT_CONFLICT" ? lookupFailure : uncertain };
        }
      }
      if (mutationSequence === mutationSequenceRef.current) {
        setMutationError(error);
        if (isRetryableMutationError(error)) {
          retryableMutationRef.current = { method, payload, proposalId, mutationSelection };
          setHasRetryableMutation(true);
        }
        if (error?.status === 409 && sameSelection(mutationSelection, selectionRef.current)) {
          await load(selectionRef.current, { preserveMutationError: true });
        }
      }
      return { ok: false, error };
    } finally {
      mutationInFlightRef.current = false;
      setIsMutating(false);
    }
  }, [client, load]);

  const retryLastMutation = useCallback(async () => {
    const retry = retryableMutationRef.current;
    if (!retry) return { ok: false };
    if (retry.checkOnly) {
      if (mutationInFlightRef.current || !sameSelection(retry.mutationSelection, selectionRef.current))
        return { ok: false };
      mutationInFlightRef.current = true;
      setIsMutating(true);
      try {
        await retry.recovery(retry.payload, retry.proposalId);
        forgetSignedReview(retry.payload.operationId);
        retryableMutationRef.current = null;
        setHasRetryableMutation(false);
        setMutationError(null);
        await load(retry.mutationSelection);
        return { ok: true, recovered: true };
      } catch (error) {
        const uncertain = new Error(`Outcome remains uncertain for operation ${retry.payload.operationId}.`);
        uncertain.code = "UNCERTAIN_OUTCOME";
        setMutationError(error?.code === "RECEIPT_CONFLICT" ? error : uncertain);
        return { ok: false, error };
      } finally {
        mutationInFlightRef.current = false;
        setIsMutating(false);
      }
    }
    return mutate(retry.method, retry.payload, retry.proposalId, retry.mutationSelection);
  }, [mutate, load]);

  const register = useCallback(() => mutate(
    (payload) => client.registerModelPromotionCandidate(payload),
    { selection: commandSelection(selectionRef.current), operationId: createIdempotencyKey("model-promotion-register") }
  ), [client, mutate]);
  const propose = useCallback((requestedTargetState, rationale) => mutationCommand("propose", requestedTargetState, rationale), [state, mutate, client]);
  const retire = useCallback((rationale) => mutationCommand("retire", "RETIRED", rationale), [state, mutate, client]);
  const review = useCallback(async (proposalId, outcome, rationale) => {
    const production = selectionRef.current?.environment === "PRODUCTION";
    return mutate(
    async (payload, id) => {
      if (!production) {
        return client.reviewModelPromotionProposal(id, payload);
      }
      const challenge = await client.startSignedModelPromotionReview(id, payload);
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      return client.finishSignedModelPromotionReview(id,
        { intent: payload, challengeId: challenge.challengeId, responseJson });
    },
    await reviewPayload(outcome, rationale), proposalId, selectionRef.current,
    production ? (payload, id) => verifySignedReviewReceipt(client, payload, id) : null
    );
  }, [client, mutate]);

  async function mutationCommand(kind, requestedTargetState, rationale) {
    if (!state || !selectionRef.current) return Promise.resolve({ ok: false });
    if (!String(rationale || "").trim()) return Promise.resolve({ ok: false, reason: "rationale-required" });
    const prefix = kind === "retire" ? "model-promotion-retire" : "model-promotion-propose";
    const payload = {
      selection: commandSelection(selectionRef.current), sourceState: state.lifecycleState,
      expectedRevision: state.currentRevision, fromInclusive: selectionRef.current.fromInclusive,
      toInclusive: selectionRef.current.toInclusive, operationId: createIdempotencyKey(prefix),
      proposalId: createIdempotencyKey("model-promotion-proposal"), rationale, rationaleSha256: await rationaleHash(rationale)
    };
    if (kind === "propose") {
      return mutate((command) => client.proposeModelPromotionTransition({ ...command, requestedTargetState }), payload);
    }
    return mutate((command) => client.proposeModelRetirement(command), payload);
  }

  useEffect(() => { if (!enabled || !client) clear(true); }, [clear, client, enabled]);
  useEffect(() => () => controllerRef.current?.abort(), []);
  return { state, assessment, stateError, assessmentError, mutationError, hasRetryableMutation, isLoading, isMutating, load, clear, register, propose, retire, review, retryLastMutation };
}

function commandSelection(selection) {
  const { modelName, modelVersion, featureContractVersion, modelArtifactSha256, environment, intendedUse } = selection;
  return { modelName, modelVersion, featureContractVersion, modelArtifactSha256, environment, intendedUse };
}
async function reviewPayload(outcome, rationale) {
  if (!String(rationale || "").trim()) throw new Error("A review rationale is required.");
  return { approvalId: createIdempotencyKey("model-promotion-review"), outcome, operationId: createIdempotencyKey("model-promotion-review-operation"), rationale, rationaleSha256: await rationaleHash(rationale) };
}
async function rationaleHash(value) {
  if (!globalThis.crypto?.subtle) throw new Error("Cryptographic rationale hashing is unavailable.");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function sameSelection(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isRetryableMutationError(error) {
  if (isAbortError(error)) return false;
  return error?.status == null || error.status >= 500;
}

async function verifySignedReviewReceipt(client, payload, proposalId) {
  const receipt = await client.getSignedModelPromotionReviewOperation(proposalId, payload.operationId);
  if (receipt.proposalId !== proposalId || receipt.operationId !== payload.operationId
      || receipt.approvalId !== payload.approvalId || receipt.outcome !== payload.outcome
      || receipt.rationaleSha256 !== payload.rationaleSha256) {
    const conflict = new Error("Durable review receipt conflicts with the signed intent.");
    conflict.code = "RECEIPT_CONFLICT";
    throw conflict;
  }
  return receipt;
}
