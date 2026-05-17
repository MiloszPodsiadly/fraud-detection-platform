import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const safeRepoRoot = repoRoot.replaceAll("\\", "/");
const usingExplicitChangedFiles = Boolean(process.env.FDP53_SCOPE_CHANGED_FILES);
const changedFiles = resolveChangedFiles();
const violations = [];

const backendProductionPrefixes = [
  "alert-service/src/main/java/",
  "alert-service/src/main/resources/",
  "transaction-ingest-service/src/main/java/",
  "feature-enricher-service/src/main/java/",
  "fraud-scoring-service/src/main/java/",
  "audit-trust-authority/src/main/java/",
  "ml-inference-service/"
];
const fdp55AllowedBackendFiles = new Set([
  "alert-service/src/main/java/com/frauddetection/alert/security/auth/BffLogoutSuccessHandler.java"
]);
const allowedEndpointFiles = new Set([
  "analyst-console-ui/src/api/alertsApi.js"
]);
const workspaceRuntimePrefixes = [
  "analyst-console-ui/src/workspace/"
];
const scriptPath = "scripts/check-fdp53-scope.mjs";
const forbiddenWorkflowPattern = /\b(assign(?:ment)?|claim|bulk|mass[ -]?action|optimistic|Kafka|outbox|finality|idempotency)\b/i;
const forbiddenExportWorkflowPattern = /\bexport\s+(workflow|button|action|csv|download|file|report|data|results)\b/i;
const prefetchPattern = /\b(prefetch|pre-load|preload|warm[ -]?up)\b/i;
const newAuthModePattern = /\b(new auth mode|auth mode|oidc mode|bearer mode|session mode)\b/i;
const defaultWrapperPattern = /from\s+["'][^"']*\/api\/alertsApi\.js["']/;
const fdp54GovernanceFiles = new Set([
  "scripts/check-doc-overclaims.mjs",
  "scripts/check-fdp-scope-helpers-smoke.mjs",
  "scripts/compare-ci-jobs.mjs",
  "scripts/fdp-scope/scopeGuardHelpers.mjs"
]);

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (backendProductionPrefixes.some((prefix) => normalized.startsWith(prefix)) && !fdp55AllowedBackendFiles.has(normalized)) {
    violations.push(`${normalized}: FDP-53 is frontend runtime decomposition only; backend production code/resources must not change.`);
  }
  if (!isCheckedTextFile(normalized)) {
    continue;
  }

  const diff = usingExplicitChangedFiles ? fileContentAsAddedLines(normalized) : git(["diff", "--unified=0", `${diffBase()}...HEAD`, "--", normalized]);
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    const sourceLine = line.replace(/^\+\s*/, "");
    if (!allowedEndpointFiles.has(normalized) && !normalized.startsWith(".github/workflows/") && /["']\/(?:api|governance|system|bff)\//.test(sourceLine)) {
      violations.push(`${normalized}: endpoint strings must stay behind the API client boundary.`);
    }
    if (!isAllowedNarrativeFile(normalized) && introducesForbiddenScope(sourceLine)) {
      violations.push(`${normalized}: FDP-53 must not introduce product workflow, idempotency, Kafka/outbox/finality, auth mode, or speculative prefetch semantics.`);
    }
    if (isWorkspaceRuntimeFile(normalized) && defaultWrapperPattern.test(sourceLine)) {
      violations.push(`${normalized}: workspace/runtime/container code must not import default API wrappers.`);
    }
    if (!isAllowedRawFetchFile(normalized) && /\bfetch\s*\(/.test(sourceLine)) {
      violations.push(`${normalized}: raw fetch is only allowed inside API/auth layers.`);
    }
  }
}

const shellPath = "analyst-console-ui/src/workspace/WorkspaceDashboardShell.jsx";
if (changedFiles.map((file) => file.replaceAll("\\", "/")).includes(shellPath)) {
  const shellSource = readFile(shellPath);
  if (/useAnalystWorkspaceRuntime|useTransactionWorkspaceRuntime|useGovernanceWorkspaceRuntime/.test(shellSource)) {
    violations.push(`${shellPath}: WorkspaceDashboardShell must not import workspace-specific runtime hooks.`);
  }
  if (/useFraudCaseWorkQueue|useAlertQueue|useScoredTransactionStream|useGovernanceQueue|useGovernanceAnalytics/.test(shellSource)) {
    violations.push(`${shellPath}: WorkspaceDashboardShell must not own workspace-specific data hooks.`);
  }
}

