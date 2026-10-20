import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

test("fresh local checkout gets separate credentials, never an approved policy", () => {
  const temporary = mkdtempSync(join(tmpdir(), "fdp-model-promotion-bootstrap-"));
  try {
    const scripts = join(temporary, "scripts");
    mkdirSync(scripts);
    const source = join(dirname(fileURLToPath(import.meta.url)), "bootstrap-local-model-promotion-keys.mjs");
    const copied = join(scripts, "bootstrap-local-model-promotion-keys.mjs");
    copyFileSync(source, copied);
    for (const mode of ["--ensure", "--check", "--ensure"]) {
      const result = spawnSync(process.execPath, [copied, mode], { encoding: "utf8" });
      assert.equal(result.status, 0, result.stderr);
    }
    const root = join(temporary, "deployment", ".local", "model-promotion-policy");
    const approval = join(root, "approval");
    assert.deepEqual(readdirSync(approval).sort(), [
      "approver-b.private-key.pkcs8.base64", "author-a.private-key.pkcs8.base64",
      "publisher.private-key.pkcs8.base64", "trusted-approvals.json"
    ]);
    const trust = JSON.parse(readFileSync(join(approval, "trusted-approvals.json"), "utf8"));
    assert.deepEqual(trust.keys.map(key => key.roles[0]).sort(), [
      "POLICY_APPROVER", "POLICY_AUTHOR", "POLICY_ISSUER", "POLICY_PUBLISHER"
    ]);
    assert.ok(existsSync(join(root, "private-key.pkcs8.base64")));
    assert.deepEqual(readdirSync(root).sort(), ["approval", "private-key.pkcs8.base64", "trusted-issuers.json"]);
  } finally {
    const resolved = resolve(temporary);
    if (!resolved.startsWith(`${resolve(tmpdir())}${process.platform === "win32" ? "\\" : "/"}`)) {
      throw new Error("Refusing cleanup outside OS temp");
    }
    rmSync(temporary, { recursive: true, force: true });
  }
});
