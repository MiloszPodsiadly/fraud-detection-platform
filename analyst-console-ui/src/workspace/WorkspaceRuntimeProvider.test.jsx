import { renderHook } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { join } from "node:path";
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
    expect(result.current.canReadSuspiciousTransactions).toBe(false);
    expect(result.current.canReadGovernanceAdvisories).toBe(true);
    expect(result.current.canReadShadowPerformance).toBe(false);
    expect(result.current.canWriteGovernanceAudit).toBe(false);
    expect(result.current.runtimeStatus).toBe("ready");
  });

  it("capabilities are frontend gating hints, not backend authorization enforcement", () => {
    const session = authenticatedSession({
      authorities: ["alert:read", "fraud-case:read", "transaction-monitor:read", "suspicious-transaction:read"]
    });
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(result.current.canReadAlerts).toBe(true);
    expect(result.current.canReadFraudCases).toBe(true);
    expect(result.current.canReadTransactions).toBe(true);
    expect(result.current.canReadSuspiciousTransactions).toBe(true);
    expect(result.current.canReadShadowPerformance).toBe(false);
    expect(result.current.canWriteGovernanceAudit).toBe(false);
    expect(result.current.apiClient).toEqual({ client: true });
  });

  it("documents that the provider is not a security boundary", () => {
    const docs = readFileSync(join(process.cwd(), "../docs/fdp/fdp_51_workspace_runtime_provider.md"), "utf8");
    const source = readFileSync(join(process.cwd(), "src/workspace/WorkspaceRuntimeProvider.jsx"), "utf8");

    expect(docs).toContain("not an authorization or security enforcement boundary");
    expect(docs).toContain("Backend authorization remains authoritative");
    expect(source).toContain("Backend authorization still enforces every protected API call");
  });

  it("frontendGuardDoesNotClaimSecurityBoundary", () => {
    const docs = readFileSync(join(process.cwd(), "../docs/product/suspicious_transaction_internal_ui.md"), "utf8");
    const source = readFileSync(join(process.cwd(), "src/workspace/WorkspaceRuntimeProvider.jsx"), "utf8");

    expect(docs).toContain("Frontend capability mapping is a session and UX hint only");
    expect(docs).toContain("backend authorization is authoritative");
    expect(docs).toContain("FDP-66 does not change production role provisioning");
    expect(source).toContain("Backend authorization still enforces every protected API call");
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
    expect(result.current.canReadAlerts).toBe(false);
  });

  it("maps governance advisory read capability to transaction monitor read authority by current backend contract", () => {
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

  it("dashboardRouteRequiresShadowPerformanceRead", () => {
    const session = authenticatedSession({
      authorities: ["transaction-monitor:read", "fraud-case:read", "assistant-summary:read"]
    });
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(result.current.canReadShadowPerformance).toBe(false);
    expect(result.current.canReadGovernanceAdvisories).toBe(true);
  });

  it("dashboardVisibleWithShadowPerformanceRead", () => {
    const session = authenticatedSession({
      authorities: ["shadow-performance:read"]
    });
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(result.current.canReadShadowPerformance).toBe(true);
  });

  it.each([
    ["genericTransactionReadDoesNotShowDashboard", ["transaction-monitor:read"]],
    ["fraudCaseReadDoesNotShowDashboard", ["fraud-case:read"]],
    ["assistantSummaryReadDoesNotShowDashboard", ["assistant-summary:read"]]
  ])("%s", (_name, authorities) => {
    const session = authenticatedSession({ authorities });
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(result.current.canReadShadowPerformance).toBe(false);
  });

  it("reports missing authorities as unknown capabilities", () => {
    const session = authenticatedSession({ authorities: [] });
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(result.current.runtimeStatus).toBe("ready");
    expect(result.current.apiClient).toEqual({ client: true });
    expect(result.current.canReadAlerts).toBeUndefined();
    expect(result.current.canReadFraudCases).toBeUndefined();
    expect(result.current.canReadTransactions).toBeUndefined();
    expect(result.current.canReadSuspiciousTransactions).toBeUndefined();
    expect(result.current.canReadGovernanceAdvisories).toBeUndefined();
    expect(result.current.canReadShadowPerformance).toBeUndefined();
    expect(result.current.canWriteGovernanceAudit).toBeUndefined();
  });

  it("keeps runtime disabled when provider is disabled even with an authenticated session", () => {
    const { result } = renderHook(() => useWorkspaceRuntime(), {
      wrapper: ({ children }) => (
        <WorkspaceRuntimeProvider session={authenticatedSession()} authProvider={{ kind: "oidc" }} enabled={false}>
          {children}
        </WorkspaceRuntimeProvider>
      )
    });

    expect(createAlertsApiClient).not.toHaveBeenCalled();
    expect(result.current.apiClient).toBeNull();
    expect(result.current.runtimeStatus).toBe("disabled");
    expect(result.current.canReadAlerts).toBe(true);
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