const registryPath = "analyst-console-ui/src/workspace/WorkspaceRouteRegistry.jsx";
if (changedFiles.map((file) => file.replaceAll("\\", "/")).includes(registryPath)) {
  const registrySource = readFile(registryPath);
  if (/apiClient|authProvider|authorit/i.test(registrySource) || /\bfetch\s*\(/.test(registrySource)) {
    violations.push(`${registryPath}: WorkspaceRouteRegistry must stay declarative and must not compute auth or call APIs.`);
  }
}

for (const staleRefreshFile of [
  "analyst-console-ui/src/workspace/useWorkspaceRefreshController.js",
  "analyst-console-ui/src/workspace/useWorkspaceRefreshController.test.js"
]) {
  if (existsSync(join(repoRoot, staleRefreshFile))) {
    violations.push(`${staleRefreshFile}: FDP-53 refresh semantics must use workspaceRefreshContract, not the stale refresh controller.`);
  }
}

for (const file of changedFiles.map((entry) => entry.replaceAll("\\", "/"))) {
  if (/analyst-console-ui\/src\/workspace\/[A-Za-z]+WorkspaceRuntime\.jsx$/.test(file)) {
    const source = readFile(file);
    if (/useWorkspaceCounters/.test(source)) {
      violations.push(`${file}: shared counters must remain shell-owned; workspace runtimes must not call useWorkspaceCounters.`);
    }
  }
}

if (violations.length > 0) {
  console.error("FDP-53 scope guard failed:");
  for (const violation of violations) {
    console.error(`- ${violation}`);
  }
  process.exit(1);
}

function introducesForbiddenScope(sourceLine) {
  if (/^export\s+(async\s+)?(function|const|let|var|class)\b/.test(sourceLine)) {
    return false;
  }
  if (/^export\s+(default|\{|\*)\b/.test(sourceLine)) {
    return false;
  }
  return forbiddenWorkflowPattern.test(sourceLine)
    || forbiddenExportWorkflowPattern.test(sourceLine)
    || /\b(export|download)\s+(csv|file|report|data|results)\b/i.test(sourceLine)
    || /\b(onExport|handleExport|export[A-Z][A-Za-z]*)\b/.test(sourceLine)
    || prefetchPattern.test(sourceLine)
    || newAuthModePattern.test(sourceLine);
}

function isCheckedTextFile(file) {
  return file.startsWith("analyst-console-ui/src/")
    || file.startsWith("analyst-console-ui/package.json")
    || file.startsWith("scripts/")
    || file.startsWith(".github/workflows/")
    || file.startsWith("docs/");
}

function isAllowedNarrativeFile(file) {
  return isScopeGuardScript(file)
    || fdp54GovernanceFiles.has(file)
    || file.startsWith("scripts/ci/")
    || file.startsWith("docs/")
    || file.startsWith(".github/workflows/")
    || /\.(test|spec)\.[jt]sx?$/.test(file);
}

function isScopeGuardScript(file) {
  return /^scripts\/check-fdp\d+-scope\.mjs$/.test(file);
}

function isWorkspaceRuntimeFile(file) {
  return workspaceRuntimePrefixes.some((prefix) => file.startsWith(prefix))
    && !/\.(test|spec)\.[jt]sx?$/.test(file);
}

function isAllowedRawFetchFile(file) {
  return file.startsWith("analyst-console-ui/src/api/")
    || file.startsWith("analyst-console-ui/src/auth/")
    || file === scriptPath
    || /\.(test|spec)\.[jt]sx?$/.test(file);
}

function resolveChangedFiles() {
  const explicit = process.env.FDP53_SCOPE_CHANGED_FILES;
  if (explicit) {
    return explicit.split(/[\r\n,]+/).map((entry) => entry.trim()).filter(Boolean);
  }
  const base = diffBase();
  if (!assertRefExists(base)) {
    if (process.env.FDP53_SCOPE_ALLOW_HEAD_FALLBACK === "true") {
      return git(["diff", "--name-only", "HEAD~1..HEAD"]).split(/\r?\n/).filter(Boolean);
    }
    console.error(`Cannot resolve FDP-53 scope base ref ${base}`);
    process.exit(1);
  }
  return git(["diff", "--name-only", `${base}...HEAD`]).split(/\r?\n/).filter(Boolean);
}

function diffBase() {
  return process.env.FDP53_SCOPE_BASE || `origin/${process.env.GITHUB_BASE_REF || "master"}`;
}

function assertRefExists(ref) {
  try {
    git(["rev-parse", "--verify", ref]);
    return true;
  } catch {
    return false;
  }
}

function fileContentAsAddedLines(file) {
  try {
    return readFile(file).split(/\r?\n/).map((line) => `+${line}`).join("\n");
  } catch {
    return "";
  }
}

function readFile(file) {
  return readFileSync(join(repoRoot, file), "utf8");
}

function git(args) {
  return execFileSync("git", ["-c", `safe.directory=${safeRepoRoot}`, "-C", repoRoot, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  });
}
