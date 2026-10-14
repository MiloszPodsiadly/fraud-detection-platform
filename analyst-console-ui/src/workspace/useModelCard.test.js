import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { modelCard } from "../test-utils/modelCardFixtures.js";
import { useModelCard } from "./useModelCard.js";

describe("useModelCard", () => {
  it("loads the current card with an abort signal", async () => {
    const apiClient = client();
    const { result } = renderHook(() => useModelCard({ enabled: true, apiClient }));

    await waitFor(() => expect(result.current.card?.cardType).toBe("ML_MODEL_CARD_V1"));
    expect(apiClient.getCurrentModelCard).toHaveBeenCalledWith({ signal: expect.any(AbortSignal) });
  });

  it("clears card state when permission is removed", async () => {
    const apiClient = client();
    const { result, rerender } = renderHook(({ enabled }) => useModelCard({ enabled, apiClient }), {
      initialProps: { enabled: true }
    });
    await waitFor(() => expect(result.current.card).not.toBeNull());

    rerender({ enabled: false });

    await waitFor(() => expect(result.current.card).toBeNull());
    expect(result.current.error).toBeNull();
  });

  it("aborts and ignores a stale request", async () => {
    const first = deferred();
    const apiClient = client();
    apiClient.getCurrentModelCard.mockReturnValueOnce(first.promise).mockResolvedValue(modelCard());
    const { result } = renderHook(() => useModelCard({ enabled: true, apiClient }));
    await waitFor(() => expect(apiClient.getCurrentModelCard).toHaveBeenCalledTimes(1));
    const firstSignal = apiClient.getCurrentModelCard.mock.calls[0][0].signal;

    act(() => { result.current.refresh(); });
    await waitFor(() => expect(apiClient.getCurrentModelCard).toHaveBeenCalledTimes(2));
    expect(firstSignal.aborted).toBe(true);
    await waitFor(() => expect(result.current.card).not.toBeNull());
    await act(async () => { first.resolve({ ...modelCard(), cardType: "STALE" }); });
    expect(result.current.card.cardType).toBe("ML_MODEL_CARD_V1");
  });

  it.each([401, 403, 404, 503])("keeps HTTP %s as an explicit error", async (status) => {
    const apiClient = client();
    apiClient.getCurrentModelCard.mockRejectedValue({ status });
    const { result } = renderHook(() => useModelCard({ enabled: true, apiClient }));
    await waitFor(() => expect(result.current.error).toEqual({ status }));
    expect(result.current.card).toBeNull();
  });

  it("maps unsupported contracts to a safe state", async () => {
    const apiClient = client();
    apiClient.getCurrentModelCard.mockResolvedValue({ state: "invalid-response" });
    const { result } = renderHook(() => useModelCard({ enabled: true, apiClient }));
    await waitFor(() => expect(result.current.error).toEqual({ state: "invalid-response" }));
  });
});

function client() {
  return { getCurrentModelCard: vi.fn().mockResolvedValue(modelCard()) };
}

function deferred() {
  let resolve;
  const promise = new Promise((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}
