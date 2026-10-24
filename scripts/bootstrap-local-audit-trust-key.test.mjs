import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdtempSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

test("audit trust bootstrap is stable and rejects partial or mismatched key pairs", () => {
  const temporary = mkdtempSync(join(tmpdir(), "audit-trust-bootstrap-"));
  try {
    const scripts = join(temporary, "scripts");
    mkdirSync(scripts);
    const copied = join(scripts, "bootstrap-local-audit-trust-key.mjs");
    copyFileSync(join(dirname(fileURLToPath(import.meta.url)),
      "bootstrap-local-audit-trust-key.mjs"), copied);
    for (const mode of ["--ensure", "--check", "--ensure"]) {
      const result = spawnSync(process.execPath, [copied, mode], { encoding: "utf8" });
      assert.equal(result.status, 0, result.stderr);
    }
    const keys = join(temporary, "deployment", ".local", "audit-trust-authority");
    const privatePath = join(keys, "ed25519-private.key");
    const publicPath = join(keys, "ed25519-public.key");
    const originalPrivate = readFileSync(privatePath, "utf8");
    writeFileSync(publicPath, Buffer.alloc(44, 1).toString("base64"));
    const mismatched = spawnSync(process.execPath, [copied, "--check"], { encoding: "utf8" });
    assert.notEqual(mismatched.status, 0, "mismatched key pair must fail closed");
    unlinkSync(publicPath);
    const partial = spawnSync(process.execPath, [copied, "--ensure"], { encoding: "utf8" });
    assert.notEqual(partial.status, 0, "partial key pair must not be replaced");
    assert.equal(readFileSync(privatePath, "utf8"), originalPrivate);
  } finally {
    const resolved = resolve(temporary);
    if (!resolved.startsWith(`${resolve(tmpdir())}${process.platform === "win32" ? "\\" : "/"}`)) {
      throw new Error("Refusing cleanup outside OS temp");
    }
    rmSync(temporary, { recursive: true, force: true });
  }
});
