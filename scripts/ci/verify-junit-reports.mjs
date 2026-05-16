import { existsSync, readdirSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";
import { repoRoot } from "../fdp-scope/scopeGuardHelpers.mjs";
import { getCiSuite } from "./ci-suites.mjs";

const options = parseArgs(process.argv.slice(2));
if (options.suite) {
  const suite = getCiSuite(options.suite);
  options.label ||= suite.label ?? options.suite;
  options.reports ||= suite.reports ?? "";
  options.required = options.required.length > 0 ? options.required : suite.junitRequired ?? [];
}

if (!options.label || !options.reports || options.required.length === 0) {
  console.error("Usage: node scripts/ci/verify-junit-reports.mjs --suite <suite> OR --label <name> --reports <dir> --required A,B,C");
  process.exit(2);
}

const reportsDirectory = join(repoRoot, options.reports);
if (!existsSync(reportsDirectory)) {
  console.error(`${options.label} report directory is missing: ${options.reports}`);
  process.exit(1);
}

const reportsByClass = new Map();
for (const entry of readdirSync(reportsDirectory)) {
  if (!entry.startsWith("TEST-") || !entry.endsWith(".xml")) {
    continue;
  }
  const path = join(reportsDirectory, entry);
  const source = readFileSync(path, "utf8");
  const className = classNameFromReport(entry, source);
  reportsByClass.set(className, { path, source });
  reportsByClass.set(className.split(".").at(-1), { path, source });
}

const failures = [];
for (const className of options.required) {
  const report = reportsByClass.get(className);
  if (!report) {
    failures.push(`missing XML report for ${className}`);
    continue;
  }

  const tests = numberAttribute(report.source, "tests");
  const skipped = numberAttribute(report.source, "skipped");
  const failuresCount = numberAttribute(report.source, "failures");
  const errors = numberAttribute(report.source, "errors");
  if (tests <= 0 || skipped > 0 || failuresCount > 0 || errors > 0) {
    failures.push(
      `${className} did not fully execute: report=${basename(report.path)} tests=${tests} skipped=${skipped} failures=${failuresCount} errors=${errors}`
    );
  }
}

if (failures.length > 0) {
  console.error(`${options.label} JUnit report verification failed:`);
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(`${options.label} JUnit report verification passed`);

function parseArgs(args) {
  const parsed = {
    suite: "",
    label: "",
    reports: "",
    required: []
  };
  for (let index = 0; index < args.length; index += 1) {
    const key = args[index];
    const value = args[index + 1];
    if (key === "--suite") {
      parsed.suite = value ?? "";
      index += 1;
    } else if (key === "--label") {
      parsed.label = value ?? "";
      index += 1;
    } else if (key === "--reports") {
      parsed.reports = value ?? "";
      index += 1;
    } else if (key === "--required") {
      parsed.required = (value ?? "").split(",").map((entry) => entry.trim()).filter(Boolean);
      index += 1;
    }
  }
  return parsed;
}

function classNameFromReport(fileName, source) {
  const nameMatch = source.match(/<testsuite\b[^>]*\bname="([^"]+)"/);
  if (nameMatch) {
    return decodeXml(nameMatch[1]);
  }
  return basename(fileName, ".xml").replace(/^TEST-/, "");
}

function numberAttribute(source, name) {
  const match = source.match(new RegExp(`<testsuite\\b[^>]*\\b${name}="(\\d+)"`));
  return match ? Number.parseInt(match[1], 10) : 0;
}

function decodeXml(value) {
  return value
    .replaceAll("&quot;", "\"")
    .replaceAll("&apos;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}
