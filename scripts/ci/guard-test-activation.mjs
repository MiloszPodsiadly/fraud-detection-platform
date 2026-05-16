import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { repoRoot } from "../fdp-scope/scopeGuardHelpers.mjs";
import { getCiSuite } from "./ci-suites.mjs";

const suiteName = parseSuiteName(process.argv.slice(2));
const suite = getCiSuite(suiteName);
const groups = suite.activationGroups ?? [];

if (groups.length === 0) {
  console.error(`${suite.label ?? suiteName} does not define activation guard groups`);
  process.exit(2);
}

const failures = [];
for (const group of groups) {
  const pattern = guardPattern(group.pattern);
  for (const file of group.files ?? []) {
    const path = join(repoRoot, file);
    if (!existsSync(path)) {
      failures.push(`${group.label}: missing guard file ${file}`);
      continue;
    }
    const source = readFileSync(path, "utf8");
    if (pattern.regex.test(source)) {
      failures.push(`${group.label}: ${pattern.message} in ${file}`);
    }
  }
}

if (failures.length > 0) {
  console.error(`${suite.label ?? suiteName} activation guard failed:`);
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(`${suite.label ?? suiteName} activation guard passed`);

function parseSuiteName(args) {
  const suiteIndex = args.indexOf("--suite");
  const value = suiteIndex >= 0 ? args[suiteIndex + 1] : "";
  if (!value) {
    console.error("Usage: node scripts/ci/guard-test-activation.mjs --suite <suite>");
    process.exit(2);
  }
  return value;
}

function guardPattern(name) {
  if (name === "java-no-disabled") {
    return {
      regex: /@Disabled/,
      message: "test class must not be disabled"
    };
  }
  if (name === "vitest-no-skip") {
    return {
      regex: /\b(?:it|describe|test)\.skip\b/,
      message: "frontend test must not be skipped"
    };
  }
  if (name === "vitest-no-skip-or-only") {
    return {
      regex: /\b(?:it|describe|test)\.(?:skip|only)\b/,
      message: "frontend test must not be skipped or focused"
    };
  }
  throw new Error(`Unknown activation guard pattern '${name}'`);
}
