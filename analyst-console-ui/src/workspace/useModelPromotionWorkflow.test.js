import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useModelPromotionWorkflow } from "./useModelPromotionWorkflow.js";

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
});

function selection(modelVersion) {
  return { modelName: "fraud-model", modelVersion, featureContractVersion: "features-v1", modelArtifactSha256: "a".repeat(64), environment: "TEST", intendedUse: "FRAUD_RISK_SCORING", fromInclusive: "2026-01-01T00:00:00Z", toInclusive: "2026-01-02T00:00:00Z" };
}
function state(modelVersion) {
  return { artifactIdentity: { modelVersion }, proposals: [] };
}
function assessment(modelVersion) {
  return { artifactIdentity: { modelVersion } };
}
function deferred() {
  let resolve;
  const promise = new Promise((nextResolve) => { resolve = nextResolve; });
  return { promise, resolve };
}
