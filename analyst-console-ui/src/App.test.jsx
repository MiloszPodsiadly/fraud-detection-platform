import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const {
  callbackPath,
  completeLoginCallback,
  providerState,
  refreshSession,
  listAlerts,
  listFraudCaseWorkQueue,
  listGovernanceAdvisories,
  getGovernanceAdvisoryAnalytics,
  getFraudCaseWorkQueueSummary,
  isAbortError,
  listScoredTransactions,
  createAlertsApiClient
} = vi.hoisted(() => {
  const listAlerts = vi.fn();
  const listFraudCaseWorkQueue = vi.fn();
  const listGovernanceAdvisories = vi.fn();
  const getGovernanceAdvisoryAnalytics = vi.fn();
  const getFraudCaseWorkQueueSummary = vi.fn();
  const listScoredTransactions = vi.fn();
  return {
    callbackPath: { value: true },
    completeLoginCallback: vi.fn(),
    providerState: {
      value: {
        kind: "oidc",
        label: "OIDC session",
        supportsSessionEditing: false,
        authenticatedModeLabel: "oidc",
        unauthenticatedModeLabel: "waiting for oidc",
        unauthenticatedDescription: "Use the configured OIDC sign-in flow to start a real provider session.",
        getInitialSession: () => ({ userId: "", roles: [], extraAuthorities: [], authorities: [] }),
        getSessionState: () => ({ status: "unauthenticated" }),
        persistSession: vi.fn(),
        refreshSession: vi.fn(),
        completeLoginCallback: vi.fn(),
        beginLogin: vi.fn(),
        beginLogout: vi.fn(),
        hasLoginConfiguration: () => true,
        getRequestHeaders: () => ({})
      }
    },
    refreshSession: vi.fn(),
    listAlerts,
    listFraudCaseWorkQueue,
    listGovernanceAdvisories,
    getGovernanceAdvisoryAnalytics,
    getFraudCaseWorkQueueSummary,
    isAbortError: (error) => error?.name === "AbortError",
    listScoredTransactions,
    createAlertsApiClient: vi.fn(() => ({
      listAlerts,
      listFraudCaseWorkQueue,
      listGovernanceAdvisories,
      getGovernanceAdvisoryAnalytics,
      getFraudCaseWorkQueueSummary,
      listScoredTransactions,
      getGovernanceAdvisoryAudit: vi.fn(),
      recordGovernanceAdvisoryAudit: vi.fn()
    }))
  };
});

vi.mock("./api/alertsApi.js", () => ({
  createAlertsApiClient,
  listAlerts,
  listFraudCaseWorkQueue,
  listGovernanceAdvisories,
  getGovernanceAdvisoryAnalytics,
  getFraudCaseWorkQueueSummary,
  isAbortError,
  listScoredTransactions,
  getGovernanceAdvisoryAudit: vi.fn(),
  recordGovernanceAdvisoryAudit: vi.fn()
}));

vi.mock("./auth/authProvider.js", () => ({
  getConfiguredAuthProvider: () => providerState.value,
  DEMO_PROVIDER_FALLBACK: {
    kind: "demo",
    label: "Local demo session",
    supportsSessionEditing: true,
    authenticatedModeLabel: "local/dev only",
    unauthenticatedModeLabel: "headers off",
    unauthenticatedDescription: "Demo auth headers are disabled"
  }
}));

vi.mock("./auth/oidcClient.js", () => ({
  isOidcCallbackPath: () => callbackPath.value
}));

import App from "./App.jsx";

