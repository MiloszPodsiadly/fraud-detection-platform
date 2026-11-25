import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const syntheticAccessKeyId = "AKIA0000000000000000";
const trivyVersion = "0.76.2";

function main() {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "trivy-secret-only-probe-"));
  const scanRoot = path.join(temporary, "scan-root");
  const reportPath = path.join(temporary, "trivy-secret-probe.json");
  fs.mkdirSync(scanRoot, { recursive: true });
  try {
    const version = spawnSync(process.env.TRIVY_BIN ?? "trivy", ["--version"], {
      cwd: scanRoot,
      encoding: "utf8",
      maxBuffer: 8 * 1024 * 1024,
      env: { ...process.env, TRIVY_CACHE_DIR: path.join(temporary, "trivy-cache") }
    });
    if (version.error || version.status !== 0 || !new RegExp(`\\b${trivyVersion.replaceAll(".", "\\.")}\\b`).test(version.stdout ?? "")) {
      const cause = version.error ? version.error.code ?? "spawn error" : `exit code ${version.status}`;
      throw new Error(`expected pinned Trivy ${trivyVersion} (${cause})`);
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
      `final class SyntheticCredentialFixture {\n  static final String NONFUNCTIONAL_TEST_MARKER = "${syntheticAccessKeyId}";\n}\n`, "utf8");
    fs.writeFileSync(path.join(scanRoot, "CleanControl.java"),
      "final class CleanControl {\n  static final String VALUE = \"ordinary-placeholder\";\n}\n", "utf8");

    const maven = spawnSync(process.env.MAVEN_BIN ?? (process.platform === "win32" ? "mvn.cmd" : "mvn"), [
      "-o", "-B", `-Dmaven.repo.local=${path.join(temporary, "m2-repository")}`,
      "-f", path.join(scanRoot, "pom.xml"), "validate"
    ], {
      cwd: scanRoot,
      encoding: "utf8",
      maxBuffer: 8 * 1024 * 1024,
      shell: process.platform === "win32"
    });
    const mavenOutput = `${maven.stdout ?? ""}\n${maven.stderr ?? ""}`;
    if (maven.error || maven.status === 0 || !mavenOutput.includes("synthetic-unavailable-bom")) {
      const cause = maven.error ? maven.error.code ?? "spawn error" : `exit code ${maven.status}`;
      throw new Error(`offline Maven fixture did not fail on its unavailable synthetic BOM (${cause})`);
    }

    const blockedNetworkProxy = "http://127.0.0.1:1";
    const result = spawnSync(process.env.TRIVY_BIN ?? "trivy", [
      "fs", scanRoot,
      "--scanners", "secret",
      "--offline-scan",
      "--skip-version-check",
      "--disable-telemetry",
      "--format", "json",
      "--output", reportPath
    ], {
      cwd: scanRoot,
      encoding: "utf8",
      maxBuffer: 8 * 1024 * 1024,
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
        TRIVY_CACHE_DIR: path.join(temporary, "trivy-cache"),
        TRIVY_OFFLINE_SCAN: "true",
        TRIVY_PARALLEL: "1"
      }
    });

    if (result.error || result.status !== 0 || !fs.existsSync(reportPath)) {
      const cause = result.error ? result.error.code ?? "spawn error" : `exit code ${result.status}`;
      throw new Error(`secret-only offline probe did not complete (${cause})`);
    }

    const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
    const results = Array.isArray(report.Results) ? report.Results : [];
    const findings = results.flatMap((entry) => Array.isArray(entry?.Secrets)
      ? entry.Secrets.map((finding) => ({ target: entry.Target, finding }))
      : []);
    const intendedFindings = findings.filter(({ target, finding }) =>
      path.basename(target ?? "") === "SyntheticCredentialFixture.java" && finding?.RuleID === "aws-access-key-id");
    if (intendedFindings.length !== 1) {
      throw new Error("secret-only offline probe did not detect exactly one aws-access-key-id finding in the intended source file");
    }
    if (findings.some(({ target }) => path.basename(target ?? "") === "CleanControl.java")) {
      throw new Error("secret-only offline probe reported a secret in its clean control file");
    }
    console.log("Trivy secret-only offline probe detected the expected aws-access-key-id test rule; clean control passed and offline Maven failed on the synthetic BOM.");
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

try {
  main();
} catch (error) {
  console.error(`Trivy secret-only offline probe failed: ${error.message}`);
  process.exitCode = 1;
}
