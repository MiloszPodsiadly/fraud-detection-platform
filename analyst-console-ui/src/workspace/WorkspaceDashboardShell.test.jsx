import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const shellSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "WorkspaceDashboardShell.jsx"), "utf8");

describe("WorkspaceDashboardShell FDP-53 composition", () => {
  it("renders the active workspace through WorkspaceRouteRegistry", () => {
    expect(shellSource).toContain("resolveWorkspaceRoute(workspacePage)");
    expect(shellSource).toContain("const ActiveWorkspaceRuntime = activeRoute.Runtime");
    expect(shellSource).toContain("<ActiveWorkspaceRuntime");
    expect(shellSource).toContain("workspaceRoutes={WORKSPACE_ROUTE_ENTRIES}");
  });

  it("keeps workspace-specific runtime hooks out of the shell", () => {
    expect(shellSource).not.toMatch(/useAnalystWorkspaceRuntime|useTransactionWorkspaceRuntime|useGovernanceWorkspaceRuntime/);
    expect(shellSource).not.toMatch(/useFraudCaseWorkQueue|useFraudCaseWorkQueueSummary|useAlertQueue|useScoredTransactionStream/);
    expect(shellSource).not.toMatch(/useGovernanceQueue|useGovernanceAnalytics|useGovernanceAuditWorkflow/);
    expect(shellSource).not.toContain("AnalystWorkspaceContainer");
    expect(shellSource).not.toContain("FraudTransactionWorkspaceContainer");
    expect(shellSource).not.toContain("TransactionScoringWorkspaceContainer");
    expect(shellSource).not.toContain("GovernanceWorkspaceContainer");
    expect(shellSource).not.toContain("ReportsWorkspaceContainer");
  });

  it("keeps shared counters and detail routing single-owned by the shell", () => {
    expect(shellSource).toContain("useWorkspaceCounters");
    expect(shellSource).toContain("<WorkspaceDetailRouter");
    expect(shellSource.match(/useWorkspaceCounters\(/g)).toHaveLength(1);
    expect(shellSource.match(/<WorkspaceDetailRouter/g)).toHaveLength(1);
  });
});
