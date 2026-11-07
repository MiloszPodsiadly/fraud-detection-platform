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

  it("recovers an out-of-range page once while preserving the current backend filters and size", async () => {
    const requests = [];
    const apiClient = {
      listScoredTransactions: vi.fn((request) => {
        requests.push({ ...request });
        if (requests.length === 1) {
          return Promise.resolve(scoredResponse({ page: 0, size: 25, totalElements: 125, content: [page("initial").content[0]] }));
        }
        if (requests.length === 2) {
          return Promise.resolve(scoredResponse({ page: 3, size: 10, totalElements: 4, content: [] }));
        }
        return Promise.resolve(scoredResponse({ page: 0, size: 10, totalElements: 4, content: [page("recovered").content[0]] }));
      })
    };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-initial"));
    act(() => result.current.setRequest({
      page: 3,
      size: 10,
      query: "merchant query",
      riskLevel: "HIGH",
      status: "SUSPICIOUS"
    }));

    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-recovered"));
    expect(requests).toEqual([
      { page: 0, size: 25, query: "", riskLevel: "ALL", status: "ALL" },
      { page: 3, size: 10, query: "merchant query", riskLevel: "HIGH", status: "SUSPICIOUS" },
      { page: 0, size: 10, query: "merchant query", riskLevel: "HIGH", status: "SUSPICIOUS" }
    ]);
    expect(result.current.request).toMatchObject({
      page: 0,
      size: 10,
      query: "merchant query",
      riskLevel: "HIGH",
      status: "SUSPICIOUS"
    });
  });

  it.each([
    ["inconsistent total page count", { page: 0, size: 25, totalElements: 10, totalPages: 0, content: [] }],
    ["rows with zero total", { page: 0, size: 25, totalElements: 0, totalPages: 0, content: [page("contradictory").content[0]] }]
  ])("rejects contradictory page metadata: %s", async (_name, response) => {
    const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(scoredResponse(response)) };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
    expect(result.current.page.content).toEqual([]);
  });

  it("requires the exact-versus-capped total metadata", async () => {
    const response = page("missing-cap-indicator");
    delete response.totalElementsCapped;
    const { result } = renderStream(readContext({ listScoredTransactions: vi.fn().mockResolvedValue(response) }));

    await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
    expect(result.current.page.content).toEqual([]);
  });

  it.each([
    ["canonical UTC timestamp", "2026-11-07T10:00:00Z", true],
    ["fractional Instant timestamp", "2026-11-07T10:00:00.123456789Z", true],
    ["historical explicit offset", "2026-11-07T12:00:00+02:00", true],
    ["missing timezone", "2026-11-07T10:00:00", false],
    ["impossible calendar day", "2026-02-30T10:00:00Z", false],
    ["invalid timezone offset", "2026-11-07T10:00:00+18:01", false],
    ["overflow timestamp", "999999-11-07T10:00:00Z", false],
    ["malformed type", 1_798_000_000_000, false]
  ])("validates scored timestamp %s", async (_caseName, scoredAt, valid) => {
    const response = page("timestamp");
    response.content[0].scoredAt = scoredAt;
    const { result } = renderStream(readContext({ listScoredTransactions: vi.fn().mockResolvedValue(response) }));

    if (valid) {
      await waitFor(() => expect(result.current.page.content).toHaveLength(1));
      expect(result.current.error).toBeNull();
    } else {
      await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
      expect(result.current.page.content).toEqual([]);
    }
  });

  it("recovers a page that shrank to zero rows and does not loop when page zero is empty", async () => {
    const requests = [];
    const apiClient = {
      listScoredTransactions: vi.fn((request) => {
        requests.push({ ...request });
        if (requests.length === 1) {
          return Promise.resolve(scoredResponse({ page: 0, size: 25, totalElements: 125, content: [page("initial").content[0]] }));
        }
        if (requests.length === 2) {
          return Promise.resolve(scoredResponse({ page: request.page, size: request.size, totalElements: 0, content: [] }));
        }
        return Promise.resolve(scoredResponse({ page: 0, size: request.size, totalElements: 0, content: [] }));
      })
    };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.page.content).toHaveLength(1));
    act(() => result.current.setRequest((current) => ({ ...current, page: 1000 })));
    await waitFor(() => expect(result.current.request.page).toBe(0));
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.page).toMatchObject({ content: [], page: 0, totalElements: 0, totalPages: 0 });
    expect(result.current.error).toBeNull();
    expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(3);
    expect(requests.map(({ page: pageNumber }) => pageNumber)).toEqual([0, 1000, 0]);
  });

  it("shows a safe recovery error and allows a later manual retry", async () => {
    const apiClient = {
      listScoredTransactions: vi.fn()
        .mockResolvedValueOnce(scoredResponse({ page: 0, size: 25, totalElements: 125, content: [page("initial").content[0]] }))
        .mockResolvedValueOnce(scoredResponse({ page: 3, size: 25, totalElements: 10, content: [] }))
        .mockRejectedValueOnce(new Error("private issuer and transaction diagnostics"))
        .mockResolvedValueOnce(scoredResponse({ page: 0, size: 25, totalElements: 10, content: [page("retried").content[0]] }))
    };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-initial"));
    act(() => result.current.setRequest((current) => ({ ...current, page: 3 })));
    await waitFor(() => expect(result.current.error?.message).toBe("Scored transactions are temporarily unavailable."));

    expect(result.current.request.page).toBe(0);
    expect(result.current.page.content).toEqual([]);
    expect(result.current.error.message).not.toContain("private issuer");
    expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(3);

    await act(async () => result.current.refresh());
    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-retried"));
    expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(4);
  });

  it("aborts and fences an unresolved page-zero recovery when the owner changes", async () => {
    const pendingRecovery = deferred();
    const oldClient = {
      listScoredTransactions: vi.fn()
        .mockResolvedValueOnce(scoredResponse({ page: 0, size: 25, totalElements: 125, content: [page("owner-a").content[0]] }))
        .mockResolvedValueOnce(scoredResponse({ page: 3, size: 25, totalElements: 10, content: [] }))
        .mockReturnValueOnce(pendingRecovery.promise)
    };
    const newClient = {
      listScoredTransactions: vi.fn().mockResolvedValue(scoredResponse({
        page: 0,
        size: 25,
        totalElements: 1,
        content: [page("owner-b").content[0]]
      }))
    };
    const initial = readContext(oldClient);
    const { result, rerender } = renderStream(initial);

    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-owner-a"));
    act(() => result.current.setRequest((current) => ({ ...current, page: 3 })));
    await waitFor(() => expect(oldClient.listScoredTransactions).toHaveBeenCalledTimes(3));
    const recoverySignal = oldClient.listScoredTransactions.mock.calls[2][1].signal;

    rerender({ ...initial, apiClient: newClient, session: { ...initial.session, issuer: "issuer-b" } });
    expect(recoverySignal.aborted).toBe(true);
    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-owner-b"));

    await act(async () => pendingRecovery.resolve(scoredResponse({
      page: 0,
      size: 25,
      totalElements: 1,
      content: [page("owner-a-late").content[0]]
    })));
    expect(result.current.page.content[0]?.transactionId).toBe("txn-owner-b");
    expect(newClient.listScoredTransactions).toHaveBeenCalledTimes(1);
  });

  it("accepts consistent capped filtered totals from the backend", async () => {
    const response = scoredResponse({
      page: 399,
      size: 25,
      totalElements: 10_000,
      totalPages: 400,
      totalElementsCapped: true,
      content: []
    });
    const apiClient = {
      listScoredTransactions: vi.fn()
        .mockResolvedValueOnce(scoredResponse({ page: 0, size: 25, totalElements: 10_000, totalPages: 400 }))
        .mockResolvedValueOnce(response)
    };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1));
    act(() => result.current.setRequest({ page: 399, size: 25, query: "merchant", riskLevel: "ALL", status: "ALL" }));
    await waitFor(() => expect(result.current.page.page).toBe(399));
    expect(result.current.error).toBeNull();
    expect(result.current.page.totalElements).toBe(10_000);
  });

  it("recovers an out-of-window capped page once and returns to page zero", async () => {
    const apiClient = {
      listScoredTransactions: vi.fn((request) => Promise.resolve(request.page === 400
        ? scoredResponse({ page: 400, size: 25, totalElements: 10_000, totalPages: 400, totalElementsCapped: true })
        : request.query
          ? scoredResponse({ page: 0, size: request.size, totalElements: 10_000, totalPages: 400, totalElementsCapped: true })
          : scoredResponse({ page: 0, size: request.size, totalElements: 0, totalPages: 0 })))
    };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1));
    act(() => result.current.setRequest({ page: 400, size: 25, query: "merchant", riskLevel: "ALL", status: "ALL" }));
    await waitFor(() => expect(result.current.page.page).toBe(0));

    expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(3);
    expect(apiClient.listScoredTransactions.mock.calls.map(([request]) => request.page)).toEqual([0, 400, 0]);
  });

  it("fails closed when rows or malformed cap metadata escape the filtered window", async () => {
    const malformedResponses = [
      [400, scoredResponse({ page: 400, size: 25, totalElements: 10_000, totalPages: 400, totalElementsCapped: true,
        content: [page("out-of-window").content[0]] })],
      [0, scoredResponse({ totalElements: 9_999, totalElementsCapped: "true" })],
      [0, scoredResponse({ totalElements: 9_999, totalElementsCapped: true })]
    ];

    for (const [requestedPage, payload] of malformedResponses) {
      const apiClient = { listScoredTransactions: vi.fn((request) => Promise.resolve(request.query
        ? payload
        : scoredResponse({ page: 0, size: 25, totalElements: 0, totalPages: 0 }))) };
      const { result } = renderStream(readContext(apiClient));
      await waitFor(() => expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1));
      act(() => result.current.setRequest({ page: requestedPage, size: 25, query: "merchant", riskLevel: "ALL", status: "ALL" }));
      await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
      expect(result.current.page.content).toEqual([]);
    }
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

  it.each([
    { attributes: { privateMetadata: "sentinel" } },
    { cardPresent: true },
    { arbitraryExtension: "sentinel" }
  ])("rejects scored merchant payloads outside the display allowlist: %s", async (merchantInfo) => {
    const response = page("merchant");
    response.content[0].merchantInfo = { merchantName: "Market", ...merchantInfo };
    const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(response) };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
    expect(result.current.page.content).toEqual([]);
  });

  it.each(["A".repeat(64), "a".repeat(63), "raw-fingerprint", {}])(
    "rejects invalid list scoring occurrence tokens %#",
    async (scoringOccurrenceToken) => {
      const response = page("invalid-occurrence");
      response.content[0].scoringOccurrenceToken = scoringOccurrenceToken;
      const { result } = renderStream(readContext({ listScoredTransactions: vi.fn().mockResolvedValue(response) }));

      await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
      expect(result.current.page.content).toEqual([]);
    }
  );

  it.each([
    ["below range", -0.01],
    ["above range", 1.01],
    ["outside score domain", 2.5],
    ["NaN", Number.NaN],
    ["positive infinity", Number.POSITIVE_INFINITY],
    ["negative infinity", Number.NEGATIVE_INFINITY],
    ["numeric string", "0.91"],
    ["NaN string", "NaN"],
    ["object", {}],
    ["array", []],
    ["boolean", true]
  ])("rejects malformed scored fraudScore: %s", async (_caseName, fraudScore) => {
    const response = page("invalid-score");
    response.content[0].fraudScore = fraudScore;
    const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(response) };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
    expect(result.current.page.content).toEqual([]);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.page.content.some((row) => row.fraudScore === fraudScore)).toBe(false);
  });

  it.each([0, 1, 0.91, null, undefined])("accepts valid optional scored fraudScore %s", async (fraudScore) => {
    const response = page("valid-score");
    if (fraudScore === undefined) {
      delete response.content[0].fraudScore;
    } else {
      response.content[0].fraudScore = fraudScore;
    }
    const apiClient = { listScoredTransactions: vi.fn().mockResolvedValue(response) };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.page.content).toHaveLength(1));
    expect(result.current.error).toBeNull();
    expect(result.current.page.content[0].fraudScore).toBe(fraudScore);
  });

  it("clears visible rows when a refresh returns an invalid fraudScore", async () => {
    const invalidResponse = page("invalid-after-refresh");
    invalidResponse.content[0].fraudScore = 1.01;
    const apiClient = {
      listScoredTransactions: vi.fn()
        .mockResolvedValueOnce(page("valid-before-refresh"))
        .mockResolvedValueOnce(invalidResponse)
    };
    const { result } = renderStream(readContext(apiClient));

    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-valid-before-refresh"));
    await act(async () => result.current.refresh());

    expect(result.current.page.content).toEqual([]);
    expect(result.current.error?.message).toBe("The scored transaction response is malformed.");
    expect(result.current.isLoading).toBe(false);
  });

  it("does not show old rows or a malformed score after an owner change", async () => {
    const oldClient = { listScoredTransactions: vi.fn().mockResolvedValue(page("owner-a")) };
    const malformedPage = page("owner-b");
    malformedPage.content[0].fraudScore = 2.5;
    const newClient = { listScoredTransactions: vi.fn().mockResolvedValue(malformedPage) };
    const initial = readContext(oldClient);
    const frames = [];
    const { result, rerender } = renderStream(initial, frames);
    await waitFor(() => expect(result.current.page.content[0]?.transactionId).toBe("txn-owner-a"));

    frames.length = 0;
    rerender({ ...initial, apiClient: newClient, session: { ...initial.session, issuer: "issuer-b" } });
    expect(frames.length).toBeGreaterThan(0);
    for (const frame of frames) expect(frame.page.content).toEqual([]);

    await waitFor(() => expect(result.current.error?.message).toBe("The scored transaction response is malformed."));
    expect(result.current.page.content).toEqual([]);
    expect(JSON.stringify(frames)).not.toContain("txn-owner-a");
    expect(JSON.stringify(frames)).not.toContain("txn-owner-b");
    expect(newClient.listScoredTransactions).toHaveBeenCalledTimes(1);
  });

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
  const response = {
    content: [{ transactionId: "txn-" + name, customerId: "customer-" + name, merchantInfo: { merchantName: "Merchant " + name } }],
    totalElements: 73, page: 0, size: 25, totalElementsCapped: false, ...request
  };
  return { ...response, totalPages: Math.ceil(response.totalElements / response.size) };
}

function scoredResponse({ content = [], page: pageNumber = 0, size = 25, totalElements = 0, totalPages = Math.ceil(totalElements / size), totalElementsCapped = false }) {
  return { content, page: pageNumber, size, totalElements, totalPages, totalElementsCapped };
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
