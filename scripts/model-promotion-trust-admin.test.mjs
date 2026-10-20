import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const script = join(dirname(fileURLToPath(import.meta.url)), "model-promotion-trust-admin.mjs");
const run = (...args) => spawnSync(process.execPath, [script, ...args], { encoding: "utf8" });

test("operator creates a key, verifies fingerprint, rotates and revokes without overwriting history", () => {
  const directory = mkdtempSync(join(tmpdir(), "fdp-trust-admin-"));
  try {
    const privatePath = join(directory, "author.private");
    const publicPath = join(directory, "author.public");
    const original = join(directory, "approval-1.json");
    const rotated = join(directory, "approval-2.json");
    const revoked = join(directory, "approval-3.json");
    const generated = run("keygen", "--private", privatePath, "--public", publicPath);
    assert.equal(generated.status, 0, generated.stderr);
    assert.equal(run("keygen", "--private", privatePath, "--public", publicPath).status, 1);
    const fingerprint = generated.stdout.trim().split(" ").at(-1);
    assert.equal(run("fingerprint", "--public", publicPath, "--expected", fingerprint).status, 0);
    assert.equal(run("fingerprint", "--public", publicPath, "--expected", "0".repeat(64)).status, 1);
    writeFileSync(original, JSON.stringify({ version: "model-promotion-approval-trust-v1", generation: 1,
      keys: [{ keyId: "old-key", identity: "old-author", status: "ACTIVE",
        validFrom: "2025-01-01T00:00:00Z", validUntil: "2027-01-01T00:00:00Z" }] }));
    const added = run("add-key", "--kind", "approval", "--registry", original,
      "--expected-generation", "1", "--out", rotated, "--public", publicPath,
      "--identity", "new-author", "--key-id", "new-key", "--role", "POLICY_AUTHOR",
      "--environments", "TEST,PRODUCTION", "--valid-from", "2025-01-01T00:00:00Z",
      "--valid-until", "2027-01-01T00:00:00Z");
    assert.equal(added.status, 0, added.stderr);
    const second = JSON.parse(readFileSync(rotated, "utf8"));
    assert.equal(second.generation, 2);
    assert.equal(second.keys[1].publicKeySha256, fingerprint);
    assert.deepEqual(second.keys[1].roles, ["POLICY_AUTHOR"]);
    assert.equal(run("add-key", "--kind", "approval", "--registry", rotated,
      "--expected-generation", "2", "--out", join(directory, "reused-public.json"), "--public", publicPath,
      "--identity", "alias-author", "--key-id", "alias-key", "--role", "POLICY_AUTHOR",
      "--environments", "PRODUCTION", "--valid-from", "2025-01-01T00:00:00Z",
      "--valid-until", "2027-01-01T00:00:00Z").status, 1);
    assert.equal(run("add-key", "--kind", "approval", "--registry", original,
      "--expected-generation", "2", "--out", join(directory, "bad.json"), "--public", publicPath,
      "--identity", "other", "--key-id", "other", "--role", "POLICY_AUTHOR",
      "--environments", "TEST", "--valid-from", "2025-01-01T00:00:00Z",
      "--valid-until", "2027-01-01T00:00:00Z").status, 1);
    assert.equal(run("set-status", "--kind", "approval", "--registry", rotated,
      "--expected-generation", "2", "--out", revoked, "--key-id", "old-key",
      "--status", "REVOKED").status, 0);
    assert.equal(JSON.parse(readFileSync(revoked, "utf8")).keys[0].status, "REVOKED");
    assert.equal(JSON.parse(readFileSync(original, "utf8")).keys[0].status, "ACTIVE");
    assert.equal(run("set-status", "--kind", "approval", "--registry", revoked,
      "--expected-generation", "3", "--out", join(directory, "restored.json"),
      "--key-id", "old-key", "--status", "ACTIVE").status, 1);
    assert.equal(run("set-status", "--kind", "approval", "--registry", rotated,
      "--expected-generation", "2", "--out", revoked, "--key-id", "old-key",
      "--status", "REVOKED").status, 1);
    assert.equal(existsSync(privatePath), true);
  } finally {
    if (!resolve(directory).startsWith(`${resolve(tmpdir())}${sep}`)) throw new Error("Unsafe test cleanup");
    rmSync(directory, { recursive: true, force: true });
  }
});

test("new issuer registry stages at generation zero and retirement cannot restore authorization", () => {
  const directory = mkdtempSync(join(tmpdir(), "fdp-issuer-admin-"));
  try {
    const privatePath = join(directory, "issuer.private");
    const publicPath = join(directory, "issuer.public");
    const initial = join(directory, "issuer-0.json");
    const active = join(directory, "issuer-1.json");
    const retired = join(directory, "issuer-2.json");
    assert.equal(run("keygen", "--private", privatePath, "--public", publicPath).status, 0);
    assert.equal(run("init-registry", "--kind", "issuer", "--out", initial).status, 0);
    assert.equal(run("add-key", "--kind", "issuer", "--registry", initial,
      "--expected-generation", "0", "--out", active, "--public", publicPath,
      "--identity", "institutional-issuer", "--key-id", "issuer-key-1",
      "--environments", "PRODUCTION", "--valid-from", "2025-01-01T00:00:00Z",
      "--valid-until", "2027-01-01T00:00:00Z").status, 0);
    const activeData = JSON.parse(readFileSync(active, "utf8"));
    assert.equal(activeData.generation, 1);
    assert.equal(activeData.keys[0].issuerIdentity, "institutional-issuer");
    const retiredAt = new Date(Math.floor((Date.now() - 60_000) / 1000) * 1000)
      .toISOString().replace(".000Z", "Z");
    assert.equal(run("set-status", "--kind", "issuer", "--registry", active,
      "--expected-generation", "1", "--out", retired, "--key-id", "issuer-key-1",
      "--status", "RETIRED", "--valid-until", retiredAt).status, 0);
    assert.equal(JSON.parse(readFileSync(retired, "utf8")).keys[0].validUntil, retiredAt);
    assert.equal(run("set-status", "--kind", "issuer", "--registry", retired,
      "--expected-generation", "2", "--out", join(directory, "restored.json"),
      "--key-id", "issuer-key-1", "--status", "ACTIVE").status, 1);
  } finally {
    if (!resolve(directory).startsWith(`${resolve(tmpdir())}${sep}`)) throw new Error("Unsafe test cleanup");
    rmSync(directory, { recursive: true, force: true });
  }
});
