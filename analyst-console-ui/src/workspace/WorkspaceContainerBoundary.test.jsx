import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const workspaceDir = dirname(fileURLToPath(import.meta.url));
const containerFiles = [
  "AnalystWorkspaceContainer.jsx",
  "FraudTransactionWorkspaceContainer.jsx",
  "TransactionScoringWorkspaceContainer.jsx",
  "GovernanceWorkspaceContainer.jsx",
  "ReportsWorkspaceContainer.jsx"
];

describe("workspace container boundaries", () => {
  it("keeps containers as presentation boundaries without raw API access", () => {
    for (const file of containerFiles) {
      const source = readFileSync(resolve(workspaceDir, file), "utf8");

      expect(source).not.toMatch(/from\s+["'][^"']*api\/alertsApi\.js["']/);
      expect(source).not.toMatch(/\bfetch\s*\(/);
      expect(source).not.toMatch(/use[A-Z][A-Za-z]+WorkspaceRuntime/);
      expect(source).toContain("workspaceHeadingProps");
    }
  });
});
