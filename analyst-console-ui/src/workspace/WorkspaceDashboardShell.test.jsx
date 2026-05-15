import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const shellSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "WorkspaceDashboardShell.jsx"), "utf8");

describe("WorkspaceDashboardShell FDP-52 composition", () => {
  it("delegates workspace-specific rendering to containers instead of AlertsListPage props", () => {
    expect(shellSource).toContain("AnalystWorkspaceContainer");
    expect(shellSource).toContain("FraudTransactionWorkspaceContainer");
    expect(shellSource).toContain("TransactionScoringWorkspaceContainer");
    expect(shellSource).toContain("GovernanceWorkspaceContainer");
    expect(shellSource).toContain("ReportsWorkspaceContainer");

    const frameCall = shellSource.slice(shellSource.indexOf("<AlertsListPage"), shellSource.indexOf(">", shellSource.indexOf("<AlertsListPage")));
    expect(frameCall).not.toContain("fraudCaseWorkQueue=");
    expect(frameCall).not.toContain("transactionPageRequest=");
    expect(frameCall).not.toContain("governanceAuditHistories=");
    expect(frameCall).not.toContain("onRecordGovernanceAudit=");
  });

  it("passes grouped runtime state into workspace containers", () => {
    expect(shellSource).toContain("workQueueState={fraudCaseWorkQueueState}");
    expect(shellSource).toContain("summaryState={fraudCaseWorkQueueSummaryState}");
    expect(shellSource).toContain("alertQueueState={alertQueueState}");
    expect(shellSource).toContain("transactionStreamState={transactionStreamState}");
    expect(shellSource).toContain("analyticsState={governanceAnalyticsState}");
    expect(shellSource).toContain("queueState={governanceQueueState}");
    expect(shellSource).not.toContain("fraudCaseWorkQueueDraftFilters=");
    expect(shellSource).not.toContain("transactionPageRequest=");
    expect(shellSource).not.toContain("governanceAuditHistories=");
  });
});
