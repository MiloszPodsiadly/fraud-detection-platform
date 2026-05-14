import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WorkspaceRuntimeProvider } from "./WorkspaceRuntimeProvider.jsx";
import { useWorkspaceRuntime } from "./useWorkspaceRuntime.js";

const { createAlertsApiClient } = vi.hoisted(() => ({
  createAlertsApiClient: vi.fn(() => ({ client: true }))
}));

vi.mock("../api/alertsApi.js", () => ({
  createAlertsApiClient
}));

describe("WorkspaceRuntimeProvider", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("creates an explicit workspace api client for an authenticated session", () => {
    const session = authenticatedSession();
    const authProvider = { kind: "oidc" };
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={authProvider}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(createAlertsApiClient).toHaveBeenCalledWith({ session, authProvider });
    expect(result.current.apiClient).toEqual({ client: true });
    expect(result.current.canReadFraudCases).toBe(true);
    expect(result.current.canReadAlerts).toBe(true);
    expect(result.current.canReadGovernanceAdvisories).toBe(true);
    expect(result.current.canWriteGovernanceAudit).toBe(false);
    expect(result.current.runtimeStatus).toBe("ready");
  });

  it("keeps governance advisory read and audit write capabilities separate", () => {
    const session = authenticatedSession({
      authorities: ["governance-advisory:audit:write"]
    });
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(result.current.canReadGovernanceAdvisories).toBe(false);
    expect(result.current.canWriteGovernanceAudit).toBe(true);
  });

  it("does not let governance advisory read imply audit write", () => {
    const session = authenticatedSession({
      authorities: ["transaction-monitor:read"]
    });
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(result.current.canReadGovernanceAdvisories).toBe(true);
    expect(result.current.canWriteGovernanceAudit).toBe(false);
  });

  it("recreates the client when the session object changes for the same user", () => {
    const authProvider = { kind: "oidc" };
    const firstSession = authenticatedSession({ extraAuthorities: [] });
    const refreshedSession = authenticatedSession({ extraAuthorities: ["audit:read"] });
    let currentSession = firstSession;
    const { rerender } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={currentSession} authProvider={authProvider}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    currentSession = refreshedSession;
    rerender();

    expect(createAlertsApiClient).toHaveBeenCalledTimes(2);
    expect(createAlertsApiClient.mock.calls.at(-1)[0]).toEqual({ session: refreshedSession, authProvider });
  });

  it("clears runtime client while unauthenticated", () => {
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={{ userId: "", roles: [], authorities: [] }} authProvider={{ kind: "bff" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(createAlertsApiClient).not.toHaveBeenCalled();
    expect(result.current.apiClient).toBeNull();
    expect(result.current.canReadGovernanceAdvisories).toBeUndefined();
    expect(result.current.canWriteGovernanceAudit).toBeUndefined();
    expect(result.current.runtimeStatus).toBe("disabled");
  });
});

function authenticatedSession(overrides = {}) {
  return {
    userId: "analyst-1",
    roles: ["READ_ONLY_ANALYST"],
    extraAuthorities: [],
    authorities: ["alert:read", "assistant-summary:read", "fraud-case:read", "transaction-monitor:read"],
    ...overrides
  };
}
