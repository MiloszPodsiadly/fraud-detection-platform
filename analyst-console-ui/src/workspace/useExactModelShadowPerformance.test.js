import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useExactModelShadowPerformance } from "./useExactModelShadowPerformance.js";

describe("useExactModelShadowPerformance", () => {
  it("aborts and ignores a stale exact-artifact request", async () => {
    const first = deferred();
    const current = { schemaVersion: "current" };
    const apiClient = { getExactModelShadowPerformance: vi.fn()
      .mockReturnValueOnce(first.promise).mockResolvedValueOnce(current) };
    const { result } = renderHook(() => useExactModelShadowPerformance({ enabled: true, apiClient }));

    act(() => { result.current.load(selector("a")); });
    await waitFor(() => expect(apiClient.getExactModelShadowPerformance).toHaveBeenCalledTimes(1));
    const firstSignal = apiClient.getExactModelShadowPerformance.mock.calls[0][1].signal;
    act(() => { result.current.load(selector("b")); });
    await waitFor(() => expect(result.current.summary).toBe(current));

    expect(firstSignal.aborted).toBe(true);
    await act(async () => { first.resolve({ schemaVersion: "stale" }); });
    expect(result.current.summary).toBe(current);
  });

  it("clears selected evidence when authority is removed", async () => {
    const apiClient = { getExactModelShadowPerformance: vi.fn().mockResolvedValue({ schemaVersion: "current" }) };
    const { result, rerender } = renderHook(
      ({ enabled }) => useExactModelShadowPerformance({ enabled, apiClient }),
      { initialProps: { enabled: true } }
    );
    act(() => { result.current.load(selector("a")); });
    await waitFor(() => expect(result.current.summary).not.toBeNull());

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.summary).toBeNull());
    expect(result.current.selector).toBeNull();
  });
});

function selector(suffix) {
  return {
    modelName: "model", modelVersion: "version", featureContractVersion: "feature-v2",
    modelArtifactSha256: suffix.repeat(64), fromInclusive: "", toInclusive: ""
  };
}

function deferred() {
  let resolve;
  const promise = new Promise((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}
