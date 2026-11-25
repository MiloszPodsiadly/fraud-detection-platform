import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
export const defaultRequirementsPath = path.join(repositoryRoot, "ml-inference-service", "requirements.txt");
export const defaultReportPath = path.join(repositoryRoot, "target", "security", "pip-audit.json");
export const defaultDiagnosticPath = path.join(repositoryRoot, "target", "security", "pip-audit-runner.txt");
export const pinnedVersion = "2.10.1";

export function normalizePackageName(name) {
  return name.toLowerCase().replaceAll(/[._]+/g, "-").replaceAll(/-+/g, "-");
}

export function parseLockedPackages(contents) {
  const packages = [];
  for (const [index, line] of contents.split(/\r?\n/).entries()) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || trimmed.startsWith("--")) continue;
    const match = trimmed.match(/^([A-Za-z0-9][A-Za-z0-9_.-]*)\s*==\s*([^\s;\\]+)(?:\s*;.*)?(?:\s*\\)?$/);
    if (match) {
      packages.push({ name: match[1], version: match[2], line: index + 1 });
    }
  }
  if (packages.length === 0) {
    throw new Error("the Python requirements lock contains no exact package pins");
  }
  const seen = new Set();
  for (const item of packages) {
    const normalized = normalizePackageName(item.name);
    if (seen.has(normalized)) {
      throw new Error(`the Python requirements lock repeats package ${item.name}`);
    }
    seen.add(normalized);
  }
  return packages;
}

export function validateAuditReport(contents, lockedPackages) {
  let report;
  try {
    report = JSON.parse(contents);
  } catch {
    throw new Error("pip-audit did not produce valid JSON");
  }
  if (!report || typeof report !== "object" || Array.isArray(report)) {
    throw new Error("pip-audit JSON must be a top-level report object");
  }
  if (!Array.isArray(report.dependencies) || report.dependencies.length === 0) {
    throw new Error("pip-audit JSON must contain a non-empty dependencies array");
  }
  if (!Array.isArray(report.fixes)) {
    throw new Error("pip-audit JSON must contain the fixes array from the pinned CLI format");
  }

  const expected = new Map(lockedPackages.map((item) => [normalizePackageName(item.name), item.version]));
  const actual = new Map();
  let vulnerabilityCount = 0;
  for (const dependency of report.dependencies) {
    if (!dependency || typeof dependency !== "object" || Array.isArray(dependency)
      || typeof dependency.name !== "string" || dependency.name.length === 0) {
      throw new Error("pip-audit JSON contains a malformed dependency entry");
    }
    if (Object.hasOwn(dependency, "skip_reason")) {
      throw new Error(`pip-audit skipped locked package ${dependency.name}`);
    }
    if (typeof dependency.version !== "string" || dependency.version.length === 0
      || !Array.isArray(dependency.vulns)) {
      throw new Error(`pip-audit JSON contains a malformed or incomplete dependency entry for ${dependency.name}`);
    }
    const name = normalizePackageName(dependency.name);
    if (actual.has(name)) throw new Error(`pip-audit JSON repeats package ${dependency.name}`);
    actual.set(name, dependency.version);
    for (const vulnerability of dependency.vulns) {
      if (!vulnerability || typeof vulnerability !== "object" || Array.isArray(vulnerability)
        || typeof vulnerability.id !== "string" || vulnerability.id.length === 0
        || !Array.isArray(vulnerability.fix_versions)
        || vulnerability.fix_versions.some((version) => typeof version !== "string" || version.length === 0)
        || (vulnerability.aliases !== undefined
          && (!Array.isArray(vulnerability.aliases) || vulnerability.aliases.some((alias) => typeof alias !== "string")))
        || (vulnerability.description !== undefined && typeof vulnerability.description !== "string")) {
        throw new Error(`pip-audit JSON contains a malformed vulnerability for ${dependency.name}`);
      }
      vulnerabilityCount += 1;
    }
  }

  for (const fix of report.fixes) {
    if (!fix || typeof fix !== "object" || Array.isArray(fix)
      || typeof fix.name !== "string" || fix.name.length === 0) {
      throw new Error("pip-audit JSON contains a malformed fix entry");
    }
    const expectedVersion = expected.get(normalizePackageName(fix.name));
    if (!expectedVersion) throw new Error(`pip-audit JSON contains a fix for an unlocked package (${fix.name})`);
    if (Object.hasOwn(fix, "skip_reason")) {
      if (typeof fix.version !== "string" || fix.version.length === 0
        || fix.version !== expectedVersion
        || typeof fix.skip_reason !== "string" || fix.skip_reason.length === 0) {
        throw new Error(`pip-audit JSON contains a malformed skipped fix for ${fix.name}`);
      }
    } else if (typeof fix.old_version !== "string" || fix.old_version.length === 0
      || fix.old_version !== expectedVersion
      || typeof fix.new_version !== "string" || fix.new_version.length === 0) {
      throw new Error(`pip-audit JSON contains a malformed resolved fix for ${fix.name}`);
    }
  }

  for (const name of actual.keys()) {
    if (!expected.has(name)) throw new Error(`pip-audit evidence contains an unlocked package (${name})`);
  }
  if (actual.size !== expected.size) {
    throw new Error(`pip-audit covered ${actual.size} packages; the lock pins ${expected.size}`);
  }
  for (const [name, version] of expected) {
    if (actual.get(name) !== version) {
      throw new Error(`pip-audit evidence is missing or mismatches a pinned package (${name})`);
    }
  }
  return { packageCount: actual.size, vulnerabilityCount };
}

