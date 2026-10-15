import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const bootstrap = path.join(repositoryRoot, "scripts", "bootstrap-local-fixtures.sh");
const bashExecutable = findBash();

test("ordinary fixture provisioning is repeatable and parallel-safe", async (context) => {
  if (!bashExecutable) {
    context.skip("Bash is unavailable");
    return;
  }
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "local-startup-"));
  const environment = {
    ...process.env,
    LOCAL_IDENTITY_FIXTURE_ROOT: path.join(temporary, "identity"),
    LOCAL_GENERATED_FIXTURE_ROOT: path.join(temporary, "generated")
  };
  try {
    runBootstrap("--force", environment);
    const before = fixtureFingerprint(temporary);
    const identityRoot = path.join(temporary, "identity", "service-identity");
    const originalIdentity = fixtureFingerprint(identityRoot);
    runBootstrap("--ensure", environment);
    assert.equal(fixtureFingerprint(temporary), before);

    await Promise.all([
      runBootstrapAsync("--ensure", environment),
      runBootstrapAsync("--ensure", environment)
    ]);
    assert.equal(fixtureFingerprint(temporary), before);

    const evaluationManifest = path.join(
      temporary,
      "generated",
      "platform-recommendation-evaluation-card",
      "manifest.json"
    );
    fs.writeFileSync(evaluationManifest, "invalid\n", "utf8");
    runBootstrap("--ensure", environment);
    assert.equal(fixtureFingerprint(identityRoot), originalIdentity);

    const certificate = path.join(
      temporary,
      "identity",
      "service-identity",
      "mtls",
      "alert-service.pem"
    );
    fs.chmodSync(certificate, 0o644);
    fs.writeFileSync(certificate, "invalid\n", "utf8");
    runBootstrap("--ensure", environment);
    assert.notEqual(fixtureFingerprint(identityRoot), originalIdentity);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test("Make targets share one project identity and keep down non-destructive", (context) => {
  const make = spawnSync("make", ["--version"], { encoding: "utf8" });
  if (make.error?.code === "ENOENT") {
    context.skip("GNU Make is unavailable");
    return;
  }
  assert.equal(make.status, 0, make.stderr);

  const makefile = fs.readFileSync(path.join(repositoryRoot, "Makefile"), "utf8");
  assert.match(makefile, /^COMPOSE_PROJECT_NAME \?= fraud-detection-platform$/m);
  assert.match(makefile, /^local-runtime-fixtures:\r?\n\tbash scripts\/bootstrap-local-fixtures\.sh --ensure$/m);
  assert.match(makefile, /^regenerate-local-runtime-fixtures:\r?\n\tbash scripts\/bootstrap-local-fixtures\.sh --force$/m);
  assert.doesNotMatch(makeTarget(makefile, "app-down"), /\b-v\b|--volumes/);

  for (const target of ["up", "up-exact-model-shadow", "ps", "down", "check-compose", "check-compose-exact-model-shadow"]) {
    const dryRun = spawnSync("make", ["-n", target], {
      cwd: repositoryRoot,
      encoding: "utf8"
    });
    assert.equal(dryRun.status, 0, `${target}: ${dryRun.stderr || dryRun.stdout}`);
    if (["up", "up-exact-model-shadow", "ps", "down", "check-compose", "check-compose-exact-model-shadow"].includes(target)) {
      assert.match(dryRun.stdout, /--project-name fraud-detection-platform/);
    }
  }
});

function runBootstrap(mode, environment) {
  const result = spawnSync(bashExecutable, [bootstrap, mode], {
    cwd: repositoryRoot,
    env: environment,
    encoding: "utf8"
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

function runBootstrapAsync(mode, environment) {
  return new Promise((resolve, reject) => {
    const child = spawn(bashExecutable, [bootstrap, mode], {
      cwd: repositoryRoot,
      env: environment,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let output = "";
    child.stdout.on("data", (chunk) => { output += chunk; });
    child.stderr.on("data", (chunk) => { output += chunk; });
    child.on("error", reject);
    child.on("close", (code) => code === 0 ? resolve() : reject(new Error(output)));
  });
}

function fixtureFingerprint(root) {
  const hash = crypto.createHash("sha256");
  for (const file of walk(root).sort()) {
    hash.update(path.relative(root, file).replaceAll("\\", "/"));
    hash.update(fs.readFileSync(file));
  }
  return hash.digest("hex");
}

function walk(root) {
  return fs.readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const candidate = path.join(root, entry.name);
    return entry.isDirectory() ? walk(candidate) : [candidate];
  });
}

function makeTarget(makefile, target) {
  const match = makefile.match(new RegExp(`^${target}:.*(?:\\r?\\n\\t.*)*`, "m"));
  assert.ok(match, `missing Make target ${target}`);
  return match[0];
}

function findBash() {
  if (process.platform !== "win32") return "bash";
  const candidates = [
    process.env.ProgramFiles && path.join(process.env.ProgramFiles, "Git", "bin", "bash.exe"),
    process.env["ProgramFiles(x86)"] && path.join(process.env["ProgramFiles(x86)"], "Git", "bin", "bash.exe"),
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, "Programs", "Git", "bin", "bash.exe")
  ].filter(Boolean);
  return candidates.find((candidate) => fs.existsSync(candidate));
}
