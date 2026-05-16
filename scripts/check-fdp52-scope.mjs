import {
  containsEndpointString,
  fileMatchesAnyPrefix,
  getChangedFiles,
  printViolationsAndExit,
  readFileIfExists,
  runGitDiff
} from "./fdp-scope/scopeGuardHelpers.mjs";

const usingExplicitChangedFiles = Boolean(process.env.FDP52_SCOPE_CHANGED_FILES);
const changedFiles = getChangedFiles({ baseEnvVar: "FDP52_SCOPE_BASE" });
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
const fdp54GovernanceFiles = new Set([
  "scripts/check-doc-overclaims.mjs",
  "scripts/check-fdp-scope-helpers-smoke.mjs",
  "scripts/compare-ci-jobs.mjs",
  "scripts/fdp-scope/scopeGuardHelpers.mjs"
]);

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (fileMatchesAnyPrefix(normalized, backendProductionPrefixes)) {
    violations.push(`${normalized}: FDP-52 is frontend UX decomposition only; backend production code/resources must not change.`);
  }
  if (!isCheckedTextFile(normalized)) {
    continue;
  }
  const diff = usingExplicitChangedFiles ? fileContentAsAddedLines(normalized) : runGitDiff(diffBase(), "HEAD", ["--unified=0", "--", normalized]);
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    const sourceLine = line.replace(/^\+\s*/, "");
    if (!allowedEndpointFiles.has(normalized) && !isFdp54GovernanceFile(normalized) && containsEndpointString(sourceLine)) {
      violations.push(`${normalized}: new endpoint strings must stay behind the API client boundary.`);
    }
    if (!isScopeGuardScript(normalized) && !normalized.startsWith("docs/") && !isFdp54GovernanceFile(normalized) && introducesForbiddenScope(sourceLine)) {
      violations.push(`${normalized}: FDP-52 must not introduce assignment, claim, export, bulk, optimistic, Kafka/outbox/finality, or idempotency semantics.`);
    }
  }
}

printViolationsAndExit(violations, "FDP-52 scope guard failed:");

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

function isFdp54GovernanceFile(file) {
  return file.startsWith(".github/workflows/") || fdp54GovernanceFiles.has(file);
}

function isCheckedTextFile(file) {
  return file.startsWith("analyst-console-ui/src/")
    || file.startsWith("analyst-console-ui/package.json")
    || file.startsWith("scripts/")
    || file.startsWith(".github/workflows/")
    || file.startsWith("docs/");
}

function diffBase() {
  return process.env.FDP52_SCOPE_BASE || `origin/${process.env.GITHUB_BASE_REF || "master"}`;
}

function fileContentAsAddedLines(file) {
  return readFileIfExists(file).split(/\r?\n/).map((line) => `+${line}`).join("\n");
}
