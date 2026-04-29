#!/usr/bin/env node
import { createHash, createPublicKey, verify as cryptoVerify } from "node:crypto";
import { readFileSync } from "node:fs";

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
  if (!Number.isFinite(number)) {
    return value;
  }
  return { __rawJson: Number.isInteger(number) ? `${number}.0` : JSON.stringify(number) };
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

function keyMap(bundle, keyFile) {
  const keys = new Map();
  for (const key of bundle.public_keys ?? bundle.keys ?? []) {
    if (key.key_id && key.public_key) {
      keys.set(key.key_id, key.public_key);
    }
  }
  if (keyFile) {
    const external = JSON.parse(readFileSync(keyFile, "utf8"));
    for (const key of Array.isArray(external) ? external : [external]) {
      if (key.key_id && key.public_key) {
        keys.set(key.key_id, key.public_key);
      }
    }
  }
  return keys;
}

function signingPayload(anchor) {
  return {
    chain_position: anchor.chain_position,
    external_hash: anchor.external_hash,
    external_key: anchor.external_key,
    immutability_level: anchor.immutability_level ?? anchor.external_immutability_level ?? "NONE",
    last_event_hash: anchor.last_event_hash ?? anchor.anchor_hash,
    local_anchor_id: anchor.local_anchor_id ?? anchor.anchor_id,
    partition_key: anchor.partition_key,
  };
}

function trustAuthorityPayload(anchor, payloadHash) {
  return {
    anchor_id: anchor.local_anchor_id ?? anchor.anchor_id,
    chain_position: anchor.chain_position,
    partition_key: anchor.partition_key,
    payload_hash: payloadHash,
    purpose: "AUDIT_ANCHOR",
  };
}

function verifyAnchor(anchor, keys) {
  if (!anchor.partition_key || !anchor.external_key || !anchor.external_hash || !anchor.chain_position) {
    return { status: "INVALID", reason_code: "ANCHOR_VERIFICATION_FIELDS_MISSING" };
  }
  if (anchor.signature_status !== "SIGNED") {
    return { status: "PARTIAL", reason_code: anchor.signature_status ?? "UNSIGNED" };
  }
  if (!anchor.signing_key_id || !keys.has(anchor.signing_key_id)) {
    return { status: "INVALID", reason_code: "UNKNOWN_KEY" };
  }
  const payloadHash = sha256Hex(canonical(signingPayload(anchor)));
  if (anchor.signed_payload_hash && anchor.signed_payload_hash !== payloadHash) {
    return { status: "INVALID", reason_code: "SIGNED_PAYLOAD_HASH_MISMATCH" };
  }
  const publicKey = createPublicKey({
    key: Buffer.from(keys.get(anchor.signing_key_id), "base64"),
    format: "der",
    type: "spki",
  });
  const valid = cryptoVerify(
    null,
    Buffer.from(canonical(trustAuthorityPayload(anchor, payloadHash))),
    publicKey,
    Buffer.from(anchor.signature, "base64"),
  );
  return valid ? { status: "VALID", reason_code: null } : { status: "INVALID", reason_code: "SIGNATURE_INVALID" };
}

function verifyBundleFingerprint(bundle) {
  if (!bundle.export_fingerprint) {
    return { status: "PARTIAL", reason_code: "EXPORT_FINGERPRINT_MISSING" };
  }
  const events = bundle.events ?? [];
  const anchorCoverage = bundle.anchor_coverage ?? {};
  const canonicalBundle = {
    anchor_coverage: {
      coverage_ratio: javaDouble(anchorCoverage.coverage_ratio),
      events_missing_external_anchor: anchorCoverage.events_missing_external_anchor,
      events_with_external_anchor: anchorCoverage.events_with_external_anchor,
      events_with_local_anchor: anchorCoverage.events_with_local_anchor,
      total_events: anchorCoverage.total_events,
    },
    audit_event_ids: events.map((event) => event.audit_event_id),
    event_hashes: events.map((event) => event.event_hash),
    external_anchor_ids: events.map((event) => event.external_anchor?.external_anchor_id ?? null),
    local_anchor_ids: events.map((event) => event.local_anchor?.anchor_id ?? null),
    query: {
      from: bundle.from,
      limit: bundle.limit,
      source_service: bundle.source_service,
      to: bundle.to,
    },
  };
  const actual = sha256Hex(canonical(canonicalBundle));
  return actual === bundle.export_fingerprint
    ? { status: "VALID", reason_code: null }
    : { status: "INVALID", reason_code: "EXPORT_FINGERPRINT_MISMATCH" };
}

function verifyAnchorBindings(bundle) {
  for (const event of bundle.events ?? []) {
    if (event.local_anchor) {
      if (event.local_anchor.chain_position !== event.chain_position
          || event.local_anchor.last_event_hash !== event.event_hash) {
        return { status: "INVALID", reason_code: "LOCAL_ANCHOR_BINDING_MISMATCH" };
      }
    }
    if (event.external_anchor && event.local_anchor) {
      if (event.external_anchor.chain_position !== event.chain_position
          || event.external_anchor.local_anchor_id !== event.local_anchor.anchor_id) {
        return { status: "INVALID", reason_code: "EXTERNAL_ANCHOR_BINDING_MISMATCH" };
      }
    }
  }
  return { status: "VALID", reason_code: null };
}

function anchors(bundle) {
  const fromEvents = (bundle.events ?? [])
    .map((event) => event.external_anchor)
    .filter(Boolean);
  const latest = bundle.latest_external_anchor_reference ? [bundle.latest_external_anchor_reference] : [];
  return [...fromEvents, ...latest];
}

const [bundlePath, keyFile] = process.argv.slice(2);
if (!bundlePath) {
  console.error("usage: node tools/audit-verifier/audit-verifier.mjs audit-evidence-bundle-v1.json [public-keys.json]");
  process.exit(2);
}

const bundle = JSON.parse(readFileSync(bundlePath, "utf8"));
const keys = keyMap(bundle, keyFile);
const checked = [
  verifyBundleFingerprint(bundle),
  verifyAnchorBindings(bundle),
  ...anchors(bundle).map((anchor) => verifyAnchor(anchor, keys)),
];
const invalid = checked.find((result) => result.status === "INVALID");
const partial = checked.find((result) => result.status === "PARTIAL");
const status = checked.length === 0 ? "PARTIAL" : invalid ? "INVALID" : partial ? "PARTIAL" : "VALID";
const reason = checked.length === 0 ? "NO_ANCHORS" : invalid?.reason_code ?? partial?.reason_code ?? null;

console.log(JSON.stringify({
  status,
  reason_code: reason,
  verified_trust_level: status === "VALID" ? "INDEPENDENTLY_VERIFIABLE" : "PARTIAL_EXTERNAL",
  checked_counts: {
    anchors: checked.length,
    keys: keys.size,
  },
}, null, 2));
