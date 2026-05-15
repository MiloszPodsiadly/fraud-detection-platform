import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const safeRepoRoot = repoRoot.replaceAll("\\", "/");
const usingExplicitChangedFiles = Boolean(process.env.FDP52_SCOPE_CHANGED_FILES);
const changedFiles = resolveChangedFiles();
const violations = [];

const backendProductionPrefixes = [
  "alert-service/src/main/java/",
  "alert-service/src/main/resources/"
];
const allowedEndpointFiles = new Set([
  "analyst-console-ui/src/api/alertsApi.js"
]);
const outOfScopePattern = /\b(assign(?:ment)?|claim|bulk|mass|optimistic|Kafka|outbox|finality)\b/i;
const exportWorkflowPattern = /\bexport\s+(workflow|button|action|csv|download|file|report|data|results)\b/i;

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (backendProductionPrefixes.some((prefix) => normalized.startsWith(prefix))) {
    violations.push(`${normalized}: FDP-52 is frontend UX decomposition only; backend production code/resources must not change.`);
  }
  if (!isCheckedTextFile(normalized)) {
    continue;
  }
  const diff = usingExplicitChangedFiles ? fileContentAsAddedLines(normalized) : git(["diff", "--unified=0", `${diffBase()}...HEAD`, "--", normalized]);
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    const sourceLine = line.replace(/^\+\s*/, "");
    if (!allowedEndpointFiles.has(normalized) && /["']\/(?:api|governance|system|bff)\//.test(sourceLine)) {
      violations.push(`${normalized}: new endpoint strings must stay behind the API client boundary.`);
    }
    if (!isScopeGuardScript(normalized) && !normalized.startsWith("docs/") && introducesForbiddenScope(sourceLine)) {
      violations.push(`${normalized}: FDP-52 must not introduce assignment, claim, export, bulk, optimistic, Kafka/outbox/finality, or idempotency semantics.`);
    }
  }
}

if (violations.length > 0) {
  console.error("FDP-52 scope guard failed:");
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
  return outOfScopePattern.test(sourceLine)
    || exportWorkflowPattern.test(sourceLine)
    || /\b(export|download)\s+(csv|file|report|data|results)\b/i.test(sourceLine)
    || /\b(onExport|handleExport|export[A-Z][A-Za-z]*)\b/.test(sourceLine);
}

function isScopeGuardScript(file) {
  return /^scripts\/check-fdp\d+-scope\.mjs$/.test(file);
}

function isCheckedTextFile(file) {
  return file.startsWith("analyst-console-ui/src/")
    || file.startsWith("analyst-console-ui/package.json")
    || file.startsWith("scripts/")
    || file.startsWith(".github/workflows/")
    || file.startsWith("docs/");
}

function resolveChangedFiles() {
  const explicit = process.env.FDP52_SCOPE_CHANGED_FILES;
  if (explicit) {
    return explicit.split(/[\r\n,]+/).map((entry) => entry.trim()).filter(Boolean);
  }
  const base = diffBase();
  if (!assertRefExists(base)) {
    if (process.env.FDP52_SCOPE_ALLOW_HEAD_FALLBACK === "true") {
      return git(["diff", "--name-only", "HEAD~1..HEAD"]).split(/\r?\n/).filter(Boolean);
    }
    console.error(`Cannot resolve FDP-52 scope base ref ${base}`);
    process.exit(1);
  }
  return git(["diff", "--name-only", `${base}...HEAD`]).split(/\r?\n/).filter(Boolean);
}

function diffBase() {
  return process.env.FDP52_SCOPE_BASE || `origin/${process.env.GITHUB_BASE_REF || "master"}`;
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
    return git(["show", `HEAD:${file}`]).split(/\r?\n/).map((line) => `+${line}`).join("\n");
  } catch {
    return "";
  }
}

function git(args) {
  return execFileSync("git", ["-c", `safe.directory=${safeRepoRoot}`, "-C", repoRoot, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  });
}
