import { execFileSync } from "node:child_process";
import { generateKeyPairSync, createHash, sign as cryptoSign } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

function canonical(value) {
  if (value && typeof value === "object" && Object.hasOwn(value, "__rawJson")) {
    return value.__rawJson;
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonical).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function javaDouble(value) {
  const number = Number(value);
  return { __rawJson: Number.isInteger(number) ? `${number}.0` : JSON.stringify(number) };
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

function signedAnchor(keyPair, chainPosition, eventHash) {
  const anchor = {
    external_anchor_id: `external-${chainPosition}`,
    local_anchor_id: `local-${chainPosition}`,
    partition_key: "source_service:alert-service",
    chain_position: chainPosition,
    last_event_hash: eventHash,
    external_key: `local-file:anchors.jsonl#${chainPosition}`,
    external_hash: eventHash,
    external_immutability_level: "NONE",
    signature_status: "SIGNED",
    signing_key_id: "key-1",
    signing_algorithm: "Ed25519",
    signing_authority: "local-trust-authority",
    signed_at: "2026-04-29T12:00:00Z",
  };
  const payloadHash = sha256Hex(canonical({
    chain_position: anchor.chain_position,
    external_hash: anchor.external_hash,
    external_key: anchor.external_key,
    immutability_level: anchor.external_immutability_level,
    last_event_hash: anchor.last_event_hash,
    local_anchor_id: anchor.local_anchor_id,
    partition_key: anchor.partition_key,
  }));
  anchor.signed_payload_hash = payloadHash;
  anchor.signature = cryptoSign(null, Buffer.from(canonical({
    anchor_id: anchor.local_anchor_id,
    chain_position: anchor.chain_position,
    partition_key: anchor.partition_key,
    payload_hash: payloadHash,
    purpose: "AUDIT_ANCHOR",
  })), keyPair.privateKey).toString("base64");
  return anchor;
}

function bundle(events) {
  const anchorCoverage = {
    total_events: events.length,
    events_with_local_anchor: events.length,
    events_with_external_anchor: events.filter((event) => event.external_anchor).length,
    events_missing_external_anchor: events.filter((event) => !event.external_anchor).length,
    coverage_ratio: 1.0,
  };
  const result = {
    status: "AVAILABLE",
    count: events.length,
    limit: 20,
    source_service: "alert-service",
    from: "2026-04-29T00:00:00Z",
    to: "2026-04-30T00:00:00Z",
    external_anchor_status: "AVAILABLE",
    anchor_coverage: anchorCoverage,
    chain_range_start: events[0]?.chain_position ?? null,
    chain_range_end: events.at(-1)?.chain_position ?? null,
    predecessor_hash: events[0]?.chain_position > 1 ? events[0].previous_event_hash : null,
    partial_chain_range: events[0]?.chain_position > 1,
    events,
  };
  result.export_fingerprint = fingerprint(result);
  return result;
}

function fingerprint(result) {
  const events = result.events;
  return sha256Hex(canonical({
    anchor_coverage: {
      coverage_ratio: javaDouble(result.anchor_coverage.coverage_ratio),
      events_missing_external_anchor: result.anchor_coverage.events_missing_external_anchor,
      events_with_external_anchor: result.anchor_coverage.events_with_external_anchor,
      events_with_local_anchor: result.anchor_coverage.events_with_local_anchor,
      total_events: result.anchor_coverage.total_events,
    },
    chain_range: {
      chain_range_end: result.chain_range_end,
      chain_range_start: result.chain_range_start,
      partial_chain_range: result.partial_chain_range,
      predecessor_hash: result.predecessor_hash,
    },
    audit_event_ids: events.map((event) => event.audit_event_id),
    event_hashes: events.map((event) => event.event_hash),
    external_anchor_ids: events.map((event) => event.external_anchor?.external_anchor_id ?? null),
    local_anchor_ids: events.map((event) => event.local_anchor?.anchor_id ?? null),
    query: {
      from: result.from,
      limit: result.limit,
      source_service: result.source_service,
      to: result.to,
    },
  }));
}

function event(chainPosition, eventHash, previousEventHash, externalAnchor = null) {
  return {
    audit_event_id: `event-${chainPosition}`,
    event_hash: eventHash,
    previous_event_hash: previousEventHash,
    chain_position: chainPosition,
    local_anchor: {
      anchor_id: `local-${chainPosition}`,
      chain_position: chainPosition,
      last_event_hash: eventHash,
    },
    external_anchor: externalAnchor,
  };
}

function runVerifier(input, keys) {
  const dir = mkdtempSync(join(tmpdir(), "audit-verifier-"));
  const bundlePath = join(dir, "bundle.json");
  const keysPath = join(dir, "keys.json");
  writeFileSync(bundlePath, JSON.stringify(input), "utf8");
  writeFileSync(keysPath, JSON.stringify(keys), "utf8");
  return JSON.parse(execFileSync("node", ["tools/audit-verifier/audit-verifier.mjs", bundlePath, keysPath], {
    cwd: process.cwd(),
    encoding: "utf8",
  }));
}

function runStrictVerifier(input, keys) {
  const dir = mkdtempSync(join(tmpdir(), "audit-verifier-"));
  const bundlePath = join(dir, "bundle.json");
  const keysPath = join(dir, "keys.json");
  writeFileSync(bundlePath, JSON.stringify(input), "utf8");
  writeFileSync(keysPath, JSON.stringify(keys), "utf8");
  return JSON.parse(execFileSync("node", ["tools/audit-verifier/audit-verifier.mjs", "--mode", "STRICT", bundlePath, keysPath], {
    cwd: process.cwd(),
    encoding: "utf8",
  }));
}

test("valid continuous signed chain is independently verifiable", () => {
  const keyPair = generateKeyPairSync("ed25519");
  const keys = [{ key_id: "key-1", public_key: keyPair.publicKey.export({ format: "der", type: "spki" }).toString("base64") }];
  const first = signedAnchor(keyPair, 1, "hash-1");
  const second = signedAnchor(keyPair, 2, "hash-2");
  const result = runVerifier(bundle([
    event(1, "hash-1", null, first),
    event(2, "hash-2", "hash-1", second),
  ]), keys);

  assert.equal(result.status, "VALID");
  assert.equal(result.verified_trust_level, "INTERNAL_FULL");
});

test("chain gap is invalid", () => {
  const result = runVerifier(bundle([
    event(1, "hash-1", null),
    event(3, "hash-3", "hash-1"),
  ]), []);

  assert.equal(result.status, "INVALID");
  assert.equal(result.reason_code, "CHAIN_GAP");
});

test("previous hash mismatch is invalid", () => {
  const result = runVerifier(bundle([
    event(1, "hash-1", null),
    event(2, "hash-2", "wrong"),
  ]), []);

  assert.equal(result.status, "INVALID");
  assert.equal(result.reason_code, "PREVIOUS_HASH_MISMATCH");
});

test("duplicate chain position is invalid", () => {
  const result = runVerifier(bundle([
    event(1, "hash-1", null),
    event(1, "hash-2", "hash-1"),
  ]), []);

  assert.equal(result.status, "INVALID");
  assert.equal(result.reason_code, "DUPLICATE_CHAIN_POSITION");
});

test("partial range without boundary proof is not independently verifiable", () => {
  const input = {
    ...bundle([
      event(2, "hash-2", "hash-1"),
      event(3, "hash-3", "hash-2"),
    ]),
    predecessor_hash: null,
    partial_chain_range: true,
  };
  input.export_fingerprint = fingerprint(input);
  const result = runVerifier(input, []);

  assert.equal(result.status, "PARTIAL");
  assert.equal(result.reason_code, "BOUNDARY_PROOF_MISSING");
  assert.equal(result.verified_trust_level, "INTERNAL_PARTIAL");
});

test("partial range with boundary proof is downgraded to signed anchors verified", () => {
  const keyPair = generateKeyPairSync("ed25519");
  const keys = [{ key_id: "key-1", public_key: keyPair.publicKey.export({ format: "der", type: "spki" }).toString("base64") }];
  const input = bundle([
    event(2, "hash-2", "hash-1", signedAnchor(keyPair, 2, "hash-2")),
    event(3, "hash-3", "hash-2", signedAnchor(keyPair, 3, "hash-3")),
  ]);
  const result = runVerifier(input, keys);

  assert.equal(result.status, "PARTIAL");
  assert.equal(result.reason_code, "PARTIAL_CHAIN");
  assert.equal(result.verified_trust_level, "INTERNAL_PARTIAL");
});

test("expected total larger than bundle downgrades full prefix to partial chain", () => {
  const keyPair = generateKeyPairSync("ed25519");
  const keys = [{ key_id: "key-1", public_key: keyPair.publicKey.export({ format: "der", type: "spki" }).toString("base64") }];
  const input = {
    ...bundle([
      event(1, "hash-1", null, signedAnchor(keyPair, 1, "hash-1")),
    ]),
    expected_total_events: 2,
  };
  const result = runVerifier(input, keys);

  assert.equal(result.status, "PARTIAL");
  assert.equal(result.reason_code, "PARTIAL_CHAIN");
  assert.equal(result.verified_trust_level, "INTERNAL_PARTIAL");
});

test("strict mode rejects partial chain evidence", () => {
  const keyPair = generateKeyPairSync("ed25519");
  const keys = [{ key_id: "key-1", public_key: keyPair.publicKey.export({ format: "der", type: "spki" }).toString("base64") }];
  const input = bundle([
    event(2, "hash-2", "hash-1", signedAnchor(keyPair, 2, "hash-2")),
    event(3, "hash-3", "hash-2", signedAnchor(keyPair, 3, "hash-3")),
  ]);
  const result = runStrictVerifier(input, keys);

  assert.equal(result.mode, "STRICT");
  assert.equal(result.status, "INVALID");
  assert.equal(result.reason_code, "STRICT_REQUIRES_FULL_CHAIN");
  assert.equal(result.verified_trust_level, "INVALID");
});


test("expired signing key invalidates anchor", () => {
  const keyPair = generateKeyPairSync("ed25519");
  const keys = [{
    key_id: "key-1",
    public_key: keyPair.publicKey.export({ format: "der", type: "spki" }).toString("base64"),
    valid_until: "2026-04-01T00:00:00Z",
  }];
  const result = runVerifier(bundle([
    event(1, "hash-1", null, signedAnchor(keyPair, 1, "hash-1")),
  ]), keys);

  assert.equal(result.status, "INVALID");
  assert.equal(result.reason_code, "KEY_EXPIRED");
});
