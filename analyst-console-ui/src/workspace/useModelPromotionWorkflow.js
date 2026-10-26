import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/apiErrors.js";
import { createIdempotencyKey } from "../utils/idempotencyKey.js";
import { confirmPolicyAssertion } from "../governance/policyWebAuthn.js";
import { useOptionalWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const SIGNED_PENDING_KEY = "model-promotion-pending-signed-review";
const SIGNED_PENDING_VERSION = 1;
const ACTOR_ID_PATTERN = /^oidc:[a-f0-9]{64}$/;
const SELECTION_FIELDS = Object.freeze([
  "modelName", "modelVersion", "featureContractVersion", "modelArtifactSha256",
  "environment", "intendedUse", "fromInclusive", "toInclusive"
]);

export function useModelPromotionWorkflow({ enabled = true, apiClient } = {}) {
  const runtime = useOptionalWorkspaceRuntime();
  const client = apiClient !== undefined ? apiClient : runtime?.apiClient;
  const runtimeRef = useRef(runtime);
  const enabledRef = useRef(enabled);
  const clientRef = useRef(client);
  runtimeRef.current = runtime;
  enabledRef.current = enabled;
  clientRef.current = client;

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
  const ownerBoundaryRef = useRef(null);

  const clear = useCallback((discardPending = false) => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    sequenceRef.current += 1;
    mutationSequenceRef.current += 1;
    const unresolved = !discardPending && retryableMutationRef.current?.checkOnly;
    if (discardPending) {
      clearPersistedSignedReview();
      retryableMutationRef.current = null;
    } else if (!unresolved) {
      retryableMutationRef.current = null;
    }
    selectionRef.current = null;
    setState(null);
    setAssessment(null);
    setStateError(null);
    setAssessmentError(null);
    if (!unresolved || discardPending) setMutationError(null);
    setHasRetryableMutation(Boolean(unresolved));
    setIsLoading(false);
    setIsMutating(mutationInFlightRef.current);
  }, []);

  const load = useCallback(async (selection = selectionRef.current, { preserveMutationError = false } = {}) => {
    if (!enabled || !client || !selection) return null;

    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const sequence = sequenceRef.current + 1;
    sequenceRef.current = sequence;
    selectionRef.current = selection;
    setIsLoading(true);
    setState(null);
    setAssessment(null);
    setStateError(null);
    setAssessmentError(null);

    const currentRuntime = runtimeRef.current;
    let ownerKey = null;
    let selectionHash = null;
    if (currentRuntime) {
      ownerKey = await resolveTrustedOwnerKey(currentRuntime);
      selectionHash = await selectionDigest(selection);
      if (sequenceRef.current !== sequence) return null;

      if (!ownerKey || !selectionHash) {
        if (!currentRuntime.sessionBootstrapPending) {
          clearPersistedSignedReview();
          discardInMemorySignedReview();
        }
      } else {
        const inMemory = retryableMutationRef.current;
        if (inMemory?.checkOnly
            && (inMemory.ownerKey !== ownerKey || inMemory.selectionHash !== selectionHash)) {
          forgetSignedReview(inMemory.payload.operationId, inMemory.ownerKey);
          discardInMemorySignedReview();
        }
        if (!retryableMutationRef.current?.checkOnly) {
          const restored = restoredSignedReview(client, ownerKey, selectionHash, selection);
          if (restored) {
            retryableMutationRef.current = restored;
            setHasRetryableMutation(true);
            const uncertain = new Error(`Outcome uncertain for operation ${restored.payload.operationId}. Check its durable receipt.`);
            uncertain.code = "UNCERTAIN_OUTCOME";
            setMutationError(uncertain);
          }
        }
      }
    } else if (!retryableMutationRef.current?.checkOnly) {
      // No runtime identity means this caller cannot safely recover browser-persisted work.
      clearPersistedSignedReview();
    }

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
    if (!hasCurrentOwnerContext() || !client || !mutationSelection || mutationInFlightRef.current
        || retryableMutationRef.current?.checkOnly) return { ok: false };
    const mutationSequence = mutationSequenceRef.current;
    mutationInFlightRef.current = true;
    setIsMutating(true);
    setMutationError(null);
    retryableMutationRef.current = null;
    setHasRetryableMutation(false);

    let signedContext = null;
    try {
      if (recovery) {
        const currentRuntime = runtimeRef.current;
        const ownerKey = await resolveTrustedOwnerKey(currentRuntime);
        const selectionHash = await selectionDigest(mutationSelection);
        signedContext = {
          ownerKey,
          selectionHash,
          boundaryKey: currentRuntime?.sessionOwnerBoundaryKey || ""
        };
        if (!ownerKey || !selectionHash
            || !(await isCurrentSignedReviewContext(signedContext, mutationSelection))) {
          const error = new Error("Signed review requires the current authenticated institutional session.");
          error.code = "SIGNED_REVIEW_OWNER_UNAVAILABLE";
          if (mutationSequence === mutationSequenceRef.current) setMutationError(error);
          return { ok: false, error };
        }
      }

      if (mutationSequence !== mutationSequenceRef.current) return { ok: false };
      await method(payload, proposalId, signedContext
        ? () => isCurrentSignedReviewContext(signedContext, mutationSelection)
        : null);
      if (signedContext
          && !(await isCurrentSignedReviewContext(signedContext, mutationSelection))) return { ok: false };
      if (mutationSequence === mutationSequenceRef.current) {
        if (sameSelection(mutationSelection, selectionRef.current)) await load(selectionRef.current);
        retryableMutationRef.current = null;
        setHasRetryableMutation(false);
      }
      return { ok: true };
    } catch (error) {
      if (recovery && isRetryableMutationError(error)
          && mutationSequence === mutationSequenceRef.current
          && signedContext
          && await isCurrentSignedReviewContext(signedContext, mutationSelection)) {
        try {
          await recovery(payload, proposalId);
          if (!(await isCurrentSignedReviewContext(signedContext, mutationSelection))) return { ok: false };
          forgetSignedReview(payload.operationId, signedContext.ownerKey);
          if (mutationSequence === mutationSequenceRef.current) {
            if (sameSelection(mutationSelection, selectionRef.current)) await load(selectionRef.current);
            setMutationError(null);
          }
          return { ok: true, recovered: true };
        } catch (lookupFailure) {
          if (!(await isCurrentSignedReviewContext(signedContext, mutationSelection))) return { ok: false };
          const uncertain = new Error(`Outcome uncertain for operation ${payload.operationId}. Check its durable receipt; the passkey assertion was discarded.`);
          uncertain.code = "UNCERTAIN_OUTCOME";
          if (mutationSequence === mutationSequenceRef.current) {
            retryableMutationRef.current = {
              payload: signedPayloadMetadata(payload),
              proposalId,
              mutationSelection,
              recovery,
              checkOnly: true,
              ownerKey: signedContext.ownerKey,
              selectionHash: signedContext.selectionHash
            };
            rememberSignedReview(payload, proposalId, signedContext);
            setHasRetryableMutation(true);
            setMutationError(lookupFailure?.code === "RECEIPT_CONFLICT" ? lookupFailure : uncertain);
          }
          return { ok: false, error: lookupFailure?.code === "RECEIPT_CONFLICT" ? lookupFailure : uncertain };
        }
      }
      if (mutationSequence === mutationSequenceRef.current) {
        setMutationError(error);
        if (!recovery && isRetryableMutationError(error)) {
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
    if (!hasCurrentOwnerContext()) return { ok: false };
    const retry = retryableMutationRef.current;
    if (!retry) return { ok: false };
    if (retry.checkOnly) {
      if (mutationInFlightRef.current || !sameSelection(retry.mutationSelection, selectionRef.current))
        return { ok: false };
      mutationInFlightRef.current = true;
      setIsMutating(true);
      const retrySequence = mutationSequenceRef.current;
      let retryContext = null;
      try {
        const currentContext = {
          ownerKey: await resolveTrustedOwnerKey(runtimeRef.current),
          selectionHash: await selectionDigest(retry.mutationSelection),
          boundaryKey: runtimeRef.current?.sessionOwnerBoundaryKey || ""
        };
        retryContext = currentContext;
        if (currentContext.ownerKey !== retry.ownerKey
            || currentContext.selectionHash !== retry.selectionHash
            || !(await isCurrentSignedReviewContext(currentContext, retry.mutationSelection))) {
          forgetSignedReview(retry.payload.operationId, retry.ownerKey);
          if (retryableMutationRef.current === retry) discardInMemorySignedReview();
          return { ok: false };
        }
        await retry.recovery(retry.payload, retry.proposalId);
        if (retrySequence !== mutationSequenceRef.current
            || retryableMutationRef.current !== retry
            || !(await isCurrentSignedReviewContext(currentContext, retry.mutationSelection))) return { ok: false };
        forgetSignedReview(retry.payload.operationId, retry.ownerKey);
        retryableMutationRef.current = null;
        setHasRetryableMutation(false);
        setMutationError(null);
        await load(retry.mutationSelection);
        return { ok: true, recovered: true };
      } catch (error) {
        if (retrySequence !== mutationSequenceRef.current
            || retryableMutationRef.current !== retry
            || !(await isCurrentSignedReviewContext(retryContext, retry.mutationSelection))) return { ok: false };
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
      async (payload, id, isCurrent) => {
        if (!production) return client.reviewModelPromotionProposal(id, payload);
        const challenge = await client.startSignedModelPromotionReview(id, payload);
        if (!await isCurrent?.()) {
          const error = new Error("Authenticated owner changed before signed review assertion.");
          error.code = "SIGNED_REVIEW_OWNER_CHANGED";
          throw error;
        }
        const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
        if (!await isCurrent?.()) {
          const error = new Error("Authenticated owner changed before signed review completion.");
          error.code = "SIGNED_REVIEW_OWNER_CHANGED";
          throw error;
        }
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

  useEffect(() => {
    if (runtime?.sessionBootstrapPending) return;
    if (!enabled || !client || (runtime && runtime.sessionState?.status !== "authenticated")) {
      ownerBoundaryRef.current = null;
      clear(true);
      return;
    }
    const boundaryKey = runtime?.sessionOwnerBoundaryKey || "";
    if (ownerBoundaryRef.current && ownerBoundaryRef.current !== boundaryKey) clear(true);
    ownerBoundaryRef.current = boundaryKey;
  }, [clear, client, enabled, runtime?.sessionBootstrapPending, runtime?.sessionOwnerBoundaryKey,
    runtime?.sessionState?.status]);
  useEffect(() => () => controllerRef.current?.abort(), []);

  const ownerContextCurrent = enabled && Boolean(client)
    && workflowOwnerContextIsCurrent(runtime, ownerBoundaryRef.current);

  function hasCurrentOwnerContext() {
    return Boolean(enabledRef.current && clientRef.current
      && workflowOwnerContextIsCurrent(runtimeRef.current, ownerBoundaryRef.current));
  }

  async function isCurrentSignedReviewContext(context, mutationSelection) {
    if (!context || !enabledRef.current || !clientRef.current) return false;
    const currentRuntime = runtimeRef.current;
    if (!matchesSignedReviewBoundary(currentRuntime, context.boundaryKey)) return false;
    const ownerKey = await resolveTrustedOwnerKey(currentRuntime);
    if (ownerKey !== context.ownerKey) return false;
    const selectedAtCheck = selectionRef.current;
    const currentSelectionHash = await selectionDigest(selectedAtCheck);
    const expectedSelectionHash = await selectionDigest(mutationSelection);
    return matchesSignedReviewBoundary(runtimeRef.current, context.boundaryKey)
      && sameSelection(selectedAtCheck, selectionRef.current)
      && currentSelectionHash !== null && currentSelectionHash === expectedSelectionHash
      && currentSelectionHash === context.selectionHash;
  }

function matchesSignedReviewBoundary(runtime, boundaryKey) {
  return Boolean(runtime && !runtime.sessionBootstrapPending
    && runtime.sessionState?.status === "authenticated"
    && (runtime.sessionOwnerBoundaryKey || "") === boundaryKey);
}

function workflowOwnerContextIsCurrent(runtime, ownerBoundaryKey) {
  if (!runtime) return true;
  const currentBoundaryKey = runtime.sessionOwnerBoundaryKey || "";
  return !runtime.sessionBootstrapPending
    && runtime.sessionState?.status === "authenticated"
    && Boolean(currentBoundaryKey)
    && ownerBoundaryKey === currentBoundaryKey;
}

  function discardInMemorySignedReview() {
    retryableMutationRef.current = null;
    setHasRetryableMutation(false);
    setMutationError(null);
  }

  return {
    state: ownerContextCurrent ? state : null,
    assessment: ownerContextCurrent ? assessment : null,
    stateError: ownerContextCurrent ? stateError : null,
    assessmentError: ownerContextCurrent ? assessmentError : null,
    mutationError: ownerContextCurrent ? mutationError : null,
    hasRetryableMutation: ownerContextCurrent && hasRetryableMutation,
    isLoading: ownerContextCurrent && isLoading,
    isMutating: ownerContextCurrent && isMutating,
    load, clear, register, propose, retire, review, retryLastMutation
  };
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
  const digest = await sha256(value);
  if (!digest) throw new Error("Cryptographic rationale hashing is unavailable.");
  return digest;
}

function sameSelection(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isRetryableMutationError(error) {
  if (isAbortError(error)) return false;
  return error?.status == null || error.status >= 500;
}

async function resolveTrustedOwnerKey(runtime) {
  if (!runtime || runtime.sessionBootstrapPending
      || runtime.sessionState?.status !== "authenticated"
      || !runtime.session?.userId) return null;
  const kind = runtime.authProvider?.kind;
  if (kind === "bff" && ACTOR_ID_PATTERN.test(runtime.session.actorId || "")) {
    return `bff:${runtime.session.actorId}`;
  }
  if (kind !== "oidc") return null;
  const issuer = runtime.session.issuer;
  const subject = runtime.session.subject;
  if (typeof issuer !== "string" || !issuer.trim() || issuer.includes("#")
      || typeof subject !== "string" || !subject.trim() || subject.includes("#")) return null;
  const identityHash = await sha256(`${issuer}#${subject}`);
  return identityHash ? `oidc:oidc:${identityHash}` : null;
}

async function selectionDigest(selection) {
  if (!selection || typeof selection !== "object") return null;
  const descriptor = {};
  for (const field of SELECTION_FIELDS) {
    const value = selection[field];
    if (typeof value !== "string" || !value.trim()) return null;
    descriptor[field] = value;
  }
  return sha256(JSON.stringify(descriptor));
}

async function sha256(value) {
  if (!globalThis.crypto?.subtle) return null;
  try {
    const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
    return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
  } catch {
    return null;
  }
}

function signedPayloadMetadata(payload = {}) {
  return {
    operationId: payload.operationId,
    approvalId: payload.approvalId,
    outcome: payload.outcome,
    rationaleSha256: payload.rationaleSha256
  };
}

function rememberSignedReview(payload, proposalId, context) {
  try {
    sessionStorage.setItem(SIGNED_PENDING_KEY, JSON.stringify({
      version: SIGNED_PENDING_VERSION,
      ownerKey: context.ownerKey,
      selectionHash: context.selectionHash,
      payload: signedPayloadMetadata(payload),
      proposalId
    }));
  } catch { /* In-memory receipt checks remain available when browser storage is disabled. */ }
}

function restoredSignedReview(client, ownerKey, selectionHash, mutationSelection) {
  let value;
  try {
    const raw = sessionStorage.getItem(SIGNED_PENDING_KEY);
    if (!raw) return null;
    value = JSON.parse(raw);
  } catch {
    clearPersistedSignedReview();
    return null;
  }
  if (!isValidPendingReview(value)) {
    clearPersistedSignedReview();
    return null;
  }
  if (value.ownerKey !== ownerKey || value.selectionHash !== selectionHash) {
    clearPersistedSignedReview();
    return null;
  }
  return {
    payload: value.payload,
    proposalId: value.proposalId,
    mutationSelection,
    recovery: (payload, id) => verifySignedReviewReceipt(client, payload, id),
    checkOnly: true,
    ownerKey: value.ownerKey,
    selectionHash: value.selectionHash
  };
}

function isValidPendingReview(value) {
  return value?.version === SIGNED_PENDING_VERSION
    && /^(?:bff|oidc):oidc:[a-f0-9]{64}$/.test(value.ownerKey || "")
    && /^[a-f0-9]{64}$/.test(value.selectionHash || "")
    && typeof value.proposalId === "string" && Boolean(value.proposalId.trim())
    && typeof value.payload?.operationId === "string" && Boolean(value.payload.operationId.trim())
    && typeof value.payload?.approvalId === "string" && Boolean(value.payload.approvalId.trim())
    && ["APPROVED", "REJECTED"].includes(value.payload.outcome)
    && /^[a-f0-9]{64}$/.test(value.payload.rationaleSha256 || "")
    && Object.keys(value).every((key) => ["version", "ownerKey", "selectionHash", "payload", "proposalId"].includes(key))
    && Object.keys(value.payload).every((key) => ["operationId", "approvalId", "outcome", "rationaleSha256"].includes(key));
}

function clearPersistedSignedReview() {
  try { sessionStorage.removeItem(SIGNED_PENDING_KEY); } catch { /* Browser storage is optional. */ }
}

function forgetSignedReview(operationId, ownerKey) {
  try {
    const value = JSON.parse(sessionStorage.getItem(SIGNED_PENDING_KEY) || "null");
    if (value?.payload?.operationId === operationId && value?.ownerKey === ownerKey) {
      sessionStorage.removeItem(SIGNED_PENDING_KEY);
    }
  } catch { /* Browser storage is optional. */ }
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
