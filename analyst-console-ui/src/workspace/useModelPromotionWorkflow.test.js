import { act, renderHook, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useModelPromotionWorkflow } from "./useModelPromotionWorkflow.js";
import { WorkspaceRuntimeProvider } from "./WorkspaceRuntimeProvider.jsx";
import { confirmPolicyAssertion } from "../governance/policyWebAuthn.js";

vi.mock("../governance/policyWebAuthn.js", () => ({ confirmPolicyAssertion: vi.fn() }));
beforeEach(() => window.sessionStorage.clear());
afterEach(() => vi.unstubAllGlobals());

describe("useModelPromotionWorkflow", () => {
  it("keeps a newer selection when older state and assessment responses complete later", async () => {
    const firstState = deferred();
    const firstAssessment = deferred();
    const apiClient = {
      getModelPromotionState: vi.fn().mockReturnValueOnce(firstState.promise).mockResolvedValue(state("v2")),
      getModelPromotionAssessment: vi.fn().mockReturnValueOnce(firstAssessment.promise).mockResolvedValue(assessment("v2"))
    };
    const { result } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }));

    act(() => { void result.current.load(selection("v1")); });
    await waitFor(() => expect(apiClient.getModelPromotionState).toHaveBeenCalledTimes(1));
    act(() => { void result.current.load(selection("v2")); });
    await waitFor(() => expect(result.current.state?.artifactIdentity.modelVersion).toBe("v2"));

    firstState.resolve(state("v1"));
    firstAssessment.resolve(assessment("v1"));
    await waitFor(() => expect(result.current.assessment?.artifactIdentity.modelVersion).toBe("v2"));
    expect(result.current.state.artifactIdentity.modelVersion).toBe("v2");
  });

  it("does not fetch sensitive governance state when disabled", async () => {
    const apiClient = { getModelPromotionState: vi.fn(), getModelPromotionAssessment: vi.fn() };
    const { result } = renderHook(() => useModelPromotionWorkflow({ enabled: false, apiClient }));

    await act(async () => { await result.current.load(selection("v1")); });
    expect(apiClient.getModelPromotionState).not.toHaveBeenCalled();
    expect(apiClient.getModelPromotionAssessment).not.toHaveBeenCalled();
  });

  it("retries an uncertain proposal with its original operation identity and rationale", async () => {
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      proposeModelPromotionTransition: vi.fn()
        .mockRejectedValueOnce({ status: 503, message: "service unavailable" })
        .mockResolvedValueOnce({ reviewStatus: "PENDING" })
    };
    const { result } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }));

    await act(async () => { await result.current.load(selection("v1")); });
    await act(async () => { await result.current.propose("CHALLENGER", "evidence reviewed by analyst"); });

    const originalCommand = apiClient.proposeModelPromotionTransition.mock.calls[0][0];
    expect(originalCommand.rationale).toBe("evidence reviewed by analyst");
    expect(originalCommand.rationaleSha256).toMatch(/^[0-9a-f]{64}$/);
    expect(result.current.hasRetryableMutation).toBe(true);

    await act(async () => { await result.current.retryLastMutation(); });

    expect(apiClient.proposeModelPromotionTransition).toHaveBeenCalledTimes(2);
    expect(apiClient.proposeModelPromotionTransition.mock.calls[1][0]).toEqual(originalCommand);
    expect(result.current.hasRetryableMutation).toBe(false);
  });

  it("keeps conflict feedback after refreshing authoritative state", async () => {
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      proposeModelPromotionTransition: vi.fn().mockRejectedValue({ status: 409, message: "revision changed" })
    };
    const { result } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }));

    await act(async () => { await result.current.load(selection("v1")); });
    await act(async () => { await result.current.propose("CHALLENGER", "evidence reviewed by analyst"); });

    expect(apiClient.getModelPromotionState).toHaveBeenCalledTimes(2);
    expect(result.current.mutationError).toMatchObject({ status: 409, message: "revision changed" });
    expect(result.current.hasRetryableMutation).toBe(false);
  });

  it("resolves a lost production review response from the durable receipt without repeating WebAuthn", async () => {
    vi.mocked(confirmPolicyAssertion).mockResolvedValue("signed-response");
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      startSignedModelPromotionReview: vi.fn().mockResolvedValue({ challengeId: "model-promotion-review:review-1", optionsJson: "{}" }),
      finishSignedModelPromotionReview: vi.fn().mockRejectedValue({ status: 503 }),
      getSignedModelPromotionReviewOperation: vi.fn().mockImplementation((id, operationId) =>
        Promise.resolve({ proposalId: id, operationId, approvalId: command.approvalId,
          outcome: command.outcome, rationaleSha256: command.rationaleSha256 })),
      reviewModelPromotionProposal: vi.fn()
    };
    let command;
    const { result } = renderTrustedHook(apiClient);
    await act(async () => { await result.current.load({ ...selection("v1"), environment: "PRODUCTION" }); });
    apiClient.startSignedModelPromotionReview.mockImplementation((id, payload) => {
      command = payload;
      return Promise.resolve({ challengeId: "model-promotion-review:review-1", optionsJson: "{}" });
    });
    await act(async () => { await result.current.review("proposal-1", "APPROVED", "Reviewed exact evidence"); });
    expect(result.current.hasRetryableMutation).toBe(false);
    expect(apiClient.startSignedModelPromotionReview).toHaveBeenCalledTimes(1);
    expect(apiClient.finishSignedModelPromotionReview).toHaveBeenCalledTimes(1);
    expect(apiClient.getSignedModelPromotionReviewOperation).toHaveBeenCalledWith(
      "proposal-1", command.operationId);
    expect(apiClient.reviewModelPromotionProposal).not.toHaveBeenCalled();
  });

  it("holds an offline signed review for read-only receipt checks without storing its assertion", async () => {
    vi.mocked(confirmPolicyAssertion).mockResolvedValue("signed-response");
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      startSignedModelPromotionReview: vi.fn().mockResolvedValue({ challengeId: "model-promotion-review:review-2", optionsJson: "{}" }),
      finishSignedModelPromotionReview: vi.fn().mockRejectedValue(new Error("offline")),
      getSignedModelPromotionReviewOperation: vi.fn().mockRejectedValue({ status: 404 })
    };
    const { result } = renderTrustedHook(apiClient);
    await act(async () => { await result.current.load({ ...selection("v1"), environment: "PRODUCTION" }); });
    await act(async () => { await result.current.review("proposal-1", "APPROVED", "Reviewed exact evidence"); });
    expect(result.current.hasRetryableMutation).toBe(true);
    expect(result.current.mutationError.message).toMatch(/Outcome uncertain/);
    const pendingRecord = JSON.parse(window.sessionStorage.getItem("model-promotion-pending-signed-review"));
    expect(Object.keys(pendingRecord).sort()).toEqual(["ownerKey", "payload", "proposalId", "selectionHash", "version"]);
    expect(Object.keys(pendingRecord.payload).sort()).toEqual(["approvalId", "operationId", "outcome", "rationaleSha256"]);
    expect(pendingRecord).not.toHaveProperty("mutationSelection");
    expect(JSON.stringify(pendingRecord)).not.toMatch(/issuer|subject|token|challenge|responseJson|assertion/i);
    await act(async () => { await result.current.retryLastMutation(); });
    expect(apiClient.getSignedModelPromotionReviewOperation).toHaveBeenCalledTimes(2);
    expect(apiClient.startSignedModelPromotionReview).toHaveBeenCalledTimes(1);
    expect(apiClient.finishSignedModelPromotionReview).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(window.localStorage)).not.toContain("signed-response");
    expect(JSON.stringify(window.sessionStorage)).not.toContain("signed-response");
    act(() => result.current.clear());
    await act(async () => { await result.current.load({ ...selection("v2"), environment: "PRODUCTION" }); });
    await act(async () => { await result.current.review("proposal-2", "APPROVED", "Different scope"); });
    expect(apiClient.startSignedModelPromotionReview).toHaveBeenCalledTimes(2);
    expect(result.current.hasRetryableMutation).toBe(true);
    expect(window.sessionStorage.getItem("model-promotion-pending-signed-review"))
      .toContain("proposal-2");
    expect(window.sessionStorage.getItem("model-promotion-pending-signed-review"))
      .not.toContain("signed-response");
  });

  it("restores only operation metadata after a remount and checks the original receipt", async () => {
    const mutationSelection = { ...selection("v1"), environment: "PRODUCTION" };
    const pending = {
      version: 1,
      ownerKey: trustedOwnerKey(),
      selectionHash: await hashSelection(mutationSelection),
      payload: { operationId: "review-op", approvalId: "approval-1",
        outcome: "APPROVED", rationaleSha256: "a".repeat(64) },
      proposalId: "proposal-1"
    };
    window.sessionStorage.setItem("model-promotion-pending-signed-review", JSON.stringify(pending));
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      getSignedModelPromotionReviewOperation: vi.fn().mockResolvedValue({
        proposalId: "proposal-1", ...pending.payload }),
      startSignedModelPromotionReview: vi.fn(), finishSignedModelPromotionReview: vi.fn()
    };
    const { result } = renderTrustedHook(apiClient);
    await act(async () => { await result.current.load(mutationSelection); });
    expect(result.current.hasRetryableMutation).toBe(true);
    await act(async () => { await result.current.retryLastMutation(); });
    expect(apiClient.getSignedModelPromotionReviewOperation).toHaveBeenCalledWith("proposal-1", "review-op");
    expect(apiClient.startSignedModelPromotionReview).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull();
  });

  it("discards a stored receipt when the authenticated issuer or provider changes", async () => {
    const mutationSelection = { ...selection("v1"), environment: "PRODUCTION" };
    window.sessionStorage.setItem("model-promotion-pending-signed-review", JSON.stringify(
      await pendingRecord(mutationSelection)));
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      getSignedModelPromotionReviewOperation: vi.fn(),
      startSignedModelPromotionReview: vi.fn(), finishSignedModelPromotionReview: vi.fn()
    };
    let runtimeSession = trustedSession();
    let runtimeProvider = { kind: "bff" };
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: runtimeSession, authProvider: runtimeProvider,
      sessionState: { status: "authenticated" }, enabled: true
    }, children);
    const { result, rerender } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }), { wrapper });
    await act(async () => { await result.current.load(mutationSelection); });
    expect(result.current.hasRetryableMutation).toBe(true);

    runtimeSession = { ...trustedSession(), userId: "same-display-user", issuer: "https://other-issuer", subject: "same-subject" };
    runtimeProvider = { kind: "oidc" };
    rerender();
    await waitFor(() => expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull());
    expect(result.current.hasRetryableMutation).toBe(false);
    await act(async () => { await result.current.load(mutationSelection); });
    expect(apiClient.getSignedModelPromotionReviewOperation).not.toHaveBeenCalled();
  });

  it("clears owner A pending work on logout before owner B signs in with the same user label", async () => {
    const mutationSelection = { ...selection("v1"), environment: "PRODUCTION" };
    window.sessionStorage.setItem("model-promotion-pending-signed-review", JSON.stringify(
      await pendingRecord(mutationSelection)));
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      getSignedModelPromotionReviewOperation: vi.fn()
    };
    let runtimeSession = trustedSession();
    let status = "authenticated";
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: runtimeSession, authProvider: { kind: "bff" },
      sessionState: { status }, enabled: true
    }, children);
    const { result, rerender } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }), { wrapper });
    await act(async () => { await result.current.load(mutationSelection); });
    expect(result.current.hasRetryableMutation).toBe(true);

    runtimeSession = { userId: "", actorId: "", roles: [], authorities: [] };
    status = "unauthenticated";
    rerender();
    await waitFor(() => expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull());

    runtimeSession = { ...trustedSession(), actorId: `oidc:${"c".repeat(64)}` };
    status = "authenticated";
    rerender();
    await act(async () => { await result.current.load(mutationSelection); });
    expect(result.current.hasRetryableMutation).toBe(false);
    expect(apiClient.getSignedModelPromotionReviewOperation).not.toHaveBeenCalled();
  });

  it("discards a pending receipt when the same OIDC subject is returned by another issuer", async () => {
    const mutationSelection = { ...selection("v1"), environment: "PRODUCTION" };
    const issuer = "https://issuer-a.bank.example/realms/fraud";
    const ownerDigest = await hashText(`${issuer}#same-subject`);
    window.sessionStorage.setItem("model-promotion-pending-signed-review", JSON.stringify(
      await pendingRecord(mutationSelection, `oidc:oidc:${ownerDigest}`)));
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      getSignedModelPromotionReviewOperation: vi.fn()
    };
    let runtimeSession = {
      userId: "same-display-user", issuer, subject: "same-subject", roles: [], authorities: []
    };
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: runtimeSession, authProvider: { kind: "oidc" },
      sessionState: { status: "authenticated" }, enabled: true
    }, children);
    const { result, rerender } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }), { wrapper });
    await act(async () => { await result.current.load(mutationSelection); });
    expect(result.current.hasRetryableMutation).toBe(true);

    runtimeSession = { ...runtimeSession, issuer: "https://issuer-b.bank.example/realms/fraud" };
    rerender();
    await waitFor(() => expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull());
    expect(result.current.hasRetryableMutation).toBe(false);
    await act(async () => { await result.current.load(mutationSelection); });
    expect(apiClient.getSignedModelPromotionReviewOperation).not.toHaveBeenCalled();
  });

  it("clears stored signed review metadata on expiry and corrupt storage", async () => {
    const mutationSelection = { ...selection("v1"), environment: "PRODUCTION" };
    window.sessionStorage.setItem("model-promotion-pending-signed-review", JSON.stringify(
      await pendingRecord(mutationSelection)));
    const apiClient = { getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")) };
    let currentStatus = "authenticated";
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: trustedSession(), authProvider: { kind: "bff" },
      sessionState: { status: currentStatus }, enabled: true
    }, children);
    const { result, rerender } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }), { wrapper });
    await act(async () => { await result.current.load(mutationSelection); });
    currentStatus = "expired";
    rerender();
    await waitFor(() => expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull());

    window.sessionStorage.setItem("model-promotion-pending-signed-review", "not-json");
    currentStatus = "authenticated";
    rerender();
    await act(async () => { await result.current.load(mutationSelection); });
    expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull();
  });

  it("clears pending signed review metadata when the workspace becomes disabled", async () => {
    const mutationSelection = { ...selection("v1"), environment: "PRODUCTION" };
    window.sessionStorage.setItem("model-promotion-pending-signed-review", JSON.stringify(
      await pendingRecord(mutationSelection)));
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1"))
    };
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: trustedSession(), authProvider: { kind: "bff" },
      sessionState: { status: "authenticated" }, enabled: true
    }, children);
    const { result, rerender } = renderHook(({ enabled }) => useModelPromotionWorkflow({ enabled, apiClient }), {
      initialProps: { enabled: true }, wrapper
    });
    await act(async () => { await result.current.load(mutationSelection); });
    expect(result.current.hasRetryableMutation).toBe(true);
    rerender({ enabled: false });
    await waitFor(() => expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull());
    expect(result.current.hasRetryableMutation).toBe(false);
  });

  it("keeps receipt-only recovery in memory when browser storage is unavailable", async () => {
    vi.mocked(confirmPolicyAssertion).mockResolvedValue("signed-response");
    vi.stubGlobal("sessionStorage", {
      getItem: vi.fn().mockReturnValue(null),
      setItem: vi.fn(() => { throw new DOMException("Storage disabled", "SecurityError"); }),
      removeItem: vi.fn()
    });
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      startSignedModelPromotionReview: vi.fn().mockResolvedValue({ challengeId: "challenge", optionsJson: "{}" }),
      finishSignedModelPromotionReview: vi.fn().mockRejectedValue(new Error("offline")),
      getSignedModelPromotionReviewOperation: vi.fn().mockRejectedValue({ status: 404 })
    };
    const { result } = renderTrustedHook(apiClient);
    await act(async () => { await result.current.load({ ...selection("v1"), environment: "PRODUCTION" }); });
    await act(async () => { await result.current.review("proposal-1", "APPROVED", "Reviewed exact evidence"); });
    expect(result.current.hasRetryableMutation).toBe(true);
    expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull();
    await act(async () => { await result.current.retryLastMutation(); });
    expect(apiClient.getSignedModelPromotionReviewOperation).toHaveBeenCalledTimes(2);
    expect(apiClient.startSignedModelPromotionReview).toHaveBeenCalledTimes(1);
  });

  it("does not finish or persist a signed review after the owner changes in flight", async () => {
    vi.mocked(confirmPolicyAssertion).mockResolvedValue("signed-response");
    const assertion = deferred();
    vi.mocked(confirmPolicyAssertion).mockReturnValue(assertion.promise);
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      startSignedModelPromotionReview: vi.fn().mockResolvedValue({ challengeId: "challenge", optionsJson: "{}" }),
      finishSignedModelPromotionReview: vi.fn(),
      getSignedModelPromotionReviewOperation: vi.fn()
    };
    let runtimeSession = trustedSession();
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: runtimeSession, authProvider: { kind: "bff" },
      sessionState: { status: "authenticated" }, enabled: true
    }, children);
    const { result, rerender } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }), { wrapper });
    await act(async () => { await result.current.load({ ...selection("v1"), environment: "PRODUCTION" }); });
    let reviewPromise;
    act(() => { reviewPromise = result.current.review("proposal-1", "APPROVED", "Reviewed exact evidence"); });
    await waitFor(() => expect(confirmPolicyAssertion).toHaveBeenCalled());
    runtimeSession = { ...trustedSession(), actorId: `oidc:${"c".repeat(64)}` };
    rerender();
    assertion.resolve("signed-response");
    await act(async () => { await reviewPromise; });
    expect(apiClient.finishSignedModelPromotionReview).not.toHaveBeenCalled();
    expect(apiClient.getSignedModelPromotionReviewOperation).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull();
  });

  it("ignores a receipt response that returns after the authenticated owner changes", async () => {
    const mutationSelection = { ...selection("v1"), environment: "PRODUCTION" };
    const receipt = deferred();
    window.sessionStorage.setItem("model-promotion-pending-signed-review", JSON.stringify(
      await pendingRecord(mutationSelection)));
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      getSignedModelPromotionReviewOperation: vi.fn().mockReturnValue(receipt.promise)
    };
    let runtimeSession = trustedSession();
    const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
      session: runtimeSession, authProvider: { kind: "bff" },
      sessionState: { status: "authenticated" }, enabled: true
    }, children);
    const { result, rerender } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }), { wrapper });
    await act(async () => { await result.current.load(mutationSelection); });
    let retryPromise;
    act(() => { retryPromise = result.current.retryLastMutation(); });
    await waitFor(() => expect(apiClient.getSignedModelPromotionReviewOperation).toHaveBeenCalledTimes(1));

    runtimeSession = { ...trustedSession(), actorId: `oidc:${"c".repeat(64)}` };
    rerender();
    await waitFor(() => expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull());
    receipt.resolve({ proposalId: "proposal-1", operationId: "review-op", approvalId: "approval-1",
      outcome: "APPROVED", rationaleSha256: "a".repeat(64) });
    await act(async () => { await retryPromise; });

    expect(result.current.hasRetryableMutation).toBe(false);
    expect(window.sessionStorage.getItem("model-promotion-pending-signed-review")).toBeNull();
  });
});

