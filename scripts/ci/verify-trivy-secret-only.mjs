import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const syntheticKey = [
  "-----BEGIN RSA PRIVATE KEY-----",
  "synthetic-fixture-value-this-is-not-a-real-private-key",
  "-----END RSA PRIVATE KEY-----",
  ""
].join("\n");

function main() {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "trivy-secret-only-probe-"));
  const scanRoot = path.join(temporary, "scan-root");
  const reportPath = path.join(temporary, "trivy-secret-probe.json");
  fs.mkdirSync(scanRoot, { recursive: true });
  try {
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
      `final class SyntheticCredentialFixture {\n  static final String TEST_ONLY_KEY = \"\"\"\n${syntheticKey}\"\"\";\n}\n`, "utf8");

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
    const findings = Array.isArray(report.Results)
      ? report.Results.flatMap((entry) => Array.isArray(entry?.Secrets) ? entry.Secrets : [])
      : [];
    if (findings.length === 0) {
      throw new Error("secret-only offline probe did not detect its synthetic source-code credential");
    }
    console.log(`Trivy secret-only offline probe detected ${findings.length} synthetic finding(s); no Maven lookup was available.`);
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
