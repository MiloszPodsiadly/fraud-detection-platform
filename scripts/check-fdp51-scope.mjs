import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const safeRepoRoot = repoRoot.replaceAll("\\", "/");
const usingExplicitChangedFiles = Boolean(process.env.FDP51_SCOPE_CHANGED_FILES);
const changedFiles = resolveChangedFiles();
const violations = [];

const backendProductionPrefixes = [
  "alert-service/src/main/java/",
  "transaction-ingest-service/src/main/java/",
  "feature-enricher-service/src/main/java/",
  "fraud-scoring-service/src/main/java/",
  "audit-trust-authority/src/main/java/",
  "ml-inference-service/"
];
const allowedEndpointFiles = new Set([
  "analyst-console-ui/src/api/alertsApi.js"
]);
const authSensitiveFrontendPrefixes = [
  "analyst-console-ui/src/workspace/",
  "analyst-console-ui/src/components/",
  "analyst-console-ui/src/pages/",
  "analyst-console-ui/src/fraudCases/"
];
const blockedDefaultApiWrappers = [
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
const blockedWrapperPattern = new RegExp(`\\b(${blockedDefaultApiWrappers.join("|")})\\b`);

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (backendProductionPrefixes.some((prefix) => normalized.startsWith(prefix))) {
    violations.push(`${normalized}: FDP-51 is frontend runtime only; backend production code must not change.`);
  }
  if (!normalized.startsWith("analyst-console-ui/src/") || /\.(test|spec)\.[jt]sx?$/.test(normalized)) {
    continue;
  }
  const diff = usingExplicitChangedFiles ? "" : git(["diff", "--unified=0", `${diffBase()}...HEAD`, "--", normalized]);
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    if (!allowedEndpointFiles.has(normalized) && /["']\/(?:api|governance|system|bff)\//.test(line)) {
      violations.push(`${normalized}: route calls must go through API client boundary.`);
    }
    if (introducesForbiddenWorkflow(line)) {
      violations.push(`${normalized}: export, bulk, assignment, or mass-action workflows are out of scope for FDP-51.`);
    }
    if (isAuthSensitiveFrontendFile(normalized) && introducesDirectDefaultApiWrapperUsage(line)) {
      violations.push(`${normalized}: FDP-51 workspace code must use runtime-provided API clients, not default API wrapper calls.`);
    }
  }
}

if (violations.length > 0) {
  console.error("FDP-51 scope guard failed:");
  for (const violation of violations) {
    console.error(`- ${violation}`);
  }
  process.exit(1);
}

function resolveChangedFiles() {
  const explicit = process.env.FDP51_SCOPE_CHANGED_FILES;
  if (explicit) {
    return explicit.split(/[\r\n,]+/).map((entry) => entry.trim()).filter(Boolean);
  }
  const base = diffBase();
  if (!assertRefExists(base)) {
    if (process.env.FDP51_SCOPE_ALLOW_HEAD_FALLBACK === "true") {
      return git(["diff", "--name-only", "HEAD~1..HEAD"]).split(/\r?\n/).filter(Boolean);
    }
    console.error(`Cannot resolve FDP-51 scope base ref ${base}`);
    process.exit(1);
  }
  return git(["diff", "--name-only", `${base}...HEAD`]).split(/\r?\n/).filter(Boolean);
}

function diffBase() {
  return process.env.FDP51_SCOPE_BASE || `origin/${process.env.GITHUB_BASE_REF || "master"}`;
}

function assertRefExists(ref) {
  try {
    git(["rev-parse", "--verify", ref]);
    return true;
  } catch {
    return false;
  }
}

function git(args) {
  return execFileSync("git", ["-c", `safe.directory=${safeRepoRoot}`, "-C", repoRoot, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  });
}

function introducesForbiddenWorkflow(diffLine) {
  const sourceLine = diffLine.replace(/^\+\s*/, "");
  if (/^export\s+(async\s+)?(function|const|let|var|class)\b/.test(sourceLine)) {
    return false;
  }
  if (/^export\s+(default|\{|\*)\b/.test(sourceLine)) {
    return false;
  }
  return /\b(bulk|assign(?:ment)?|mass action)\b/i.test(sourceLine)
    || /\bexport\s+(workflow|button|action|csv|download|file|report|data|results)\b/i.test(sourceLine)
    || /\b(export|download)\s+(csv|file|report|data|results)\b/i.test(sourceLine)
    || /\b(onExport|handleExport|export[A-Z][A-Za-z]*)\b/.test(sourceLine);
}

function isAuthSensitiveFrontendFile(file) {
  return authSensitiveFrontendPrefixes.some((prefix) => file.startsWith(prefix));
}

function introducesDirectDefaultApiWrapperUsage(diffLine) {
  const sourceLine = diffLine.replace(/^\+\s*/, "");
  return (
    /from\s+["'][^"']*\/api\/alertsApi\.js["']/.test(sourceLine)
    || /from\s+["'][^"']*api\/alertsApi\.js["']/.test(sourceLine)
    || /\balertsApi\./.test(sourceLine)
  ) && blockedWrapperPattern.test(sourceLine);
}
