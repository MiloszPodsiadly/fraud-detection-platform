import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const disallowed = new Set([
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
]);

const files = [
  "analyst-console-ui/src/App.jsx",
  ...walk("analyst-console-ui/src/fraudCases"),
  ...walk("analyst-console-ui/src/workspace"),
  ...walk("analyst-console-ui/src/components")
].filter((file) => /\.(js|jsx|ts|tsx)$/.test(file));

let failed = false;

for (const file of files) {
  const source = readFileSync(join(root, file), "utf8");
  for (const match of source.matchAll(/import\s*\{([^}]+)\}\s*from\s*["']([^"']*\/api\/alertsApi\.js)["'];?/g)) {
    const blocked = match[1]
      .split(",")
      .map((raw) => raw.trim().split(/\s+as\s+/)[0].trim())
      .filter((name) => disallowed.has(name));
    if (blocked.length > 0) {
      failed = true;
      console.error(`${file} imports default alertsApi wrappers: ${blocked.join(", ")}`);
    }
  }
}

if (failed) {
  console.error("Auth-sensitive workspace code must use createAlertsApiClient({ session, authProvider }), not default alertsApi wrappers.");
  process.exit(1);
}

function walk(relativeDirectory) {
  const directory = join(root, relativeDirectory);
  const entries = readdirSync(directory);
  return entries.flatMap((entry) => {
    const absolute = join(directory, entry);
    const relative = `${relativeDirectory}/${entry}`;
    return statSync(absolute).isDirectory() ? walk(relative) : [relative];
  });
}
