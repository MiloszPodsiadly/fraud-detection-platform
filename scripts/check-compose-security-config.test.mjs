import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const checker = fileURLToPath(new URL("./check-compose-security-config.mjs", import.meta.url));
const target = "/run/model-shadow-performance";

test("accepts the canonical declarative and resolved exact-model mount", () => {
  withArtifactRoot((root) => {
    assertPasses("model-shadow-performance-source", canonicalConfig(root), root);
    assertPasses("model-shadow-performance", canonicalConfig(root), root, true);
  });
});

test("rejects unsafe declarative mount mutations", () => {
  withArtifactRoot((root) => {
    const mutations = [
      ["writable mount", (config) => { config.services["alert-service"].volumes[0].read_only = false; }],
      ["short syntax", (config) => { config.services["alert-service"].volumes = [`${root}:${target}:ro`]; }],
      ["host path creation", (config) => { config.services["alert-service"].volumes[0].bind.create_host_path = true; }],
      ["missing source", (config) => { delete config.services["alert-service"].volumes[0].source; }],
      ["wrong target", (config) => { config.services["alert-service"].volumes[0].target = "/run/wrong"; }]
    ];
    for (const [label, mutate] of mutations) {
      const config = canonicalConfig(root);
      mutate(config);
      assertFails("model-shadow-performance-source", config, root, false, label);
    }
  });
});

test("rejects resolved conflicts and environment drift", () => {
  withArtifactRoot((root) => {
    const mutations = [
      ["wrong container path", (config) => {
        config.services["alert-service"].environment.MODEL_SHADOW_PERFORMANCE_ARTIFACTS_BASE_DIR = "/run/wrong";
      }],
      ["duplicate target", (config) => {
        config.services["alert-service"].volumes.push({ ...config.services["alert-service"].volumes[0] });
      }],
      ["writable parent", (config) => {
        config.services["alert-service"].volumes.push({ type: "bind", source: root, target: "/run", read_only: false });
      }],
      ["writable child", (config) => {
        config.services["alert-service"].volumes.push({ type: "bind", source: root, target: `${target}/child`, read_only: false });
      }],
      ["writable governance mount", (config) => {
        config.services["alert-service"].volumes.push({ type: "bind", source: root, target: "/run/model-card", read_only: false });
      }]
    ];
    for (const [label, mutate] of mutations) {
      const config = canonicalConfig(root);
      mutate(config);
      assertFails("model-shadow-performance", config, root, false, label);
    }
  });
});

test("rejects missing and symbolic-link artifact roots", (context) => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "model-shadow-security-"));
  try {
    const missing = path.join(temporary, "missing");
    assertFails("model-shadow-performance", canonicalConfig(missing), missing, true, "missing root");

    const real = path.join(temporary, "real");
    const linked = path.join(temporary, "linked");
    fs.mkdirSync(real);
    try {
      fs.symlinkSync(real, linked, process.platform === "win32" ? "junction" : "dir");
    } catch (error) {
      context.skip(`symbolic links unavailable: ${error.message}`);
      return;
    }
    assertFails("model-shadow-performance", canonicalConfig(linked), linked, true, "symbolic-link root");
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
        volumes: [{
          type: "bind",
          source,
          target,
          read_only: true,
          bind: { create_host_path: false }
        }]
      }
    }
  };
}

function assertPasses(variant, config, source, requireExisting = false) {
  const result = run(variant, config, source, requireExisting);
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

function assertFails(variant, config, source, requireExisting, label) {
  const result = run(variant, config, source, requireExisting);
  assert.notEqual(result.status, 0, `${label} unexpectedly passed`);
}

function run(variant, config, source, requireExisting) {
  const args = [checker, variant];
  if (requireExisting) args.push("--require-existing-source");
  return spawnSync(process.execPath, args, {
    input: JSON.stringify(config),
    encoding: "utf8",
    env: { ...process.env, MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT: source }
  });
}

function withArtifactRoot(callback) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "model-shadow-artifacts-"));
  try {
    callback(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}
