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

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (backendProductionPrefixes.some((prefix) => normalized.startsWith(prefix))) {
    violations.push(`${normalized}: FDP-51 must not change backend production code.`);
  }
  if (!normalized.startsWith("analyst-console-ui/src/") || /\.(test|spec)\.[jt]sx?$/.test(normalized)) {
    continue;
  }
  const diff = usingExplicitChangedFiles ? "" : git(["diff", "--unified=0", `${diffBase()}...HEAD`, "--", normalized]);
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    if (!allowedEndpointFiles.has(normalized) && /["']\/(?:api|governance|system|bff)\//.test(line)) {
      violations.push(`${normalized}: endpoint URL strings must stay inside the API client boundary.`);
    }
    if (/\b(bulk|assign(?:ment)?|mass action|export)\b/i.test(line)) {
      violations.push(`${normalized}: FDP-51 must not introduce export, bulk, assignment, or mass-action workflows.`);
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
