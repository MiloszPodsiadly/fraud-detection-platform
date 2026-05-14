import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const safeRepoRoot = repoRoot.replaceAll("\\", "/");

const usingExplicitChangedFiles = Boolean(process.env.FDP50_SCOPE_CHANGED_FILES);
const changedFiles = resolveChangedFiles();
const violations = [];

const allowedBackendFiles = new Set([
  "alert-service/src/main/java/com/frauddetection/alert/controller/FraudCaseController.java",
  "alert-service/src/main/java/com/frauddetection/alert/security/config/FraudCaseAuthorizationRules.java",
  "alert-service/src/main/java/com/frauddetection/alert/security/config/BffSessionSecurityConfigurer.java",
  "alert-service/src/main/java/com/frauddetection/alert/audit/read/ReadAccessAuditClassifier.java",
  "alert-service/src/main/java/com/frauddetection/alert/observability/AlertServiceMetrics.java",
  "alert-service/src/main/java/com/frauddetection/alert/fraudcase/FraudCaseReadQueryPolicy.java"
]);
const forbiddenBackendProductionPrefixes = [
  "transaction-ingest-service/src/main/java/",
  "feature-enricher-service/src/main/java/",
  "fraud-scoring-service/src/main/java/",
  "ml-inference-service/"
];
const allowedEndpointFiles = new Set([
  "analyst-console-ui/src/api/alertsApi.js"
]);

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (normalized.startsWith("alert-service/src/main/java/") && !allowedBackendFiles.has(normalized)) {
    violations.push(`${normalized}: FDP-50 backend production changes are restricted to the approved legacy fraud-case route removal allowlist.`);
  }
  if (forbiddenBackendProductionPrefixes.some((prefix) => normalized.startsWith(prefix))) {
    violations.push(`${normalized}: FDP-50 must not change backend production code outside the approved alert-service allowlist.`);
  }
}

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (!normalized.startsWith("analyst-console-ui/src/") || /\.(test|spec)\.[jt]sx?$/.test(normalized)) {
    continue;
  }
  const diff = usingExplicitChangedFiles ? "" : git(["diff", "--unified=0", `${diffBase()}...HEAD`, "--", normalized]);
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    if (!allowedEndpointFiles.has(normalized) && /["']\/(?:api|governance|system|bff)\//.test(line)) {
      violations.push(`${normalized}: endpoint URL strings must stay inside the API client boundary`);
    }
    if (/\b(bulk|assign(?:ment)?|mass action)\b/i.test(line)) {
      violations.push(`${normalized}: FDP-50 must not introduce export/bulk/assignment workflows`);
    }
  }
}

if (violations.length > 0) {
  console.error("FDP-50 scope guard failed:");
  for (const violation of violations) {
    console.error(`- ${violation}`);
  }
  process.exit(1);
}

function resolveChangedFiles() {
  const explicit = process.env.FDP50_SCOPE_CHANGED_FILES;
  if (explicit) {
    return explicit.split(/[\r\n,]+/).map((entry) => entry.trim()).filter(Boolean);
  }
  const base = diffBase();
  if (!assertRefExists(base)) {
    if (process.env.FDP50_SCOPE_ALLOW_HEAD_FALLBACK === "true") {
      return git(["diff", "--name-only", "HEAD~1..HEAD"])
        .split(/\r?\n/)
        .filter(Boolean);
    }
    console.error(`Cannot resolve FDP-50 scope base ref ${base}`);
    process.exit(1);
  }
  return git(["diff", "--name-only", `${base}...HEAD`])
    .split(/\r?\n/)
    .filter(Boolean);
}

function diffBase() {
  return process.env.FDP50_SCOPE_BASE || `origin/${process.env.GITHUB_BASE_REF || "master"}`;
}

function assertRefExists(ref) {
  try {
    git(["rev-parse", "--verify", ref]);
    return true;
  } catch {
    return false;
  }
}

function git(args, { allowFailure = false } = {}) {
  try {
    return execFileSync("git", ["-c", `safe.directory=${safeRepoRoot}`, "-C", repoRoot, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    if (allowFailure) {
      return "";
    }
    throw error;
  }
}
