import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const safeRepoRoot = repoRoot.replaceAll("\\", "/");

export function getChangedFiles({ baseEnvVar, defaultBase = "master" }) {
  const prefix = baseEnvVar.replace(/_BASE$/, "");
  const label = prefix.replace(/^FDP(\d+)_/, "FDP-$1 ").replace(/_/g, " ").trim();
  const explicit = process.env[`${prefix}_CHANGED_FILES`];
  if (explicit) {
    return explicit.split(/[\r\n,]+/).map((entry) => entry.trim()).filter(Boolean);
  }

  const base = process.env[baseEnvVar] || `origin/${process.env.GITHUB_BASE_REF || defaultBase}`;
  if (!refExists(base)) {
    if (process.env[`${prefix}_ALLOW_HEAD_FALLBACK`] === "true") {
      return runGitDiff("HEAD~1", "HEAD", ["--name-only"]).split(/\r?\n/).filter(Boolean);
    }
    console.error(`Cannot resolve ${label} base ref ${base}`);
    process.exit(1);
  }

  return runGitDiff(base, "HEAD", ["--name-only"]).split(/\r?\n/).filter(Boolean);
}

export function readFileIfExists(path) {
  const absolute = join(repoRoot, path);
  return existsSync(absolute) ? readFileSync(absolute, "utf8") : "";
}

export function fileMatchesAnyPrefix(file, prefixes) {
  const normalized = file.replaceAll("\\", "/");
  return prefixes.some((prefix) => normalized.startsWith(prefix));
}

export function isBackendProductionFile(file) {
  return fileMatchesAnyPrefix(file, [
    "alert-service/src/main/java/",
    "alert-service/src/main/resources/",
    "transaction-ingest-service/src/main/java/",
    "feature-enricher-service/src/main/java/",
    "fraud-scoring-service/src/main/java/",
    "audit-trust-authority/src/main/java/",
    "ml-inference-service/"
  ]);
}

export function isFrontendSourceFile(file) {
  return file.replaceAll("\\", "/").startsWith("analyst-console-ui/src/");
}

export function containsRawFetch(source) {
  return /\bfetch\s*\(/.test(source);
}

export function containsEndpointString(source) {
  return /["']\/(?:api|governance|system|bff)\//.test(source);
}

export function containsSkippedOrFocusedTest(source) {
  return /\b(?:it|describe|test)\.(?:skip|only)\b/.test(source);
}

export function findNamedImportsFrom(source, modulePath) {
  const escapedModule = escapeRegExp(modulePath);
  const imports = [];
  const importPattern = new RegExp(`import\\s*\\{([^}]+)\\}\\s*from\\s*["'][^"']*${escapedModule}["']`, "g");
  for (const match of source.matchAll(importPattern)) {
    imports.push(...match[1].split(",").map((entry) => entry.trim().split(/\s+as\s+/)[0]).filter(Boolean));
  }
  return imports;
}

export function printViolationsAndExit(violations, heading = "Scope guard failed:") {
  if (violations.length === 0) {
    return;
  }
  console.error(heading);
  for (const violation of violations) {
    console.error(`- ${violation}`);
  }
  process.exit(1);
}

export function runGitDiff(base, head = "HEAD", extraArgs = []) {
  return git(["diff", `${base}...${head}`, ...extraArgs]);
}

function refExists(ref) {
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

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
