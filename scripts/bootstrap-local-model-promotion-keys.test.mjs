import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
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
      "online-actor-trust.json", "publisher.private-key.pkcs8.base64", "trusted-approvals.json"
    ]);
    const trust = JSON.parse(readFileSync(join(approval, "trusted-approvals.json"), "utf8"));
    assert.deepEqual(trust.keys.map(key => key.roles[0]).sort(), [
      "POLICY_APPROVER", "POLICY_AUTHOR", "POLICY_ISSUER", "POLICY_PUBLISHER"
    ]);
    assert.ok(existsSync(join(root, "private-key.pkcs8.base64")));
    assert.deepEqual(readdirSync(root).sort(), ["approval", "fleet-observation",
      "private-key.pkcs8.base64", "trusted-issuers.json"]);
    const reports = join(root, "fleet-observation");
    assert.deepEqual(readdirSync(reports).sort(), ["alert.private-key.pkcs8.base64",
      "publisher.private-key.pkcs8.base64", "trusted-reporters.json"]);
    const reporters = JSON.parse(readFileSync(join(reports, "trusted-reporters.json"), "utf8"));
    assert.deepEqual(reporters.keys.map(key => `${key.role}/${key.instanceId}`).sort(), [
      "ALERT_VERIFIER/alert-service", "PUBLISHER/model-promotion-policy-publisher"
    ]);
    writeFileSync(join(approval, "publisher.private-key.pkcs8.base64"), "malformed\n");
    const malformed = spawnSync(process.execPath, [copied, "--check"], { encoding: "utf8" });
    assert.notEqual(malformed.status, 0, "malformed signing key must fail closed");
  } finally {
    const resolved = resolve(temporary);
    if (!resolved.startsWith(`${resolve(tmpdir())}${process.platform === "win32" ? "\\" : "/"}`)) {
      throw new Error("Refusing cleanup outside OS temp");
    }
    rmSync(temporary, { recursive: true, force: true });
  }
});
