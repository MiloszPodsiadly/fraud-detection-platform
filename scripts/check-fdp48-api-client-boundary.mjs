import { readFileSync } from "node:fs";
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
  "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueue.js",
  "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueueSummary.js",
  "analyst-console-ui/src/workspace/useAlertQueue.js",
  "analyst-console-ui/src/workspace/useGovernanceAnalytics.js",
  "analyst-console-ui/src/workspace/useGovernanceQueue.js",
  "analyst-console-ui/src/workspace/useScoredTransactionStream.js"
];

let failed = false;

for (const file of files) {
  const source = readFileSync(join(root, file), "utf8");
  for (const match of source.matchAll(/import\s*\{([^}]+)\}\s*from\s*["'](?:\.\.\/api|\.\/api)\/alertsApi\.js["'];?/g)) {
    const importedNames = match[1]
      .split(",")
      .map((raw) => raw.trim().split(/\s+as\s+/)[0].trim())
      .filter(Boolean);
    const blocked = importedNames.filter((name) => disallowed.has(name));
    if (blocked.length > 0) {
      failed = true;
      console.error(`${file} imports compatibility-only API wrappers: ${blocked.join(", ")}`);
    }
  }
}

if (failed) {
  console.error("FDP-48 workspace code must use createAlertsApiClient({ session, authProvider }) and pass the apiClient into hooks.");
  process.exit(1);
}
