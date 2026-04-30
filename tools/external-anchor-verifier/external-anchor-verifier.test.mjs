import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

function canonical(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonical).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

function anchorId(anchor) {
  return sha256Hex([
    anchor.source,
    anchor.schema_version,
    anchor.partition_key,
    anchor.chain_position,
    anchor.event_hash,
  ].join(":"));
}

function anchor(overrides = {}) {
  const base = {
    anchor_id: null,
    anchor_id_version: 1,
    source: "object-store",
    local_anchor_id: "local-1",
    partition_key: "source_service:alert-service",
    external_object_key: "audit-anchors/source/00000000000000000001.json",
    chain_position: 1,
    event_hash: "hash-1",
    previous_event_hash: null,
    payload_hash: null,
    hash_algorithm: "SHA-256",
    schema_version: "1.0",
    created_at: "2026-04-30T08:00:00Z",
    published_at_local: "2026-04-30T08:00:00Z",
    sink_type: "object-store",
    publication_status: "PENDING",
    publication_reason: null,
    manifest_status: null,
    ...overrides,
  };
  base.anchor_id = base.anchor_id ?? anchorId(base);
  base.payload_hash = base.payload_hash ?? sha256Hex(canonical({ ...base, payload_hash: null }));
  return base;
}

function run(files) {
  const dir = mkdtempSync(join(tmpdir(), "external-anchor-verifier-"));
  const paths = {};
  for (const [name, content] of Object.entries(files)) {
    paths[name] = join(dir, `${name}.json`);
    writeFileSync(paths[name], JSON.stringify(content), "utf8");
  }
  const args = ["tools/external-anchor-verifier/external-anchor-verifier.mjs", "--anchor", paths.anchor];
  if (paths.bundle) {
    args.push("--bundle", paths.bundle);
  }
  if (paths.reference) {
    args.push("--reference", paths.reference);
  }
  if (paths.witness) {
    args.push("--witness", paths.witness);
  }
  return JSON.parse(execFileSync("node", args, { cwd: process.cwd(), encoding: "utf8" }));
}

test("valid anchor with verified storage timestamp is valid", () => {
  const result = run({
    anchor: anchor(),
    reference: {
      external_key: "audit-anchors/source/00000000000000000001.json",
      external_hash: "hash-1",
      timestamp_type: "STORAGE_OBSERVED",
      timestamp_trust_level: "MEDIUM",
      timestamp_verified: true,
    },
  });

  assert.equal(result.status, "VALID");
  assert.equal(result.reason_code, null);
});

test("missing anchor file is explicit missing", () => {
  const dir = mkdtempSync(join(tmpdir(), "external-anchor-verifier-"));
  const missing = join(dir, "missing.json");
  const output = execFileSync("node", [
    "tools/external-anchor-verifier/external-anchor-verifier.mjs",
    "--anchor",
    missing,
  ], { cwd: process.cwd(), encoding: "utf8" });

  const result = JSON.parse(output);
  assert.equal(result.status, "MISSING");
  assert.equal(result.reason_code, "ANCHOR_OBJECT_MISSING");
});

test("tampered payload hash is invalid", () => {
  const result = run({ anchor: { ...anchor(), event_hash: "tampered" } });

  assert.equal(result.status, "INVALID");
  assert.equal(result.reason_code, "ANCHOR_ID_MISMATCH");
});

test("reference conflict is not silently resolved", () => {
  const result = run({
    anchor: anchor(),
    reference: {
      external_key: "audit-anchors/source/00000000000000000001.json",
      external_hash: "other",
      timestamp_type: "STORAGE_OBSERVED",
      timestamp_trust_level: "MEDIUM",
      timestamp_verified: true,
    },
  });

  assert.equal(result.status, "CONFLICT");
  assert.equal(result.reason_code, "REFERENCE_HASH_CONFLICT");
});

test("app observed timestamp downgrades to unverified", () => {
  const result = run({
    anchor: anchor(),
    reference: {
      external_key: "audit-anchors/source/00000000000000000001.json",
      external_hash: "hash-1",
      timestamp_type: "APP_OBSERVED",
      timestamp_trust_level: "WEAK",
      timestamp_verified: false,
    },
  });

  assert.equal(result.status, "UNVERIFIED");
  assert.equal(result.reason_code, "TIMESTAMP_NOT_INDEPENDENTLY_VERIFIED");
});

test("anchor behind local bundle head is stale", () => {
  const result = run({
    anchor: anchor(),
    bundle: {
      chain_range_end: 2,
      events: [],
    },
  });

  assert.equal(result.status, "STALE");
  assert.equal(result.reason_code, "ANCHOR_BEHIND_LOCAL_HEAD");
});
