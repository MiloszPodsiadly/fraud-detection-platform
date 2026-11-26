import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { SECURITY_POLICY } from "../config/policy.mjs";
import { runBoundedProcess } from "../lib/run-bounded-process.mjs";

export const SYNTHETIC_AWS_ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
export const TRIVY_VERSION = SECURITY_POLICY.tools.trivy;
const PROOF_DEADLINE_MS = SECURITY_POLICY.processLimits.secretProofTotalMs;
const TRIVY_VERSION_TIMEOUT_MS = SECURITY_POLICY.processLimits.secretVersionProcessMs;
const MAVEN_TIMEOUT_MS = SECURITY_POLICY.processLimits.secretMavenProcessMs;
const TRIVY_SCAN_TIMEOUT_MS = SECURITY_POLICY.processLimits.secretScanProcessMs;
const TRIVY_SCAN_CLI_TIMEOUT = SECURITY_POLICY.processLimits.secretScanCliTimeout;
const EXPECTED_SECRET = SECURITY_POLICY.secretProof;

export function parseSecretProbeReport(serialized) {
  let report;
  try {
    report = JSON.parse(serialized);
  } catch {
    throw new Error("Trivy secret-only probe produced malformed JSON evidence.");
  }
  return validateSecretProbeReport(report);
}

export function validateSecretProbeReport(report) {
  if (!report || typeof report !== "object" || !Number.isInteger(report.SchemaVersion)
    || !Array.isArray(report.Results)
    || report.Results.some((entry) => !entry || typeof entry !== "object"
      || (entry.Secrets !== undefined && !Array.isArray(entry.Secrets)))) {
    throw new Error("Trivy secret-only probe produced malformed JSON evidence.");
  }

  const findings = report.Results.flatMap((entry) => (entry.Secrets ?? []).map((finding) => ({
    target: basename(entry.Target ?? ""),
    finding,
  })));
  const expected = findings.filter(({ target, finding }) => target === "SyntheticCredentialFixture.java"
    && finding?.RuleID === EXPECTED_SECRET.expectedRuleId
    && finding?.Severity === EXPECTED_SECRET.expectedSeverity);
  if (expected.length !== EXPECTED_SECRET.expectedFindingCount || findings.length !== EXPECTED_SECRET.expectedFindingCount) {
    throw new Error("Trivy secret-only probe did not report exactly one critical AWS key-rule finding in its synthetic fixture.");
  }
  if (findings.some(({ target }) => target === "CleanControl.java")) {
    throw new Error("Trivy secret-only probe reported a secret in its clean control.");
  }
  return { findingCount: findings.length, ruleId: EXPECTED_SECRET.expectedRuleId, severity: EXPECTED_SECRET.expectedSeverity };
}

function basename(value) {
  return String(value).replaceAll("\\", "/").split("/").at(-1);
}

