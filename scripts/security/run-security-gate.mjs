import path from "node:path";
import { fileURLToPath } from "node:url";
import { SECURITY_POLICY, securityPolicyValue, validateSecurityPolicy, writeSecurityPolicyOutputs } from "./config/policy.mjs";
import { runBoundedProcess } from "./lib/run-bounded-process.mjs";

const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

const PHASES = Object.freeze({
  "image-inventory": { file: "scripts/security/evidence/check-image-scan-coverage.mjs", timeoutMs: 5 * 60 * 1000 },
  "image-evidence": { file: "scripts/security/evidence/validate-image-scan-evidence.mjs", timeoutMs: 10 * 60 * 1000 },
  "image-identity": { file: "scripts/security/evidence/write-docker-image-identity.mjs", timeoutMs: 60 * 1000 },
  "image-build-metadata": { file: "scripts/security/evidence/verify-buildx-image-metadata.mjs", timeoutMs: 60 * 1000 },
  "image-platform-identity": { file: "scripts/security/evidence/inspect-pinned-image-platform.mjs", timeoutMs: 5 * 60 * 1000 },
  "image-security-evidence": { file: "scripts/security/evidence/write-image-security-evidence.mjs", timeoutMs: 60 * 1000 },
  "image-sbom-binding": { file: "scripts/security/evidence/bind-image-sbom-identity.mjs", timeoutMs: 60 * 1000 },
  "image-scan-result": { file: "scripts/security/evidence/write-image-scan-result.mjs", timeoutMs: 60 * 1000 },
  "platform-security-evidence": { file: "scripts/security/evidence/write-platform-security-evidence.mjs", timeoutMs: 2 * 60 * 1000 },
  "audit-trust-runtime": { file: "scripts/security/evidence/verify-audit-trust-runtime.mjs", timeoutMs: 60 * 1000 },
  "committed-private-key-check": { file: "scripts/security/scanners/assert-no-committed-private-keys.sh", interpreter: "bash", timeoutMs: 60 * 1000 },
  "compose-bind-mount-check": { file: "scripts/security/config/check-compose-bind-mount-security.sh", interpreter: "bash", timeoutMs: 5 * 60 * 1000 },
  "exact-model-runtime-boundary": { file: "scripts/security/config/check-exact-model-runtime-boundary.sh", interpreter: "bash", timeoutMs: 15 * 60 * 1000 },
  "kafka-image-proof": { file: "scripts/security/evidence/verify-kafka-test-image.mjs", timeoutMs: 60 * 1000 },
  "maven-prefetch": { file: "scripts/security/scanners/resolve-maven-for-trivy.mjs", timeoutMs: 16 * 60 * 1000 },
  "secret-proof": { file: "scripts/security/scanners/verify-trivy-secret-only.mjs", timeoutMs: 6 * 60 * 1000 },
  "vulnerability-evidence": { file: "scripts/security/scanners/trivy-vulnerability-report.mjs", timeoutMs: 60 * 1000 },
  "sbom-validation": { file: "scripts/security/sbom/validate-sbom-artifacts.mjs", timeoutMs: 15 * 60 * 1000 },
  "pip-audit-install": { file: "scripts/security/sbom/install-pinned-pip-audit.mjs", timeoutMs: 10 * 60 * 1000 },
  "pip-dependency-audit": { file: "scripts/security/sbom/run-pip-audit.mjs", timeoutMs: 8 * 60 * 1000 },
  "npm-sbom-audit": { file: "scripts/security/sbom/run-npm-sbom-toolchain-audit.mjs", timeoutMs: 5 * 60 * 1000 },
  "python-sbom-inventory": { file: "scripts/security/sbom/verify-python-sbom-inventory.py", timeoutMs: 5 * 60 * 1000 },
  "java-runtime-policy": { file: "scripts/security/toolchain/verify-java-runtime.mjs", timeoutMs: 60 * 1000 },
  "maven-reactor-list": { file: "scripts/security/sbom/list-maven-reactor-poms.mjs", timeoutMs: 60 * 1000 },
  "maven-sbom-generation": { file: "scripts/security/sbom/generate-maven-sbom.sh", interpreter: "bash", timeoutMs: 20 * 60 * 1000 },
  "pinned-npm-install": { file: "scripts/security/toolchain/install-pinned-npm.sh", interpreter: "bash", timeoutMs: 8 * 60 * 1000 },
  "pinned-cyclonedx-install": { file: "scripts/security/toolchain/install-pinned-cyclonedx-bom.sh", interpreter: "bash", timeoutMs: 5 * 60 * 1000 },
});

export { SECURITY_POLICY, validateSecurityPolicy, writeSecurityPolicyOutputs };

export function securityGatePhase(name) {
  const spec = PHASES[name];
  if (!spec) return null;
  const file = path.join(REPOSITORY_ROOT, spec.file);
  const interpreter = spec.interpreter ?? (file.endsWith(".py") ? (process.env.PYTHON ?? "python") : process.execPath);
  return { ...spec, file, command: interpreter, args: [file] };
}

export function securityGatePhases() {
  return Object.keys(PHASES);
}

async function main(args) {
  const [phase, ...phaseArgs] = args;
  if (!phase) throw new Error(usage());
  if (phase === "policy") {
    const valueIndex = phaseArgs.indexOf("--get");
    if (valueIndex >= 0) {
      const valueName = phaseArgs[valueIndex + 1];
      if (!valueName) throw new Error("--get requires a policy path.");
      process.stdout.write(`${securityPolicyValue(valueName)}\n`);
      return;
    }
    const outputIndex = phaseArgs.indexOf("--github-output");
    const outputPath = outputIndex >= 0 ? phaseArgs[outputIndex + 1] : process.env.GITHUB_OUTPUT;
    if (outputIndex >= 0 && !outputPath) throw new Error("--github-output requires a path.");
    if (outputPath) writeSecurityPolicyOutputs(outputPath, SECURITY_POLICY);
    else process.stdout.write(`${JSON.stringify(SECURITY_POLICY, null, 2)}\n`);
    return;
  }

  const spec = securityGatePhase(phase);
  if (!spec) throw new Error(`Unknown security gate phase "${phase}". Supported phases: ${securityGatePhases().join(", ")}, policy.`);
  const controller = new AbortController();
  const abort = () => controller.abort();
  process.once("SIGINT", abort);
  process.once("SIGTERM", abort);
  try {
    const result = await runBoundedProcess(spec.command, [...spec.args, ...phaseArgs], {
      cwd: REPOSITORY_ROOT,
      timeoutMs: spec.timeoutMs,
      signal: controller.signal,
      inheritStdio: true,
    });
    if (result.error) throw new Error(`${phase} could not start (${result.error.code ?? "spawn error"}).`);
    if (result.timedOut) throw new Error(`${phase} exceeded its ${Math.ceil(spec.timeoutMs / 60_000)} minute bounded deadline.`);
    if (result.interrupted) {
      process.exitCode = 130;
      return;
    }
    if (result.status !== 0) process.exitCode = result.status;
  } finally {
    process.removeListener("SIGINT", abort);
    process.removeListener("SIGTERM", abort);
  }
}

function usage() {
  return `Usage: node scripts/security/run-security-gate.mjs <phase> [phase arguments]\nPhases: ${securityGatePhases().join(", ")}, policy`;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`Security gate failed: ${error.message}`);
    process.exitCode = 1;
  });
}
