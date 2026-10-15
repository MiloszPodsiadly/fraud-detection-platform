import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const checker = path.join(repositoryRoot, "scripts", "check-compose-security-config.mjs");
const sourceOverlay = path.join(
  repositoryRoot,
  "deployment",
  "docker-compose.model-shadow-performance-generated.yml"
);
const target = "/run/model-shadow-performance";
const sourceExpression = "${MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT:-./local-generated/model-shadow-performance}";
const canonicalSource = fs.readFileSync(sourceOverlay, "utf8").replaceAll("\r\n", "\n");
const canonicalMount = [
  "      - type: bind",
  `        source: ${sourceExpression}`,
  `        target: ${target}`,
  "        read_only: true",
  "        bind:",
  "          create_host_path: false"
].join("\n");

test("validates the actual source YAML and actual resolved Compose output", () => {
  assertSourcePasses(sourceOverlay);
  const compose = resolveActualCompose("deployment/.env.example");
  assert.equal(compose.status, 0, compose.stderr || compose.error?.message);
  assertResolvedPasses(JSON.parse(compose.stdout));
});

test("uses Compose interpolation as the authority for default, env-file, and shell roots", () => {
  const temporary = fs.mkdtempSync(path.join(repositoryRoot, "deployment", ".compose-env-test-"));
  try {
    const envFile = path.join(temporary, "custom.env");
    const envRoot = path.join(temporary, "env root");
    const shellRoot = path.join(temporary, "shell root");
    fs.writeFileSync(envFile, `MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT=${envRoot}\n`, "utf8");

    const fromEnvFile = resolveActualCompose(envFile);
    assert.equal(fromEnvFile.status, 0, fromEnvFile.stderr || fromEnvFile.error?.message);
    const envConfig = JSON.parse(fromEnvFile.stdout);
    assert.equal(exactModelMount(envConfig).source, envRoot);
    assertResolvedPasses(envConfig);

    const fromShell = resolveActualCompose(envFile, {
      MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT: shellRoot
    });
    assert.equal(fromShell.status, 0, fromShell.stderr || fromShell.error?.message);
    const shellConfig = JSON.parse(fromShell.stdout);
    assert.equal(exactModelMount(shellConfig).source, shellRoot);
    assertResolvedPasses(shellConfig);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test("rejects unsafe mutations of the actual source declaration", () => {
  const mutations = [
    ["writable mount", canonicalSource.replace("read_only: true", "read_only: false")],
    ["missing read_only", canonicalSource.replace("        read_only: true\n", "")],
    ["host path creation", canonicalSource.replace("create_host_path: false", "create_host_path: true")],
    ["short syntax", canonicalSource.replace(canonicalMount, `      - ${sourceExpression}:${target}:ro`)],
    ["wrong source", canonicalSource.replace(sourceExpression, "./local-generated/wrong")],
    ["wrong target", canonicalSource.replace(`target: ${target}`, "target: /run/wrong")],
    ["duplicate target", canonicalSource.replace(canonicalMount, `${canonicalMount}\n${canonicalMount}`)]
  ];
  for (const [label, source] of mutations) {
    withSourceOverlay(source, (file) => assertSourceFails(file, label));
  }
});

test("rejects resolved exact-model mount and runtime environment drift", () => {
  withArtifactRoot((root) => {
    const mutations = [
      ["writable mount", (config) => { config.services["alert-service"].volumes[0].read_only = false; }],
      ["missing read_only", (config) => { delete config.services["alert-service"].volumes[0].read_only; }],
      ["wrong container path", (config) => {
        config.services["alert-service"].environment.MODEL_SHADOW_PERFORMANCE_ARTIFACTS_BASE_DIR = "/run/wrong";
      }],
      ["wrong target", (config) => {
        config.services["alert-service"].volumes[0].target = "/run/wrong";
      }],
      ["duplicate target", (config) => {
        config.services["alert-service"].volumes.push({ ...config.services["alert-service"].volumes[0] });
      }],
      ["writable filesystem root", (config) => addMount(config, root, "/", false)],
      ["writable parent", (config) => addMount(config, root, "/run", false)],
      ["writable exact child", (config) => addMount(config, root, `${target}/extra`, false)],
      ["writable sibling governance root", (config) => addMount(config, root, "/run/model-card", false)],
      ["writable model-card child", (config) => addMount(config, root, "/run/model-card/extra", false)],
      ["writable platform-shadow child", (config) => addMount(config, root, "/run/shadow-performance/extra", false)],
      ["writable promotion child", (config) => addMount(config, root, "/run/promotion-readiness/extra", false)],
      ["overlapping tmpfs", (config) => { config.services["alert-service"].tmpfs = ["/run/model-card/extra"]; }],
      ["filesystem-root tmpfs", (config) => { config.services["alert-service"].tmpfs = ["/"]; }]
    ];
    for (const [label, mutate] of mutations) {
      const config = canonicalConfig(root);
      mutate(config);
      assertResolvedFails(config, false, label);
    }
  });
});

test("accepts safe read-only governance and non-overlapping writable mounts", () => {
  withArtifactRoot((root) => {
    const config = canonicalConfig(root);
    addMount(config, root, "/run/model-card/extra", true);
    addMount(config, root, "/run/model-card-extra", false);
    addMount(config, root, "/var/tmp/application-cache", false);
    assertResolvedPasses(config, true);
  });
});

test("rejects missing and symbolic-link artifact roots", (context) => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "model-shadow-security-"));
  try {
    const missing = path.join(temporary, "missing");
    assertResolvedFails(canonicalConfig(missing), true, "missing root");

    const real = path.join(temporary, "real");
    const linked = path.join(temporary, "linked");
    fs.mkdirSync(real);
    try {
      fs.symlinkSync(real, linked, process.platform === "win32" ? "junction" : "dir");
    } catch (error) {
      context.skip(`symbolic links unavailable: ${error.message}`);
      return;
    }
    assertResolvedFails(canonicalConfig(linked), true, "symbolic-link root");

    const realParent = path.join(temporary, "real-parent");
    const linkedParent = path.join(temporary, "linked-parent");
    fs.mkdirSync(path.join(realParent, "artifacts"), { recursive: true });
    try {
      fs.symlinkSync(realParent, linkedParent, process.platform === "win32" ? "junction" : "dir");
    } catch (error) {
      context.skip(`symbolic links unavailable: ${error.message}`);
      return;
    }
    assertResolvedFails(
      canonicalConfig(path.join(linkedParent, "artifacts")),
      true,
      "symbolic-link ancestor"
    );
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

function canonicalConfig(source) {
  return {
    services: {
      "alert-service": {
        environment: {
          MODEL_SHADOW_PERFORMANCE_ARTIFACTS_ENABLED: "true",
          MODEL_SHADOW_PERFORMANCE_ARTIFACTS_BASE_DIR: target
        },
        volumes: [{ type: "bind", source, target, read_only: true }]
      }
    }
  };
}

function addMount(config, source, mountTarget, readOnly) {
  config.services["alert-service"].volumes.push({
    type: "bind",
    source,
    target: mountTarget,
    read_only: readOnly
  });
}

function assertSourcePasses(file) {
  const result = runSource(file);
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

function assertSourceFails(file, label) {
  const result = runSource(file);
  assert.notEqual(result.status, 0, `${label} unexpectedly passed`);
}

function runSource(file) {
  return spawnSync(process.execPath, [
    checker,
    "model-shadow-performance-source",
    "--source-file", file
  ], {
    cwd: repositoryRoot,
    encoding: "utf8"
  });
}

function assertResolvedPasses(config, requireExisting = false) {
  const result = runResolved(config, requireExisting);
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

function assertResolvedFails(config, requireExisting, label) {
  const result = runResolved(config, requireExisting);
  assert.notEqual(result.status, 0, `${label} unexpectedly passed`);
}

function runResolved(config, requireExisting) {
  const args = [checker, "model-shadow-performance"];
  if (requireExisting) args.push("--require-existing-source");
  return spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    input: JSON.stringify(config),
    encoding: "utf8"
  });
}

function resolveActualCompose(envFile, environment = {}) {
  return spawnSync("docker", [
    "compose",
    "--env-file", envFile,
    "-f", "deployment/docker-compose.yml",
    "-f", "deployment/docker-compose.model-shadow-performance-generated.yml",
    "config", "--format", "json"
  ], {
    cwd: repositoryRoot,
    encoding: "utf8",
    env: { ...process.env, ...environment }
  });
}

function exactModelMount(config) {
  return config.services["alert-service"].volumes.find((volume) => volume.target === target);
}

function withSourceOverlay(source, callback) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "model-shadow-source-"));
  try {
    const file = path.join(temporary, "compose.yml");
    fs.writeFileSync(file, source, "utf8");
    callback(file);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

function withArtifactRoot(callback) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "model-shadow-artifacts-"));
  try {
    callback(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}