function selection(modelVersion) {
  return { modelName: "fraud-model", modelVersion, featureContractVersion: "features-v1", modelArtifactSha256: "a".repeat(64), environment: "TEST", intendedUse: "FRAUD_RISK_SCORING", fromInclusive: "2026-01-01T00:00:00Z", toInclusive: "2026-01-02T00:00:00Z" };
}
function state(modelVersion) {
  return { artifactIdentity: { modelVersion }, lifecycleState: "CANDIDATE", currentRevision: 0, proposals: [] };
}
function assessment(modelVersion) {
  return { artifactIdentity: { modelVersion } };
}
function deferred() {
  let resolve;
  const promise = new Promise((nextResolve) => { resolve = nextResolve; });
  return { promise, resolve };
}

function trustedSession() {
  return { userId: "same-display-user", actorId: `oidc:${"b".repeat(64)}`, roles: [], authorities: [] };
}

function trustedOwnerKey() {
  return `bff:${trustedSession().actorId}`;
}

function renderTrustedHook(apiClient) {
  const wrapper = ({ children }) => createElement(WorkspaceRuntimeProvider, {
    session: trustedSession(), authProvider: { kind: "bff" },
    sessionState: { status: "authenticated" }, enabled: true
  }, children);
  return renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }), { wrapper });
}

async function hashSelection(value) {
  const descriptor = Object.fromEntries([
    "modelName", "modelVersion", "featureContractVersion", "modelArtifactSha256",
    "environment", "intendedUse", "fromInclusive", "toInclusive"
  ].map((field) => [field, value[field]]));
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify(descriptor)));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function pendingRecord(value, ownerKey = trustedOwnerKey()) {
  return {
    version: 1,
    ownerKey,
    selectionHash: await hashSelection(value),
    payload: { operationId: "review-op", approvalId: "approval-1", outcome: "APPROVED", rationaleSha256: "a".repeat(64) },
    proposalId: "proposal-1"
  };
}

async function hashText(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
