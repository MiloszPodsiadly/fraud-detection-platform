import { existsSync, readdirSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";
import { repoRoot } from "../fdp-scope/scopeGuardHelpers.mjs";

export function repoPath(path) {
  return join(repoRoot, path);
}

export function assertFile(path) {
  if (!existsSync(repoPath(path))) {
    fail(`missing required file: ${path}`);
  }
}

export function readText(path) {
  assertFile(path);
  return readFileSync(repoPath(path), "utf8");
}

export function readJson(path) {
  return JSON.parse(readText(path));
}

export function assertIncludes(path, token) {
  if (!readText(path).includes(token)) {
    fail(`${path} does not contain ${token}`);
  }
}

export function assertNoRegex(paths, regex, message) {
  const matches = [];
  for (const path of expandPaths(paths)) {
    if (regex.test(readText(path))) {
      matches.push(path);
    }
  }
  if (matches.length > 0) {
    fail(`${message}: ${matches.join(", ")}`);
  }
}

export function assertNoTokensInText(label, source, tokens) {
  for (const token of tokens) {
    if (source.includes(token)) {
      fail(`${label} contains forbidden token: ${token}`);
    }
  }
}

export function assertJUnitClassPassed({ className, reportPath, label, requireMethod }) {
  assertFile(reportPath);
  const source = readText(reportPath);
  const tests = numberAttribute(source, "tests");
  const skipped = numberAttribute(source, "skipped");
  const failures = numberAttribute(source, "failures");
  const errors = numberAttribute(source, "errors");
  if (tests <= 0 || skipped > 0 || failures > 0 || errors > 0) {
    fail(`${label} test class did not fully execute: ${className} tests=${tests} skipped=${skipped} failures=${failures} errors=${errors}`);
  }
  if (requireMethod && !source.includes(`name="${requireMethod}"`)) {
    fail(`${label} test method missing for ${className}: ${requireMethod}`);
  }
}

export function assertChecks(label, checks) {
  const failed = Object.entries(checks).filter(([, ok]) => !ok).map(([name]) => name);
  if (failed.length > 0) {
    fail(`${label} checks failed: ${failed.join(", ")}`);
  }
}

export function expandPaths(paths) {
  return paths.flatMap((path) => {
    if (!path.includes("*")) {
      assertFile(path);
      return [path];
    }
    const slash = path.lastIndexOf("/");
    const directory = slash >= 0 ? path.slice(0, slash) : ".";
    const filePattern = slash >= 0 ? path.slice(slash + 1) : path;
    const regex = new RegExp(`^${escapeRegex(filePattern).replaceAll("*", ".*")}$`);
    const absoluteDirectory = repoPath(directory);
    if (!existsSync(absoluteDirectory)) {
      return [];
    }
    return readdirSync(absoluteDirectory)
      .filter((entry) => regex.test(entry))
      .map((entry) => `${directory}/${entry}`);
  });
}

export function allArtifactText(directory, prefix) {
  const absoluteDirectory = repoPath(directory);
  if (!existsSync(absoluteDirectory)) {
    fail(`missing artifact directory: ${directory}`);
  }
  return readdirSync(absoluteDirectory)
    .filter((entry) => entry.startsWith(prefix))
    .map((entry) => readText(`${directory}/${entry}`))
    .join("\n");
}

export function fail(message) {
  console.error(message);
  process.exit(1);
}

function numberAttribute(source, name) {
  const match = source.match(new RegExp(`<testsuite\\b[^>]*\\b${name}="(\\d+)"`));
  return match ? Number.parseInt(match[1], 10) : 0;
}

function escapeRegex(value) {
  return basename(value).replace(/[.+?^${}()|[\]\\]/g, "\\$&");
}
