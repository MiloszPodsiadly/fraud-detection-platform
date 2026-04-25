import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const {
  callbackPath,
  completeLoginCallback,
  providerState,
  refreshSession,
  listAlerts,
  listFraudCases,
  listScoredTransactions,
  setApiSession
} = vi.hoisted(() => ({
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
  listAlerts: vi.fn(),
  listFraudCases: vi.fn(),
  listScoredTransactions: vi.fn(),
  setApiSession: vi.fn()
}));

vi.mock("./api/alertsApi.js", () => ({
  listAlerts,
  listFraudCases,
  listScoredTransactions,
  setApiSession
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
    window.scrollTo = vi.fn();
    callbackPath.value = true;
    refreshSession.mockResolvedValue({ userId: "", roles: [], extraAuthorities: [], authorities: [] });
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

  it("handles the dedicated OIDC callback path before loading dashboard data", async () => {
    window.history.replaceState({}, "", "/auth/callback?state=test-state&code=test-code");
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
    listFraudCases.mockResolvedValue({ content: [], totalElements: 2, totalPages: 1, page: 0, size: 4 });
    listScoredTransactions.mockResolvedValue({ content: [], totalElements: 3, totalPages: 1, page: 0, size: 25 });

    render(<App />);

    await waitFor(() => expect(completeLoginCallback).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(listAlerts).toHaveBeenCalledTimes(1));
    expect(listFraudCases).toHaveBeenCalledTimes(1);
    expect(listScoredTransactions).toHaveBeenCalledTimes(1);
    expect(setApiSession).toHaveBeenCalled();
    expect(window.location.pathname).toBe("/");
    expect(window.location.search).toBe("");
    expect(window.scrollTo).toHaveBeenCalledWith(0, 0);
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
    expect(screen.getAllByRole("heading", { name: "Session expired" })).toHaveLength(2);
    expect(listAlerts).not.toHaveBeenCalled();
    expect(listFraudCases).not.toHaveBeenCalled();
    expect(listScoredTransactions).not.toHaveBeenCalled();
  });

  it("allows restarting OIDC sign-in when callback state is missing", async () => {
    const beginLogin = vi.fn().mockResolvedValue(undefined);
    completeLoginCallback.mockRejectedValue(new Error("No matching state found in storage"));
    providerState.value = {
      ...providerState.value,
      beginLogin
    };

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Sign-in callback failed" })).toBeInTheDocument();
    expect(screen.getByText("No matching state found in storage")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Restart OIDC sign-in" }));

    await waitFor(() => expect(beginLogin).toHaveBeenCalledTimes(1));
    expect(listAlerts).not.toHaveBeenCalled();
    expect(listFraudCases).not.toHaveBeenCalled();
    expect(listScoredTransactions).not.toHaveBeenCalled();
  });

  it("loads dashboard data once after an authenticated oidc bootstrap without flipping the session badge back to loading", async () => {
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
    listFraudCases.mockResolvedValue({ content: [], totalElements: 2, totalPages: 1, page: 0, size: 4 });
    listScoredTransactions.mockResolvedValue({ content: [], totalElements: 3, totalPages: 1, page: 0, size: 25 });

    render(<App />);

    await waitFor(() => expect(refreshSession).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(listAlerts).toHaveBeenCalledTimes(1));
    expect(listFraudCases).toHaveBeenCalledTimes(1);
    expect(listScoredTransactions).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    expect(screen.queryByText("Loading session state...")).not.toBeInTheDocument();
  });
});
