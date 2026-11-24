import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const PIP_AUDIT_VERSION = "2.10.1";
export const PIP_AUDIT_WHEEL_SHA256 = "99ef3f600a317c1945f1e89e227ef26e1c2d618429b8bd3fa6f4f7c440c4611a";
export const PIP_INDEX_URL = "https://pypi.org/simple";

export function verifyPipAuditWheel(wheelPath) {
  assert.equal(
    path.basename(wheelPath),
    `pip_audit-${PIP_AUDIT_VERSION}-py3-none-any.whl`,
    "the downloaded file is not the pinned pip-audit wheel"
  );
  const digest = createHash("sha256").update(fs.readFileSync(wheelPath)).digest("hex");
  assert.equal(digest, PIP_AUDIT_WHEEL_SHA256, "the pip-audit wheel SHA-256 does not match PyPI");
  return digest;
}

function run(command, args, options, description) {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    ...options
  });
  if (result.error || result.status !== 0) {
    const reason = result.error ? result.error.code ?? "spawn error" : `exit code ${result.status}`;
    throw new Error(`${description} failed (${reason}).`);
  }
  return result;
}

function main() {
  const runnerTemp = process.env.RUNNER_TEMP;
  if (!runnerTemp || !path.isAbsolute(runnerTemp)) {
    throw new Error("RUNNER_TEMP must name an absolute temporary directory.");
  }

  const venvPath = path.join(runnerTemp, "pip-audit-venv");
  const wheelhouse = path.join(runnerTemp, "pip-audit-wheelhouse");
  const expectedWheel = path.join(wheelhouse, `pip_audit-${PIP_AUDIT_VERSION}-py3-none-any.whl`);
  fs.mkdirSync(wheelhouse, { recursive: true });
  if (fs.existsSync(venvPath)) {
    throw new Error("The isolated pip-audit environment already exists; refusing to reuse it.");
  }

  const environment = {
    ...process.env,
    PIP_DISABLE_PIP_VERSION_CHECK: "1",
    PIP_NO_INPUT: "1",
    PIP_INDEX_URL
  };
  const python = process.env.PYTHON ?? "python";
  run(python, ["-m", "venv", venvPath], { env: environment }, "Creating isolated pip-audit environment");

  const venvPython = process.platform === "win32"
    ? path.join(venvPath, "Scripts", "python.exe")
    : path.join(venvPath, "bin", "python");
  run(venvPython, [
    "-m", "pip", "download", "--only-binary=:all:", "--no-deps",
    "--dest", wheelhouse, `pip-audit==${PIP_AUDIT_VERSION}`
  ], { env: environment }, "Downloading pinned pip-audit wheel from official PyPI");

  verifyPipAuditWheel(expectedWheel);
  run(venvPython, [
    "-m", "pip", "install", "--only-binary=:all:", "--no-input", expectedWheel
  ], { env: environment }, "Installing verified pip-audit wheel and isolated CLI dependencies");

  console.log(`Installed pip-audit ${PIP_AUDIT_VERSION}; verified official wheel SHA-256 ${PIP_AUDIT_WHEEL_SHA256}.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(`pip-audit installation failed: ${error.message}`);
    process.exitCode = 1;
  }
}