describe("App", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState({}, "", "/");
    callbackPath.value = true;
    refreshSession.mockResolvedValue({ userId: "", roles: [], extraAuthorities: [], authorities: [] });
    listFraudCaseWorkQueue.mockResolvedValue({ content: [], size: 20, hasNext: false, nextCursor: null, sort: "createdAt,desc" });
    getFraudCaseWorkQueueSummary.mockResolvedValue(summary(0));
    listGovernanceAdvisories.mockResolvedValue({ status: "AVAILABLE", count: 0, retention_limit: 200, advisory_events: [] });
    getGovernanceAdvisoryAnalytics.mockResolvedValue({
      status: "AVAILABLE",
      window: { from: null, to: null, days: 7 },
      totals: { advisories: 0, reviewed: 0, open: 0 },
      decision_distribution: {},
      lifecycle_distribution: {},
      review_timeliness: { status: "LOW_CONFIDENCE" }
    });
    providerState.value = {
      kind: "oidc",
      label: "OIDC session",
      supportsSessionEditing: false,
      authenticatedModeLabel: "oidc",
      unauthenticatedModeLabel: "waiting for oidc",
      unauthenticatedDescription: "Use the configured OIDC sign-in flow to start a real provider session.",
      getInitialSession: () => ({ userId: "", roles: [], extraAuthorities: [], authorities: [] }),
      getSessionState: () => ({ status: "unauthenticated" }),
      persistSession: vi.fn(),
      refreshSession,
      completeLoginCallback,
      beginLogin: vi.fn(),
      beginLogout: vi.fn(),
      hasLoginConfiguration: () => true,
      getRequestHeaders: () => ({})
    };
  });

  it("handles the dedicated OIDC callback path before loading active workspace and navigation counters", async () => {
    completeLoginCallback.mockImplementation(async () => {
      callbackPath.value = false;
      return {
        userId: "subject-1",
        roles: ["ANALYST"],
        extraAuthorities: [],
        authorities: ["alert:read", "fraud-case:read", "transaction-monitor:read", "assistant-summary:read"]
      };
    });
    listAlerts.mockResolvedValue({ content: [], totalElements: 1, totalPages: 1, page: 0, size: 10 });
    listScoredTransactions.mockResolvedValue({ content: [], totalElements: 3, totalPages: 1, page: 0, size: 25 });

    render(<App />);

    await waitFor(() => expect(completeLoginCallback).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    expect(listAlerts).toHaveBeenCalledWith({ page: 0, size: 1 });
    expect(listFraudCaseWorkQueue).toHaveBeenCalledTimes(1);
    expect(listScoredTransactions).toHaveBeenCalledWith({
      page: 0,
      size: 1,
      query: "",
      riskLevel: "ALL",
      status: "ALL"
    });
  });

  it("does not load dashboard data when oidc bootstrap reports an expired session", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue({
      userId: "",
      roles: [],
      extraAuthorities: [],
      authorities: []
    });
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "expired", expiresAt: "2026-04-24T10:00:00Z" })
    };

    render(<App />);

    await waitFor(() => expect(refreshSession).toHaveBeenCalledTimes(1));
    expect(await screen.findAllByText("The provider session expired or no longer has a usable access token.")).toHaveLength(2);
    expect(listAlerts).not.toHaveBeenCalled();
    expect(listFraudCaseWorkQueue).not.toHaveBeenCalled();
    expect(getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
    expect(listScoredTransactions).not.toHaveBeenCalled();
  });

  it("loads analyst workspace data once after an authenticated oidc bootstrap without flipping the session badge back to loading", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue({
      userId: "subject-1",
      roles: ["READ_ONLY_ANALYST"],
      extraAuthorities: [],
      authorities: ["alert:read", "fraud-case:read", "transaction-monitor:read", "assistant-summary:read"]
    });
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    listAlerts.mockResolvedValue({ content: [], totalElements: 1, totalPages: 1, page: 0, size: 10 });
    listScoredTransactions.mockResolvedValue({ content: [], totalElements: 3, totalPages: 1, page: 0, size: 25 });

    render(<App />);

    await waitFor(() => expect(refreshSession).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    expect(listAlerts).toHaveBeenCalledWith({ page: 0, size: 1 });
    expect(listFraudCaseWorkQueue).toHaveBeenCalledTimes(1);
    expect(listScoredTransactions).toHaveBeenCalledWith({
      page: 0,
      size: 1,
      query: "",
      riskLevel: "ALL",
      status: "ALL"
    });
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    expect(screen.queryByText("Loading session state...")).not.toBeInTheDocument();
  });

  it("recreates the api client when auth material refreshes for the same session identity", async () => {
    callbackPath.value = false;
    const initialSession = authenticatedSession();
    const refreshedSession = authenticatedSession();
    refreshSession.mockResolvedValue(refreshedSession);
    providerState.value = {
      ...providerState.value,
      getInitialSession: () => initialSession,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: vi.fn()
        .mockReturnValueOnce({ Authorization: "Bearer token-old" })
        .mockReturnValue({ Authorization: "Bearer token-new" })
    };
    listAlerts.mockResolvedValue({ content: [], totalElements: 1, totalPages: 1, page: 0, size: 10 });
    listScoredTransactions.mockResolvedValue({ content: [], totalElements: 3, totalPages: 1, page: 0, size: 25 });

    render(<App />);

    await waitFor(() => expect(refreshSession).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    const authenticatedClientCreations = createAlertsApiClient.mock.calls
      .filter(([options]) => options.session?.userId === "subject-1");
    expect(authenticatedClientCreations.length).toBeGreaterThanOrEqual(2);
    expect(authenticatedClientCreations.at(-1)[0].session.userId).toBe(refreshedSession.userId);
    expect(authenticatedClientCreations.at(-1)[0].session.roles).toEqual(refreshedSession.roles);
    expect(new Set(authenticatedClientCreations.at(-1)[0].session.authorities)).toEqual(new Set(refreshedSession.authorities));
  });

  it("marks workspace counters degraded when one counter fails without hiding successful counters", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    listAlerts.mockRejectedValue(new Error("alerts counter unavailable"));
    listScoredTransactions.mockResolvedValue({ content: [], totalElements: 9, totalPages: 1, page: 0, size: 25 });

    render(<App />);

    expect(await screen.findByText("Counters partially unavailable.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Transactions\s*9/ })).toBeInTheDocument();
  });

  it("clears degraded counter state on session boundary reset", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: (session) => ({ status: session?.userId ? "authenticated" : "unauthenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" }),
      beginLogout: vi.fn().mockResolvedValue(undefined)
    };
    listAlerts.mockRejectedValue(new Error("alerts counter unavailable"));
    listScoredTransactions.mockResolvedValue({ content: [], totalElements: 9, totalPages: 1, page: 0, size: 25 });

    render(<App />);

    expect(await screen.findByText("Counters partially unavailable.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(screen.queryByText("Counters partially unavailable.")).not.toBeInTheDocument());
  });

  it("keeps transaction scoring usable when the fraud case global summary fails", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    getFraudCaseWorkQueueSummary.mockRejectedValue({ status: 503, message: "summary unavailable" });
    listScoredTransactions.mockResolvedValue(transactionPage("txn-visible"));

    render(<App />);

    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("link", { name: "Transaction Scoring" }));

    expect(await screen.findByText("txn-visible")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Session required" })).not.toBeInTheDocument();
  });

  it("does not fetch fraud case summary when transaction scoring is the initial workspace", async () => {
    callbackPath.value = false;
    window.history.replaceState({}, "", "/?workspace=transaction-scoring");
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    listScoredTransactions.mockResolvedValue(transactionPage("txn-visible"));

    render(<App />);

    expect(await screen.findByText("txn-visible")).toBeInTheDocument();
    expect(getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
    expect(listFraudCaseWorkQueue).not.toHaveBeenCalled();
  });

  it("loads the fraud case summary when navigating into the fraud case workspace", async () => {
    callbackPath.value = false;
    window.history.replaceState({}, "", "/?workspace=transaction-scoring");
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    listScoredTransactions.mockResolvedValue(transactionPage("txn-visible"));
    getFraudCaseWorkQueueSummary.mockResolvedValue(summary(47));

    render(<App />);

    expect(await screen.findByText("txn-visible")).toBeInTheDocument();
    expect(getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("link", { name: "Fraud Case" }));

    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("link", { name: /Global fraud cases\s*47/ })).toBeInTheDocument();
  });

  it("does not retry fraud case summary after switching away from the fraud case workspace", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    listScoredTransactions.mockResolvedValue(transactionPage("txn-visible"));
    getFraudCaseWorkQueueSummary.mockResolvedValue(summary(47));

    render(<App />);

    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("link", { name: "Transaction Scoring" }));
    expect(await screen.findByText("txn-visible")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(listScoredTransactions).toHaveBeenCalledTimes(3));
    expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1);
    expect(listFraudCaseWorkQueue).toHaveBeenCalledTimes(1);
  });

  it("keeps workspace state synchronized with browser back and forward navigation", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    listScoredTransactions.mockResolvedValue(transactionPage("txn-visible"));

    render(<App />);

    await waitFor(() => expect(getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("link", { name: "Transaction Scoring" }));
    expect(await screen.findByText("txn-visible")).toBeInTheDocument();

    window.history.pushState({}, "", "/");
    window.dispatchEvent(new PopStateEvent("popstate"));

    expect(await screen.findByRole("heading", { name: "Fraud Case Work Queue" })).toBeInTheDocument();
  });

  it("keeps the newest transaction stream response when an older request resolves later", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    const firstTransactions = deferred();
    const secondTransactions = deferred();
    listAlerts.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 10 });
    listScoredTransactions
      .mockReturnValueOnce(firstTransactions.promise)
      .mockReturnValueOnce(secondTransactions.promise);

    render(<App />);

    fireEvent.click(screen.getByRole("link", { name: "Transaction Scoring" }));
    await waitFor(() => expect(listScoredTransactions).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "customer-123" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(listScoredTransactions).toHaveBeenCalledTimes(2));

    secondTransactions.resolve(transactionPage("txn-new"));
    await screen.findByText("txn-new");

    firstTransactions.resolve(transactionPage("txn-old"));
    await waitFor(() => expect(screen.queryByText("txn-old")).not.toBeInTheDocument());
    expect(screen.getByText("txn-new")).toBeInTheDocument();
  });

  it("does not show a stale transaction stream error after a newer request succeeds", async () => {
    callbackPath.value = false;
    refreshSession.mockResolvedValue(authenticatedSession());
    providerState.value = {
      ...providerState.value,
      getSessionState: () => ({ status: "authenticated" }),
      getRequestHeaders: () => ({ Authorization: "Bearer token-1" })
    };
    const firstTransactions = deferred();
    const secondTransactions = deferred();
    listAlerts.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 10 });
    listScoredTransactions
      .mockReturnValueOnce(firstTransactions.promise)
      .mockReturnValueOnce(secondTransactions.promise);

    render(<App />);

    fireEvent.click(screen.getByRole("link", { name: "Transaction Scoring" }));
    await waitFor(() => expect(listScoredTransactions).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "customer-123" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(listScoredTransactions).toHaveBeenCalledTimes(2));

    secondTransactions.resolve(transactionPage("txn-new"));
    await screen.findByText("txn-new");

    firstTransactions.reject(new Error("old request failed"));
    await waitFor(() => expect(screen.queryByText("Unable to load dashboard data")).not.toBeInTheDocument());
    expect(screen.getByText("txn-new")).toBeInTheDocument();
  });
});

function authenticatedSession() {
  return {
    userId: "subject-1",
    roles: ["READ_ONLY_ANALYST"],
    extraAuthorities: [],
    authorities: ["alert:read", "fraud-case:read", "transaction-monitor:read", "assistant-summary:read"]
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function transactionPage(transactionId) {
  return {
    content: [{
      transactionId,
      customerId: "customer-123",
      transactionAmount: { amount: 120, currency: "PLN" },
      merchantInfo: { merchantName: "Merchant" },
      fraudScore: 0.1,
      riskLevel: "LOW",
      alertRecommended: false,
      scoredAt: "2026-05-11T10:00:00Z"
    }],
    totalElements: 1,
    totalPages: 1,
    page: 0,
    size: 25
  };
}

function summary(totalFraudCases) {
  return {
    totalFraudCases,
    generatedAt: "2026-05-12T10:00:00Z",
    scope: "GLOBAL_FRAUD_CASES",
    snapshotConsistentWithWorkQueue: false
  };
}
