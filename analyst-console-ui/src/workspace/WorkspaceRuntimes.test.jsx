import { render, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WORKSPACE_ROUTE_REGISTRY } from "./WorkspaceRouteRegistry.jsx";
import { WorkspaceRuntimeContext } from "./useWorkspaceRuntime.js";

const apiClient = {
  getFraudCaseWorkQueueSummary: vi.fn(),
  listFraudCaseWorkQueue: vi.fn(),
  listAlerts: vi.fn(),
  listScoredTransactions: vi.fn(),
  listGovernanceAdvisories: vi.fn(),
  getGovernanceAdvisoryAnalytics: vi.fn(),
  getGovernanceAdvisoryAudit: vi.fn(),
  recordGovernanceAdvisoryAudit: vi.fn()
};

describe("workspace runtime ownership", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.getFraudCaseWorkQueueSummary.mockResolvedValue({ totalFraudCases: 1 });
    apiClient.listFraudCaseWorkQueue.mockResolvedValue(workQueueSlice([{ caseId: "case-1" }]));
    apiClient.listAlerts.mockResolvedValue(page([{ alertId: "alert-1" }]));
    apiClient.listScoredTransactions.mockResolvedValue(page([{ transactionId: "txn-1" }]));
    apiClient.listGovernanceAdvisories.mockResolvedValue({ status: "AVAILABLE", count: 0, advisory_events: [] });
    apiClient.getGovernanceAdvisoryAnalytics.mockResolvedValue(analytics(1));
    apiClient.getGovernanceAdvisoryAudit.mockResolvedValue({ status: "AVAILABLE", audit_events: [] });
    apiClient.recordGovernanceAdvisoryAudit.mockResolvedValue(undefined);
  });

  it("loads only analyst workspace data on initial analyst runtime mount", async () => {
    const stableRuntime = runtimeValue();
    const stableProps = runtimeProps();
    const { rerender } = renderRuntime("analyst", {}, stableRuntime, stableProps);

    await waitFor(() => expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(apiClient.getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1));
    expect(apiClient.listAlerts).not.toHaveBeenCalled();
    expect(apiClient.listScoredTransactions).not.toHaveBeenCalled();

    rerender(runtimeElement("analyst", {}, stableRuntime, stableProps));

    expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1);
    expect(apiClient.getFraudCaseWorkQueueSummary).toHaveBeenCalledTimes(1);
  });

  it("mounts only the active runtime while switching workspaces", async () => {
    const { rerender } = renderRuntime("analyst");
    await waitFor(() => expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1));

    rerender(runtimeElement("fraudTransaction"));
    await waitFor(() => expect(apiClient.listAlerts).toHaveBeenCalledTimes(1));
    expect(apiClient.listFraudCaseWorkQueue).toHaveBeenCalledTimes(1);
    expect(apiClient.listScoredTransactions).not.toHaveBeenCalled();

    rerender(runtimeElement("transactionScoring"));
    await waitFor(() => expect(apiClient.listScoredTransactions).toHaveBeenCalledTimes(1));
    expect(apiClient.listAlerts).toHaveBeenCalledTimes(1);
  });

  it("keeps governance queue and reports analytics as separate active owners", async () => {
    const { rerender } = renderRuntime("compliance");
    await waitFor(() => expect(apiClient.listGovernanceAdvisories).toHaveBeenCalledTimes(1));
    expect(apiClient.getGovernanceAdvisoryAnalytics).not.toHaveBeenCalled();

    rerender(runtimeElement("reports"));
    await waitFor(() => expect(apiClient.getGovernanceAdvisoryAnalytics).toHaveBeenCalledTimes(1));
    expect(apiClient.listGovernanceAdvisories).toHaveBeenCalledTimes(1);
  });

  it("does not fetch hidden workspace data when authority is missing", async () => {
    renderRuntime("analyst", { canReadFraudCases: false });

    await waitFor(() => expect(apiClient.listFraudCaseWorkQueue).not.toHaveBeenCalled());
    expect(apiClient.getFraudCaseWorkQueueSummary).not.toHaveBeenCalled();
  });

  it.each([
    ["analyst", "listFraudCaseWorkQueue"],
    ["fraudTransaction", "listAlerts"],
    ["transactionScoring", "listScoredTransactions"],
    ["compliance", "listGovernanceAdvisories"],
    ["reports", "getGovernanceAdvisoryAnalytics"]
  ])("surfaces %s runtime API failures without replacing them with fake empty state", async (routeKey, methodName) => {
    apiClient[methodName].mockRejectedValueOnce(new Error(`${routeKey} unavailable`));
    const onResult = vi.fn();

    renderRuntime(routeKey, {}, runtimeValue(), { ...runtimeProps(), onResult });

    await waitFor(() => expect(lastRuntimeResult(onResult)?.error?.message).toBe(`${routeKey} unavailable`));
  });

  it("does not hidden-fetch reports analytics from compliance runtime", async () => {
    renderRuntime("compliance");

    await waitFor(() => expect(apiClient.listGovernanceAdvisories).toHaveBeenCalledTimes(1));
    expect(apiClient.getGovernanceAdvisoryAnalytics).not.toHaveBeenCalled();
  });
});

function renderRuntime(routeKey, runtimeOverrides = {}, runtimeContext = runtimeValue(runtimeOverrides), props = runtimeProps()) {
  return render(runtimeElement(routeKey, runtimeOverrides, runtimeContext, props));
}

function runtimeElement(routeKey, runtimeOverrides = {}, runtimeContext = runtimeValue(runtimeOverrides), props = runtimeProps()) {
  const route = WORKSPACE_ROUTE_REGISTRY[routeKey];
  const Runtime = route.Runtime;
  return (
    <WorkspaceRuntimeContext.Provider value={runtimeContext}>
      <Runtime
        route={route}
        sharedWorkspaceReadsEnabled
        setCounterValue={props.setCounterValue}
        setSessionState={props.setSessionState}
        onOpenAlert={props.onOpenAlert}
        onOpenFraudCase={props.onOpenFraudCase}
      >
        {(result) => {
          props.onResult?.(result);
          return <div>{result.workspaceContent}</div>;
        }}
      </Runtime>
    </WorkspaceRuntimeContext.Provider>
  );
}

function runtimeProps() {
  return {
    setCounterValue: vi.fn(),
    setSessionState: vi.fn(),
    onOpenAlert: vi.fn(),
    onOpenFraudCase: vi.fn(),
    onResult: null
  };
}

function lastRuntimeResult(onResult) {
  return onResult.mock.calls.at(-1)?.[0];
}

function runtimeValue(overrides = {}) {
  return {
    session: { userId: "analyst-1", authorities: [] },
    authProvider: { kind: "demo" },
    apiClient,
    canReadFraudCases: true,
    canReadAlerts: true,
    canReadTransactions: true,
    canReadGovernanceAdvisories: true,
    canWriteGovernanceAudit: false,
    workspaceSessionResetKey: "demo:analyst-1",
    runtimeStatus: "ready",
    ...overrides
  };
}

function page(content, overrides = {}) {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    page: 0,
    size: 25,
    ...overrides
  };
}

function workQueueSlice(cases) {
  return {
    cases,
    nextCursor: null,
    hasNext: false,
    size: cases.length,
    sort: "createdAt,desc"
  };
}

function analytics(advisories) {
  return {
    status: "AVAILABLE",
    window: { days: 7 },
    totals: { advisories, reviewed: advisories, open: 0 },
    decision_distribution: {},
    lifecycle_distribution: {},
    review_timeliness: { status: "LOW_CONFIDENCE" }
  };
}
