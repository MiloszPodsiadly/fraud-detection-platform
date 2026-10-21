import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useModelPromotionWorkflow } from "./useModelPromotionWorkflow.js";
import { confirmPolicyAssertion } from "../governance/policyWebAuthn.js";

vi.mock("../governance/policyWebAuthn.js", () => ({ confirmPolicyAssertion: vi.fn() }));

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

  it("retries a production review with the same WebAuthn assertion after an uncertain response", async () => {
    vi.mocked(confirmPolicyAssertion).mockResolvedValue("signed-response");
    const apiClient = {
      getModelPromotionState: vi.fn().mockResolvedValue(state("v1")),
      getModelPromotionAssessment: vi.fn().mockResolvedValue(assessment("v1")),
      startSignedModelPromotionReview: vi.fn().mockResolvedValue({ challengeId: "model-promotion-review:review-1", optionsJson: "{}" }),
      finishSignedModelPromotionReview: vi.fn().mockRejectedValueOnce({ status: 503 })
        .mockResolvedValueOnce({ reviewStatus: "APPROVED" }),
      reviewModelPromotionProposal: vi.fn()
    };
    const { result } = renderHook(() => useModelPromotionWorkflow({ enabled: true, apiClient }));
    await act(async () => { await result.current.load({ ...selection("v1"), environment: "PRODUCTION" }); });
    await act(async () => { await result.current.review("proposal-1", "APPROVED", "Reviewed exact evidence"); });
    expect(result.current.hasRetryableMutation).toBe(true);
    await act(async () => { await result.current.retryLastMutation(); });
    expect(apiClient.startSignedModelPromotionReview).toHaveBeenCalledTimes(1);
    expect(apiClient.finishSignedModelPromotionReview).toHaveBeenCalledTimes(2);
    expect(apiClient.finishSignedModelPromotionReview.mock.calls[1][1])
      .toEqual(apiClient.finishSignedModelPromotionReview.mock.calls[0][1]);
    expect(apiClient.reviewModelPromotionProposal).not.toHaveBeenCalled();
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
