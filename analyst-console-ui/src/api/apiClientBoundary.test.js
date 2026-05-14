import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const disallowed = [
  "listAlerts",
  "listFraudCaseWorkQueue",
  "getFraudCaseWorkQueueSummary",
  "listScoredTransactions",
  "listGovernanceAdvisories",
  "getGovernanceAdvisoryAnalytics",
  "getGovernanceAdvisoryAudit",
  "recordGovernanceAdvisoryAudit",
  "getAlert",
  "getAssistantSummary",
  "getFraudCase",
  "updateFraudCase",
  "submitAnalystDecision"
];

const authSensitiveFiles = [
  "src/App.jsx",
  ...walk("src/fraudCases"),
  ...walk("src/workspace"),
  ...walk("src/components")
].filter((file) => /\.(js|jsx|ts|tsx)$/.test(file));

describe("api client boundary", () => {
  it("keeps default wrappers compatibility-only", () => {
    const source = readFileSync(join(process.cwd(), "src/api/alertsApi.js"), "utf8");

    expect(source).toContain("Compatibility-only default client. Auth-sensitive workspace code must use createAlertsApiClient({ session, authProvider }).");
  });

  it("prevents auth-sensitive code from importing default alertsApi wrappers", () => {
    const violations = [];

    for (const file of authSensitiveFiles) {
      const source = readFileSync(join(process.cwd(), file), "utf8");
      for (const match of source.matchAll(/import\s*\{([^}]+)\}\s*from\s*["']([^"']*\/api\/alertsApi\.js)["'];?/g)) {
        const blocked = match[1]
          .split(",")
          .map((raw) => raw.trim().split(/\s+as\s+/)[0].trim())
          .filter((name) => disallowed.includes(name));
        if (blocked.length > 0) {
          violations.push(`${file}: ${blocked.join(", ")}`);
        }
      }
    }

    expect(violations).toEqual([]);
  });
});

function walk(relativeDirectory) {
  const directory = join(process.cwd(), relativeDirectory);
  const entries = readdirSync(directory);
  return entries.flatMap((entry) => {
    const absolute = join(directory, entry);
    const relative = `${relativeDirectory}/${entry}`;
    return statSync(absolute).isDirectory() ? walk(relative) : [relative];
  });
}
