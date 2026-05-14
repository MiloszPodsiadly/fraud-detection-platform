import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = process.env.FDP49_API_BOUNDARY_ROOT ?? join(dirname(fileURLToPath(import.meta.url)), "..");
const failureMessage = "Auth-sensitive UI code must use createAlertsApiClient({ session, authProvider }); raw fetch is only allowed in API/auth bootstrap layers.";
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

const files = walk("analyst-console-ui/src")
  .filter((file) => /\.(js|jsx|ts|tsx)$/.test(file))
  .filter((file) => !isExcluded(file));

let failed = false;

for (const file of files) {
  const source = readFileSync(join(root, file), "utf8");
  const namespaceImports = new Set();

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

  for (const match of source.matchAll(/export\s*\{([^}]+)\}\s*from\s*["']([^"']*\/api\/alertsApi\.js)["'];?/g)) {
    const blocked = match[1]
      .split(",")
      .map((raw) => raw.trim().split(/\s+as\s+/)[0].trim())
      .filter((name) => disallowed.has(name));
    if (blocked.length > 0) {
      failed = true;
      console.error(`${file} re-exports default alertsApi wrappers: ${blocked.join(", ")}`);
    }
  }
  if (/export\s+\*\s+from\s*["'][^"']*\/api\/alertsApi\.js["'];?/.test(source)) {
    failed = true;
    console.error(`${file} re-exports alertsApi.js from an auth-sensitive barrel`);
  }
  if (/export\s+\{\s*default(?:\s+as\s+[A-Za-z_$][\w$]*)?\s*\}\s*from\s*["'][^"']*\/api\/alertsApi\.js["'];?/.test(source)) {
    failed = true;
    console.error(`${file} re-exports the default alertsApi client from an auth-sensitive barrel`);
  }

  for (const match of source.matchAll(/import\s+\*\s+as\s+([A-Za-z_$][\w$]*)\s+from\s*["']([^"']*\/api\/alertsApi\.js)["'];?/g)) {
    namespaceImports.add(match[1]);
  }
  for (const namespace of namespaceImports) {
    const usedWrappers = [...disallowed].filter((name) => new RegExp(`\\b${namespace}\\.${name}\\s*\\(`).test(source));
    if (usedWrappers.length > 0) {
      failed = true;
      console.error(`${file} uses default alertsApi wrappers through namespace ${namespace}: ${usedWrappers.join(", ")}`);
    }
  }

  if (/import\s*\(\s*["'][^"']*\/api\/alertsApi\.js["']\s*\)/.test(source)) {
    failed = true;
    console.error(`${file} dynamically imports alertsApi.js outside the API layer`);
  }

  for (const match of source.matchAll(/\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*fetch\s*;?/g)) {
    const alias = match[1];
    if (new RegExp(`\\b${alias}\\s*\\(`).test(source.slice(match.index + match[0].length))) {
      failed = true;
      console.error(`${file} aliases raw fetch as ${alias} outside the API/auth bootstrap layer`);
    }
  }

  if (/\b(?:fetch|window\.fetch|globalThis\.fetch|window\[\s*["']fetch["']\s*\]|globalThis\[\s*["']fetch["']\s*\])\s*\(/.test(source)) {
    failed = true;
    console.error(`${file} uses raw fetch outside the API/auth bootstrap layer`);
  }
}

if (failed) {
  console.error(failureMessage);
  process.exit(1);
}

function walk(relativeDirectory) {
  const directory = join(root, relativeDirectory);
  if (!existsSync(directory)) {
    return [];
  }
  const entries = readdirSync(directory);
  return entries.flatMap((entry) => {
    const absolute = join(directory, entry);
    const relative = `${relativeDirectory}/${entry}`;
    return statSync(absolute).isDirectory() ? walk(relative) : [relative];
  });
}

function isExcluded(file) {
  const normalized = file.replaceAll("\\", "/");
  if (normalized.startsWith("analyst-console-ui/src/api/")) {
    return true;
  }
  if (normalized === "analyst-console-ui/src/auth/authProvider.js") {
    return true;
  }
  if (/(\.test\.[jt]sx?|__mocks__|\/mocks\/|generated)/.test(normalized)) {
    return true;
  }
  return false;
}
