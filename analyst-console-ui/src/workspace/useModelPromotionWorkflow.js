import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { createIdempotencyKey } from "../utils/idempotencyKey.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

export function useModelPromotionWorkflow({ enabled = true, apiClient } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const client = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const [state, setState] = useState(null);
  const [assessment, setAssessment] = useState(null);
  const [stateError, setStateError] = useState(null);
  const [assessmentError, setAssessmentError] = useState(null);
  const [mutationError, setMutationError] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isMutating, setIsMutating] = useState(false);
  const selectionRef = useRef(null);
  const sequenceRef = useRef(0);
  const controllerRef = useRef(null);

  const clear = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    sequenceRef.current += 1;
    selectionRef.current = null;
    setState(null); setAssessment(null); setStateError(null); setAssessmentError(null); setMutationError(null); setIsLoading(false);
  }, []);

  const load = useCallback(async (selection = selectionRef.current) => {
    if (!enabled || !client || !selection) return null;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const sequence = sequenceRef.current + 1;
    sequenceRef.current = sequence;
    selectionRef.current = selection;
    setIsLoading(true); setState(null); setAssessment(null); setStateError(null); setAssessmentError(null); setMutationError(null);
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

  const mutate = useCallback(async (method, payload, proposalId) => {
    if (!client || !selectionRef.current) return { ok: false };
    setIsMutating(true); setMutationError(null);
    try {
      await method(payload, proposalId);
      await load(selectionRef.current);
      return { ok: true };
    } catch (error) {
      setMutationError(error);
      if (error?.status === 409) await load(selectionRef.current);
      return { ok: false, error };
    } finally { setIsMutating(false); }
  }, [client, load]);

  const register = useCallback(() => mutate(
    (payload) => client.registerModelPromotionCandidate(payload),
    { selection: commandSelection(selectionRef.current), operationId: createIdempotencyKey("model-promotion-register") }
  ), [client, mutate]);
  const propose = useCallback((requestedTargetState, rationale) => mutationCommand("propose", requestedTargetState, rationale), [state, mutate, client]);
  const retire = useCallback((rationale) => mutationCommand("retire", "RETIRED", rationale), [state, mutate, client]);
  const review = useCallback(async (proposalId, outcome, rationale) => mutate(
    (payload, id) => client.reviewModelPromotionProposal(id, payload),
    await reviewPayload(outcome, rationale), proposalId
  ), [client, mutate]);

  async function mutationCommand(kind, requestedTargetState, rationale) {
    if (!state || !selectionRef.current) return Promise.resolve({ ok: false });
    if (!String(rationale || "").trim()) return Promise.resolve({ ok: false, reason: "rationale-required" });
    const prefix = kind === "retire" ? "model-promotion-retire" : "model-promotion-propose";
    const payload = {
      selection: commandSelection(selectionRef.current), sourceState: state.lifecycleState,
      expectedRevision: state.currentRevision, fromInclusive: selectionRef.current.fromInclusive,
      toInclusive: selectionRef.current.toInclusive, operationId: createIdempotencyKey(prefix),
      proposalId: createIdempotencyKey("model-promotion-proposal"), rationaleSha256: await rationaleHash(rationale)
    };
    if (kind === "propose") {
      return mutate((command) => client.proposeModelPromotionTransition({ ...command, requestedTargetState }), payload);
    }
    return mutate((command) => client.proposeModelRetirement(command), payload);
  }

  useEffect(() => { if (!enabled || !client) clear(); }, [clear, client, enabled]);
  useEffect(() => () => controllerRef.current?.abort(), []);
  return { state, assessment, stateError, assessmentError, mutationError, isLoading, isMutating, load, clear, register, propose, retire, review };
}

function commandSelection(selection) {
  const { modelName, modelVersion, featureContractVersion, modelArtifactSha256, environment, intendedUse } = selection;
  return { modelName, modelVersion, featureContractVersion, modelArtifactSha256, environment, intendedUse };
}
async function reviewPayload(outcome, rationale) {
  if (!String(rationale || "").trim()) throw new Error("A review rationale is required.");
  return { approvalId: createIdempotencyKey("model-promotion-review"), outcome, operationId: createIdempotencyKey("model-promotion-review-operation"), rationaleSha256: await rationaleHash(rationale) };
}
async function rationaleHash(value) {
  if (!globalThis.crypto?.subtle) throw new Error("Cryptographic rationale hashing is unavailable.");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
