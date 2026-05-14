import { execFileSync } from "node:child_process";

const changedFiles = resolveChangedFiles();
const violations = [];

const forbiddenBackendPrefixes = [
  "alert-service/src/main/java/",
  "transaction-ingest-service/src/main/java/",
  "feature-enricher-service/src/main/java/",
  "fraud-scoring-service/src/main/java/",
  "ml-inference-service/"
];
const forbiddenBackendHints = [
  "/controller/",
  "/security/",
  "/regulated/",
  "/outbox/",
  "/finality/",
  "/coordinator/"
];
const allowedEndpointFiles = new Set([
  "analyst-console-ui/src/api/alertsApi.js"
]);

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (forbiddenBackendPrefixes.some((prefix) => normalized.startsWith(prefix))) {
    violations.push(`${normalized}: FDP-50 must not change backend production code`);
  }
  if (normalized.endsWith(".java") && forbiddenBackendHints.some((hint) => normalized.includes(hint))) {
    violations.push(`${normalized}: FDP-50 must not change backend auth/controller/lifecycle semantics`);
  }
}

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (!normalized.startsWith("analyst-console-ui/src/") || /\.(test|spec)\.[jt]sx?$/.test(normalized)) {
    continue;
  }
  const diff = git(["diff", "--unified=0", `${diffBase()}...HEAD`, "--", normalized], { allowFailure: true });
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    if (!allowedEndpointFiles.has(normalized) && /["']\/(?:api|governance|system|bff)\//.test(line)) {
      violations.push(`${normalized}: endpoint URL strings must stay inside the API client boundary`);
    }
    if (/\b(export|bulk|assign(?:ment)?|mass action)\b/i.test(line)) {
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
  const output = git(["diff", "--name-only", `${base}...HEAD`], { allowFailure: true });
  if (output.trim()) {
    return output.split(/\r?\n/).filter(Boolean);
  }
  return git(["diff", "--name-only", "HEAD~1..HEAD"], { allowFailure: true })
    .split(/\r?\n/)
    .filter(Boolean);
}

function diffBase() {
  return process.env.FDP50_SCOPE_BASE || `origin/${process.env.GITHUB_BASE_REF || "main"}`;
}

function git(args, { allowFailure = false } = {}) {
  try {
    return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    if (allowFailure) {
      return "";
    }
    throw error;
  }
}
