import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { repoRoot } from "../fdp-scope/scopeGuardHelpers.mjs";
import { getCiSuite } from "./ci-suites.mjs";

const suiteName = parseSuiteName(process.argv.slice(2));
const suite = getCiSuite(suiteName);
const reports = suite.vitestReports ?? [];

if (reports.length === 0) {
  console.error(`${suite.label ?? suiteName} does not define Vitest report checks`);
  process.exit(2);
}

const failures = [];
for (const report of reports) {
  const path = join(repoRoot, report.path);
  if (!existsSync(path)) {
    failures.push(`missing Vitest XML report: ${report.path}`);
    continue;
  }
  const source = readFileSync(path, "utf8");
  for (const required of report.required ?? []) {
    if (!source.includes(required)) {
      failures.push(`${report.path} does not contain ${required}`);
    }
  }
  if (report.forbidSkipped && /skipped="[1-9]/.test(source)) {
    failures.push(`${report.path} contains skipped tests`);
  }
}

for (const marker of suite.markerFiles ?? []) {
  const path = join(repoRoot, marker.path);
  if (!existsSync(path)) {
    failures.push(`missing marker file: ${marker.path}`);
    continue;
  }
  const source = readFileSync(path, "utf8");
  if (!source.includes(marker.contains)) {
    failures.push(`${marker.path} does not contain ${marker.contains}`);
  }
}

if (failures.length > 0) {
  console.error(`${suite.label ?? suiteName} Vitest report verification failed:`);
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(`${suite.label ?? suiteName} Vitest report verification passed`);

function parseSuiteName(args) {
  const suiteIndex = args.indexOf("--suite");
  const value = suiteIndex >= 0 ? args[suiteIndex + 1] : "";
  if (!value) {
    console.error("Usage: node scripts/ci/verify-vitest-reports.mjs --suite <suite>");
    process.exit(2);
  }
  return value;
}
