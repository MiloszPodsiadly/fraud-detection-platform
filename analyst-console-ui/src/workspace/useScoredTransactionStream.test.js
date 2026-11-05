import { act, renderHook, waitFor } from "@testing-library/react";
import { useLayoutEffect } from "react";
import { describe, expect, it, vi } from "vitest";
import { useScoredTransactionStream } from "./useScoredTransactionStream.js";

describe("useScoredTransactionStream ownership", () => {
  it.each(["enabled", "apiClient", "session", "authProvider"])(
    "hides the old page in the first commit when %s changes",
    async (changedField) => {
      const pending = deferred();
      const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(page("old")) };
      const initial = readContext(apiClient);
      const frames = [];
      const { result, rerender } = renderStream(initial, frames);
      await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-old"));
      apiClient.listScoredTransactions.mockReturnValue(pending.promise);
      const next = {
        ...initial,
        [changedField]: {
          enabled: false,
          apiClient: { listScoredTransactions: vi.fn().mockReturnValue(pending.promise) },
          session: { ...initial.session, issuer: "issuer-new" },
          authProvider: { kind: "demo" }
        }[changedField]
      };

      frames.length = 0;
      rerender(next);

      expect(frames.length).toBeGreaterThan(0);
      for (const frame of frames) {
        expect(frame.page.content).toEqual([]);
        expect(frame.page.totalElements).toBe(0);
        expect(frame.error).toBeNull();
      }
      if (changedField === "enabled") {
        expect(result.current.isLoading).toBe(false);
        expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1);
      } else {
        expect(result.current.isLoading).toBe(true);
        expect(next.apiClient.listScoredTransactions).toHaveBeenCalledTimes(changedField === "apiClient" ? 1 : 2);
      }
    }
  );

  it("fences old results, failures and finally even when abort is ignored", async () => {
    const oldRequest = deferred();
    const currentRequest = deferred();
    const oldClient = { listScoredTransactions: vi.fn().mockReturnValue(oldRequest.promise) };
    const newClient = { listScoredTransactions: vi.fn().mockReturnValue(currentRequest.promise) };
    const initial = readContext(oldClient);
    const { result, rerender } = renderStream(initial);
    const oldSignal = oldClient.listScoredTransactions.mock.calls[0][1].signal;

    rerender({ ...initial, apiClient: newClient });
    expect(oldSignal.aborted).toBe(true);
    await act(async () => oldRequest.reject(new Error("old private exception")));
    expect(result.current.isLoading).toBe(true);
    expect(result.current.error).toBeNull();
    await act(async () => currentRequest.resolve(page("new")));
    expect(result.current.page.content[0].transactionId).toBe("txn-new");
  });

  it("binds page, size and filters to each response and refreshes the current request", async () => {
    const second = deferred();
    const third = deferred();
    const apiClient = {
      listScoredTransactions: vi.fn()
        .mockResolvedValueOnce(page("initial"))
        .mockReturnValueOnce(second.promise)
        .mockReturnValueOnce(third.promise)
        .mockResolvedValue(page("refresh", { page: 2, size: 10 }))
    };
    const frames = [];
    const { result } = renderStream(readContext(apiClient), frames);
    await waitFor(() => expect(result.current.page.content).toHaveLength(1));
    const savedRefresh = result.current.refresh;

    frames.length = 0;
    act(() => result.current.setRequest((current) => ({ ...current, query: "merchant-two", page: 1 })));
    expect(frames.every((frame) => frame.page.content.length === 0)).toBe(true);
    act(() => result.current.setRequest((current) => ({ ...current, query: "merchant-three", page: 2, size: 10, status: "SUSPICIOUS" })));
    const currentRequest = result.current.request;
    await act(async () => third.resolve(page("third", { page: 2, size: 10 })));
    await act(async () => second.resolve(page("second", { page: 1 })));
    expect(result.current.page.content[0].transactionId).toBe("txn-third");

    await act(async () => savedRefresh());
    expect(apiClient.listScoredTransactions).toHaveBeenLastCalledWith(currentRequest, expect.anything());
    expect(result.current.page.content[0].transactionId).toBe("txn-refresh");
  });

  it("rejects saved refresh callbacks after owner transitions but keeps fresh callbacks active", async () => {
    const oldClient = { listScoredTransactions: vi.fn().mockResolvedValue(page("old")) };
    const newClient = { listScoredTransactions: vi.fn().mockResolvedValue(page("new")) };
    const initial = readContext(oldClient);
    const { result, rerender, unmount } = renderStream(initial);
    await waitFor(() => expect(result.current.page.content).toHaveLength(1));
    const savedRefresh = result.current.refresh;
    const ownerB = {
      ...initial,
      apiClient: newClient,
      session: { ...initial.session, issuer: "issuer-b", subject: "subject-b", authorities: ["ALERT_READ"] },
      authProvider: { kind: "demo", issuer: "issuer-b" }
    };
    rerender(ownerB);
    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-new"));

    let staleResult;
    await act(async () => { staleResult = await savedRefresh(); });
    expect(staleResult).toBeNull();
    expect(oldClient.listScoredTransactions).toHaveBeenCalledTimes(1);
    expect(newClient.listScoredTransactions).toHaveBeenCalledTimes(1);
    const savedRefreshB = result.current.refresh;
    await act(async () => savedRefreshB());
    expect(newClient.listScoredTransactions).toHaveBeenCalledTimes(2);

    rerender(initial);
    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-old"));
    let staleAReturn;
    await act(async () => { staleAReturn = await savedRefresh(); });
    expect(staleAReturn).toBeNull();
    expect(oldClient.listScoredTransactions).toHaveBeenCalledTimes(2);
    const savedRefreshA2 = result.current.refresh;
    await act(async () => savedRefreshA2());
    expect(oldClient.listScoredTransactions).toHaveBeenCalledTimes(3);

    rerender({ ...ownerB, enabled: false });
    let disabledResult;
    await act(async () => { disabledResult = await savedRefreshA2(); });
    expect(disabledResult).toBeNull();
    expect(oldClient.listScoredTransactions).toHaveBeenCalledTimes(3);
    unmount();
    await savedRefreshA2();
    expect(oldClient.listScoredTransactions).toHaveBeenCalledTimes(3);
  });

  it("does not refetch for ordinary rerenders or equivalent normalized requests", async () => {
    const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(page("stable")) };
    const props = readContext(apiClient);
    const { result, rerender } = renderStream(props);
    await waitFor(() => expect(result.current.page.content).toHaveLength(1));
    rerender({ ...props });
    act(() => result.current.setRequest((current) => ({ ...current, query: "  " })));
    expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1);
  });

  it.each([401, 403, 404, 500, 503, undefined])("clears prior rows and hides exception details for error %s", async (status) => {
    const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(page("old")) };
    const { result } = renderStream(readContext(apiClient));
    await waitFor(() => expect(result.current.page.content).toHaveLength(1));
    apiClient.listScoredTransactions.mockRejectedValue({ status, message: "private issuer exception" });

    await act(async () => result.current.refresh());

    expect(result.current.page.content).toEqual([]);
    expect(result.current.page.totalElements).toBe(0);
    expect(result.current.error).toBeTruthy();
    expect(result.current.error.message).not.toContain("private issuer");
  });

  it.each([null, {}, { content: [null], totalElements: 1, totalPages: 1, page: 0, size: 25 }])(
    "rejects a malformed page safely: %s",
    async (payload) => {
      const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(payload) };
      const { result } = renderStream(readContext(apiClient));
      await waitFor(() => expect(result.current.error).toBeTruthy());
      expect(result.current.page.content).toEqual([]);
      expect(result.current.isLoading).toBe(false);
    }
  );

  it.each(["", "   ", "0x10", "NaN", "Infinity", "1e999", "01.25"])(
    "rejects malformed MoneyResponse decimal %s",
    async (amount) => {
      const payload = page("invalid-amount");
      payload.content[0].transactionAmount = { amount, currency: "USD" };
      const { result } = renderStream(readContext({ listScoredTransactions: vi.fn().mockResolvedValue(payload) }));

      await waitFor(() => expect(result.current.error).toBeTruthy());
      expect(result.current.page.content).toEqual([]);
    }
  );

  it.each(["false", "true", 0, 1, [], {}])("rejects malformed list alertRecommended value %s", async (alertRecommended) => {
    const payload = page("invalid-recommendation");
    payload.content[0].alertRecommended = alertRecommended;
    const { result } = renderStream(readContext({ listScoredTransactions: vi.fn().mockResolvedValue(payload) }));

    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.page.content).toEqual([]);
  });

  it.each([
    [{ amount: 10, currency: "US" }],
    [{ amount: 10, currency: "usd" }],
    [{ amount: 10, currency: "USD " }],
    [{ amount: [], currency: "USD" }],
    [{ amount: 10 }],
    [{ currency: "USD" }],
    [{ amount: null, currency: "US" }],
    [{ amount: null, currency: null }]
  ])("rejects malformed MoneyResponse currency or shape %s", async (transactionAmount) => {
    const payload = page("invalid-currency");
    payload.content[0].transactionAmount = transactionAmount;
    const { result } = renderStream(readContext({ listScoredTransactions: vi.fn().mockResolvedValue(payload) }));

    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.page.content).toEqual([]);
  });

  it.each([
    [{ amount: 0, currency: "USD" }],
    [{ amount: "-12.50", currency: "PLN" }],
    [{ amount: "1e-300", currency: "EUR" }],
    [{ amount: 1e300, currency: "JPY" }],
    [null],
    [undefined]
  ])("accepts valid optional MoneyResponse values %s", async (transactionAmount) => {
    const payload = page("valid-amount");
    payload.content[0].transactionAmount = transactionAmount;
    const { result } = renderStream(readContext({ listScoredTransactions: vi.fn().mockResolvedValue(payload) }));

    await waitFor(() => expect(result.current.page.content).toHaveLength(1));
    expect(result.current.error).toBeNull();
  });

  it("does not show an old owner's rows when the new owner's amount contract is malformed", async () => {
    const oldClient = { listScoredTransactions: vi.fn().mockResolvedValue(page("owner-a")) };
    const malformedPage = page("owner-b");
    malformedPage.content[0].transactionAmount = { amount: "", currency: "USD" };
    const newClient = { listScoredTransactions: vi.fn().mockResolvedValue(malformedPage) };
    const initial = readContext(oldClient);
    const frames = [];
    const { result, rerender } = renderStream(initial, frames);
    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-owner-a"));

    frames.length = 0;
    rerender({ ...initial, apiClient: newClient, session: { ...initial.session, issuer: "issuer-b" } });
    expect(frames.length).toBeGreaterThan(0);
    for (const frame of frames) {
      expect(frame.page.content).toEqual([]);
    }
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.page.content).toEqual([]);
    expect(result.current.error.message).not.toContain("owner-b");
  });

  it("does not fetch without a client and aborts outstanding requests on unmount", async () => {
    const missing = renderStream(readContext(null));
    expect(missing.result.current.page.content).toEqual([]);
    expect(missing.result.current.isLoading).toBe(false);
    missing.unmount();

    const pending = deferred();
    const apiClient = { listScoredTransactions: vi.fn().mockReturnValue(pending.promise) };
    const { result, unmount } = renderStream(readContext(apiClient));
    const signal = apiClient.listScoredTransactions.mock.calls[0][1].signal;
    unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => pending.resolve(page("late")));
    expect(result.current.page.content).toEqual([]);
  });
});

function renderStream(props, frames = []) {
  return renderHook((currentProps) => {
    const stream = useScoredTransactionStream(currentProps);
    useLayoutEffect(() => {
      frames.push({ page: stream.page, error: stream.error, isLoading: stream.isLoading, request: stream.request });
    });
    return stream;
  }, { initialProps: props });
}

function readContext(apiClient) {
  return { enabled: true, apiClient, session: { userId: "analyst", issuer: "issuer-old" }, authProvider: { kind: "demo" } };
}

function page(name, request = {}) {
  return {
    content: [{ transactionId: "txn-" + name, customerId: "customer-" + name, merchantInfo: { merchantName: "Merchant " + name } }],
    totalElements: 73, totalPages: 8, page: 0, size: 25, ...request
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
