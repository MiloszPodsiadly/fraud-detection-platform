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
    LOCAL_IDENTITY_FIXTURE_ROOT: shellPath(path.join(temporary, "identity")),
    LOCAL_GENERATED_FIXTURE_ROOT: shellPath(path.join(temporary, "generated"))
  };
  try {
    const artifactRootMarker = path.join(temporary, "generated", "model-shadow-performance", ".artifact-root-ready.labels");
    if (process.platform !== "win32") {
      fs.mkdirSync(path.dirname(artifactRootMarker), { recursive: true });
      fs.writeFileSync(artifactRootMarker, "malformed=true\n", { mode: 0o400 });
    }
    runBootstrap("--force", environment);
    if (process.platform !== "win32") {
      assert.equal(fs.readFileSync(artifactRootMarker, "utf8"), "com.frauddetection.artifact-root-ready=true\n");
    }
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

    const evaluationRoot = path.dirname(evaluationManifest);
    const evaluationCard = path.join(evaluationRoot, "platform_recommendation_evaluation_card.json");
    const invalidCard = JSON.parse(fs.readFileSync(evaluationCard, "utf8"));
    invalidCard.runtimeDecisionAuthority = "AUTOMATIC";
    fs.writeFileSync(evaluationCard, `${JSON.stringify(invalidCard, null, 2)}\n`, "utf8");
    refreshManifestEntry(evaluationManifest, evaluationCard);
    const evaluationRepair = runBootstrap("--ensure", environment);
    assert.match(evaluationRepair.stdout, /Existing local identity fixtures are valid/);
    assert.equal(fixtureFingerprint(identityRoot), originalIdentity);
    assert.equal(
      JSON.parse(fs.readFileSync(evaluationCard, "utf8")).runtimeDecisionAuthority,
      "NONE"
    );

    fs.writeFileSync(evaluationCard, `${" ".repeat(262_145)}\n`, "utf8");
    refreshManifestEntry(evaluationManifest, evaluationCard);
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

test("fixture bootstrap rejects a symlinked exact-model root before external mutation", (context) => {
  if (!bashExecutable || process.platform === "win32") {
    context.skip("Linux Bash symlink contract is unavailable");
    return;
  }
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "local-startup-symlink-"));
  try {
    const identityRoot = path.join(temporary, "identity");
    const generatedRoot = path.join(temporary, "generated");
    const external = path.join(temporary, "external");
    fs.mkdirSync(identityRoot, { recursive: true });
    fs.mkdirSync(generatedRoot, { recursive: true });
    fs.mkdirSync(external, { mode: 0o711 });
    fs.writeFileSync(path.join(identityRoot, "unchanged"), "identity\n", "utf8");
    fs.writeFileSync(path.join(external, "unchanged"), "external\n", "utf8");
    fs.symlinkSync(external, path.join(generatedRoot, "model-shadow-performance"), "dir");
    const identityBefore = fixtureFingerprint(identityRoot);
    const externalBefore = fixtureFingerprint(external);
    const externalMode = fs.statSync(external).mode;

    const result = spawnSync(bashExecutable, [bootstrap, "--ensure"], {
      cwd: repositoryRoot,
      env: {
        ...process.env,
        LOCAL_IDENTITY_FIXTURE_ROOT: identityRoot,
        LOCAL_GENERATED_FIXTURE_ROOT: generatedRoot
      },
      encoding: "utf8"
    });

    assert.notEqual(result.status, 0, "symlinked exact-model root unexpectedly passed");
    assert.equal(fixtureFingerprint(identityRoot), identityBefore);
    assert.equal(fixtureFingerprint(external), externalBefore);
    assert.equal(fs.statSync(external).mode, externalMode);
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
  assert.match(makefile, /^local-runtime-fixtures:\r?\n\tbash scripts\/bootstrap-local-fixtures\.sh --ensure\r?\n\tnode scripts\/bootstrap-local-model-promotion-keys\.mjs --ensure\r?\n\tnode scripts\/bootstrap-local-audit-trust-key\.mjs --ensure$/m);
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
  return result;
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

function refreshManifestEntry(manifestPath, artifactPath) {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const bytes = fs.readFileSync(artifactPath);
  const entry = manifest.files.find(({ name }) => name === path.basename(artifactPath));
  assert.ok(entry, `missing manifest entry for ${path.basename(artifactPath)}`);
  entry.sizeBytes = bytes.length;
  entry.sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
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

function shellPath(value) {
  if (process.platform !== "win32") return value;
  return value.replace(/^([A-Za-z]):\\/, (_, drive) => `/${drive.toLowerCase()}/`).replaceAll("\\", "/");
}
