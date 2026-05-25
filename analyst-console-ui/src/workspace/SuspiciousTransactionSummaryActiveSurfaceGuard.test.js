import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const workspaceDir = dirname(fileURLToPath(import.meta.url));
const srcDir = resolve(workspaceDir, "..");

describe("SuspiciousTransaction summary active surface guard", () => {
  it("keeps the summary endpoint owned by the mounted workspace navigation counter", () => {
    const shell = source(resolve(workspaceDir, "WorkspaceDashboardShell.jsx"));
    const counters = source(resolve(workspaceDir, "useWorkspaceCounters.js"));
    const navigation = source(resolve(workspaceDir, "WorkspaceNavigation.jsx"));
    const api = source(resolve(srcDir, "api", "alertsApi.js"));

    expect(shell).toContain("useWorkspaceCounters({");
    expect(counters).toContain("promise: apiClient.getSuspiciousTransactionSummary({ signal })");
    expect(api).toContain('getSuspiciousTransactionSummary: (requestOptions) => request("/internal/suspicious-transactions/summary", requestOptions)');
    expect(navigation).toContain("workspaceCounters.suspiciousTransactions");
  });
});

function source(path) {
  return readFileSync(path, "utf8");
}