export function runAudit({
  executable = process.env.PIP_AUDIT_BIN ?? "pip-audit",
  requirementsPath = defaultRequirementsPath,
  reportPath = defaultReportPath,
  diagnosticPath = defaultDiagnosticPath,
  cwd = repositoryRoot,
  spawn = spawnSync
} = {}) {
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.rmSync(reportPath, { force: true });
  fs.rmSync(diagnosticPath, { force: true });

  let lockedPackages;
  try {
    lockedPackages = parseLockedPackages(fs.readFileSync(requirementsPath, "utf8"));
  } catch (error) {
    return fail(diagnosticPath, `Unable to read the complete Python lock: ${error.message}`, 1);
  }

  const versionResult = spawn(executable, ["--version"], { cwd, encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
  if (versionResult.error) {
    return fail(diagnosticPath, `Unable to execute pip-audit (${versionResult.error.code ?? "spawn error"}).`, 127);
  }
  if (versionResult.status !== 0 || !new RegExp(`^pip-audit\\s+${pinnedVersion.replaceAll(".", "\\.")}\\s*$`, "m")
    .test(versionResult.stdout ?? "")) {
    return fail(diagnosticPath, `Expected pip-audit ${pinnedVersion}; the installed CLI version was not verified.`, 1);
  }

  const args = [
    "--require-hashes", "--no-deps", "--disable-pip", "--strict",
    "--progress-spinner", "off", "--format", "json", "--output", reportPath,
    "--requirement", requirementsPath
  ];
  const result = spawn(executable, args, { cwd, encoding: "utf8", maxBuffer: 16 * 1024 * 1024 });
  if (result.error) {
    return fail(diagnosticPath, `pip-audit execution failed (${result.error.code ?? "spawn error"}).`, 127);
  }
  if (!fs.existsSync(reportPath)) {
    return fail(diagnosticPath, `pip-audit exited ${result.status ?? "without a status"} and produced no JSON report.`,
      result.status && result.status > 0 ? result.status : 1);
  }

  let summary;
  try {
    summary = validateAuditReport(fs.readFileSync(reportPath, "utf8"), lockedPackages);
  } catch (error) {
    return fail(diagnosticPath, `pip-audit report validation failed: ${error.message}`, 1);
  }

  if (result.status === 0 && summary.vulnerabilityCount === 0) {
    return { status: 0, ...summary };
  }
  if (result.status === 1 && summary.vulnerabilityCount > 0) {
    return fail(diagnosticPath,
      `pip-audit found ${summary.vulnerabilityCount} known vulnerability finding(s) in ${summary.packageCount} packages.`, 1,
      summary);
  }
  return fail(diagnosticPath,
    `pip-audit returned exit ${result.status} with ${summary.vulnerabilityCount} finding(s); expected exit 0 for clean or 1 for findings.`,
    Number.isInteger(result.status) && result.status !== 0 ? result.status : 1,
    summary);
}

function fail(diagnosticPath, message, status, summary = {}) {
  fs.mkdirSync(path.dirname(diagnosticPath), { recursive: true });
  fs.writeFileSync(diagnosticPath, `${message}\n`, "utf8");
  return { status, ...summary, message };
}

function main() {
  const result = runAudit();
  if (result.status === 0) {
    console.log(`pip-audit ${pinnedVersion}: ${result.packageCount} pinned packages audited; no known vulnerabilities.`);
    return;
  }
  console.error(result.message);
  console.error(`Available evidence is under ${path.relative(repositoryRoot, defaultReportPath)}.`);
  process.exitCode = result.status;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