async function runProof() {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "trivy-secret-only-proof-"));
  const scanRoot = path.join(temporary, "scan-root");
  const reportPath = path.join(temporary, "trivy-secret-probe.json");
  const trivyConfigPath = path.join(temporary, "trivy-secret.yaml");
  const localMavenRepository = path.join(temporary, "m2-repository");
  const cacheDirectory = path.join(temporary, "trivy-cache");
  const deadline = Date.now() + PROOF_DEADLINE_MS;
  const abortController = new AbortController();
  const abort = () => abortController.abort();
  process.once("SIGINT", abort);
  process.once("SIGTERM", abort);
  fs.mkdirSync(scanRoot, { recursive: true });

  const run = (command, args, { timeoutMs, ...options }) => {
    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0) {
      return Promise.resolve({ status: 124, timedOut: true, interrupted: false, stdout: "", stderr: "" });
    }
    return runBoundedProcess(command, args, {
      ...options,
      timeoutMs: Math.min(timeoutMs, remainingMs),
      signal: abortController.signal,
    });
  };

  try {
    const trivy = process.env.TRIVY_BIN ?? "trivy";
    const version = await run(trivy, ["--version"], {
      cwd: scanRoot,
      timeoutMs: TRIVY_VERSION_TIMEOUT_MS,
      maxOutputBytes: 64 * 1024,
      env: { ...process.env, TRIVY_CACHE_DIR: cacheDirectory },
    });
    const versionText = `${version.stdout ?? ""}\n${version.stderr ?? ""}`;
    if (version.error || version.status !== 0 || version.timedOut || version.interrupted
      || !new RegExp(`\\b${TRIVY_VERSION.replaceAll(".", "\\.")}\\b`).test(versionText)) {
      throw processFailure("required pinned Trivy scanner", version);
    }

    fs.writeFileSync(path.join(scanRoot, "pom.xml"), `<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>synthetic.fixture</groupId>
  <artifactId>secret-only-offline-proof</artifactId>
  <version>1.0.0</version>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>unavailable.invalid</groupId>
        <artifactId>synthetic-unavailable-bom</artifactId>
        <version>99.99.99</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
`, "utf8");
    fs.writeFileSync(path.join(scanRoot, "SyntheticCredentialFixture.java"),
      `final class SyntheticCredentialFixture {\n  static final String NONFUNCTIONAL_TEST_MARKER = "${SYNTHETIC_AWS_ACCESS_KEY_ID}";\n}\n`, "utf8");
    fs.writeFileSync(path.join(scanRoot, "CleanControl.java"),
      "final class CleanControl {\n  static final String VALUE = \"ordinary-placeholder\";\n}\n", "utf8");
    fs.writeFileSync(trivyConfigPath, [
      "enable-builtin-rules:",
      `  - ${EXPECTED_SECRET.expectedRuleId}`,
      "disable-allow-rules:",
      "  - examples",
      "",
    ].join("\n"), "utf8");

    const mavenExecutable = process.env.MAVEN_BIN ?? (process.platform === "win32" ? "mvn.cmd" : "mvn");
    const maven = await run(mavenExecutable, [
      "-o", "-B", "-ntp", `-Dmaven.repo.local=${localMavenRepository}`,
      "-f", path.join(scanRoot, "pom.xml"), "validate",
    ], {
      cwd: scanRoot,
      shell: process.platform === "win32",
      timeoutMs: MAVEN_TIMEOUT_MS,
      maxOutputBytes: 1024 * 1024,
    });
    const mavenOutput = `${maven.stdout ?? ""}\n${maven.stderr ?? ""}`;
    if (maven.error || maven.timedOut || maven.interrupted || maven.status === 0
      || !mavenOutput.includes("synthetic-unavailable-bom")) {
      throw processFailure("independent offline Maven BOM failure proof", maven);
    }

    const blockedNetworkProxy = "http://127.0.0.1:1";
    const scan = await run(trivy, [
      "--timeout", TRIVY_SCAN_CLI_TIMEOUT,
      "fs", scanRoot,
      "--scanners", "secret",
      "--secret-config", trivyConfigPath,
      "--offline-scan",
      "--skip-db-update",
      "--skip-java-db-update",
      "--skip-version-check",
      "--disable-telemetry",
      "--format", "json",
      "--output", reportPath,
    ], {
      cwd: scanRoot,
      timeoutMs: TRIVY_SCAN_TIMEOUT_MS,
      maxOutputBytes: 256 * 1024,
      env: {
        ...process.env,
        ALL_PROXY: blockedNetworkProxy,
        HTTPS_PROXY: blockedNetworkProxy,
        HTTP_PROXY: blockedNetworkProxy,
        all_proxy: blockedNetworkProxy,
        https_proxy: blockedNetworkProxy,
        http_proxy: blockedNetworkProxy,
        NO_PROXY: "",
        no_proxy: "",
        TRIVY_CACHE_DIR: cacheDirectory,
        TRIVY_OFFLINE_SCAN: "true",
        TRIVY_PARALLEL: "1",
        TRIVY_NO_PROGRESS: "true",
      },
    });
    if (scan.error || scan.timedOut || scan.interrupted || scan.status !== 0 || !fs.existsSync(reportPath)) {
      throw processFailure("offline secret-only Trivy scan", scan);
    }

    const result = parseSecretProbeReport(fs.readFileSync(reportPath, "utf8"));
    const evidencePath = process.env.SECRET_PROOF_EVIDENCE_PATH;
    if (evidencePath) {
      fs.mkdirSync(path.dirname(path.resolve(evidencePath)), { recursive: true });
      fs.writeFileSync(path.resolve(evidencePath), `${JSON.stringify({
        schemaVersion: 1,
        testedGitSha: process.env.GITHUB_SHA ?? null,
        workflow: process.env.GITHUB_WORKFLOW ?? null,
        job: process.env.GITHUB_JOB ?? null,
        runId: process.env.GITHUB_RUN_ID ?? null,
        trivyVersion: TRIVY_VERSION,
        offlineSecretOnlyScan: "success",
        expectedRuleId: result.ruleId,
        expectedSeverity: result.severity,
        findingCount: result.findingCount,
        cleanControl: "passed",
        independentOfflineMavenFailure: "passed",
      }, null, 2)}\n`);
    }
    console.log(`Trivy ${TRIVY_VERSION} secret-only offline proof passed: ${result.findingCount} expected ${result.severity} ${result.ruleId} finding; clean control passed and offline Maven failed on its synthetic BOM.`);
  } finally {
    process.removeListener("SIGINT", abort);
    process.removeListener("SIGTERM", abort);
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

function processFailure(label, result) {
  const cause = result.error?.code
    ? `could not start (${result.error.code})`
    : result.interrupted
      ? "was interrupted"
      : result.timedOut
        ? "exceeded its bounded deadline"
        : `exited with status ${result.status}`;
  return new Error(`${label} ${cause}; scanner output was withheld.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  runProof().catch((error) => {
    console.error(`Trivy secret-only offline proof failed: ${error.message}`);
    process.exitCode = error.message.includes("was interrupted") ? 130 : 1;
  });
}
