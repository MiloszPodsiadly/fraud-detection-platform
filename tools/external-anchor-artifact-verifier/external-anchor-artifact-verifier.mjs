#!/usr/bin/env node
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";

const SUPPORTED_ANCHOR_ID_VERSION = 1;

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

function anchorId(payload) {
  return sha256Hex([
    payload.source ?? "",
    payload.schema_version ?? "",
    payload.partition_key ?? "",
    payload.chain_position ?? "",
    payload.event_hash ?? "",
  ].join(":"));
}

function withoutPayloadHash(payload) {
  return { ...payload, payload_hash: null };
}

function readJsonFile(path) {
  if (!path) {
    return null;
  }
  if (/^https?:\/\//i.test(path)) {
    return { __error: "URL_UNSUPPORTED" };
  }
  if (!existsSync(path)) {
    return { __error: "MISSING" };
  }
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return { __error: "INVALID_JSON" };
  }
}

function parseArgs(argv) {
  const args = { bundle: null, anchor: null, reference: null, witness: null, help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const current = argv[index];
    if (current === "--help" || current === "-h") {
      args.help = true;
    } else if (current === "--bundle") {
      args.bundle = argv[++index];
    } else if (current === "--anchor") {
      args.anchor = argv[++index];
    } else if (current === "--reference") {
      args.reference = argv[++index];
    } else if (current === "--witness") {
      args.witness = argv[++index];
    }
  }
  return args;
}

function usage() {
  return "usage: node tools/external-anchor-artifact-verifier/external-anchor-artifact-verifier.mjs --anchor anchor.json [--bundle bundle.json] [--reference reference.json] [--witness witness.json]\n\nLocal artifact mode only: verifies downloaded/exported external anchor artifacts. It does not fetch live object-store, TSA, or blockchain witness records.";
}

function compareBundle(bundle, payload) {
  if (!bundle) {
    return null;
  }
  const expectedPosition = bundle.chain_range_end ?? bundle.latest_local_position ?? null;
  if (Number.isInteger(expectedPosition) && payload.chain_position < expectedPosition) {
    return { status: "STALE", reason_code: "ANCHOR_BEHIND_LOCAL_HEAD" };
  }
  const event = (bundle.events ?? []).find((candidate) => candidate.chain_position === payload.chain_position);
  if (event && event.event_hash !== payload.event_hash) {
    return { status: "CONFLICT", reason_code: "EVENT_HASH_CONFLICT" };
  }
  const localAnchor = event?.local_anchor;
  if (localAnchor && localAnchor.anchor_id && localAnchor.anchor_id !== payload.local_anchor_id) {
    return { status: "CONFLICT", reason_code: "LOCAL_ANCHOR_CONFLICT" };
  }
  return null;
}

function timestampResult(reference, witness) {
  const timestampType = reference?.timestamp_type ?? witness?.timestamp_type ?? "APP_OBSERVED";
  const timestampTrust = reference?.timestamp_trust_level ?? witness?.timestamp_trust_level ?? "WEAK";
  const timestampVerified = reference?.timestamp_verified === true || witness?.timestamp_verified === true;
  if (timestampType === "APP_OBSERVED" || !timestampVerified) {
    return {
      status: "UNVERIFIED",
      reason_code: "TIMESTAMP_NOT_INDEPENDENTLY_VERIFIED",
      timestamp_type: timestampType,
      timestamp_trust_level: timestampTrust,
      timestamp_verified: false,
    };
  }
  return {
    status: "VALID",
    reason_code: null,
    timestamp_type: timestampType,
    timestamp_trust_level: timestampTrust,
    timestamp_verified: true,
  };
}

function verify({ bundlePath, anchorPath, referencePath, witnessPath }) {
  const anchor = readJsonFile(anchorPath);
  if (!anchor || anchor.__error === "MISSING") {
    return result("MISSING", "ANCHOR_OBJECT_MISSING");
  }
  if (anchor.__error === "URL_UNSUPPORTED") {
    return result("UNAVAILABLE", "REMOTE_WITNESS_UNSUPPORTED");
  }
  if (anchor.__error) {
    return result("INVALID", "ANCHOR_JSON_INVALID");
  }
  const bundle = readJsonFile(bundlePath);
  if (bundle?.__error) {
    return result(bundle.__error === "MISSING" ? "MISSING" : "INVALID", "BUNDLE_UNREADABLE");
  }
  const reference = readJsonFile(referencePath);
  if (reference?.__error && reference.__error !== "MISSING") {
    return result("INVALID", "REFERENCE_UNREADABLE");
  }
  const witness = readJsonFile(witnessPath);
  if (witness?.__error && witness.__error !== "MISSING") {
    return result("INVALID", "WITNESS_CONFIG_UNREADABLE");
  }
  if (anchor.anchor_id_version !== SUPPORTED_ANCHOR_ID_VERSION) {
    return result("INVALID", "ANCHOR_ID_VERSION_UNSUPPORTED", anchor);
  }
  const expectedAnchorId = anchorId(anchor);
  if (anchor.anchor_id !== expectedAnchorId) {
    return result("INVALID", "ANCHOR_ID_MISMATCH", anchor);
  }
  const expectedPayloadHash = sha256Hex(canonical(withoutPayloadHash(anchor)));
  if (anchor.payload_hash !== expectedPayloadHash) {
    return result("INVALID", "PAYLOAD_HASH_MISMATCH", anchor);
  }
  if (reference && !reference.__error) {
    if (reference.external_hash && reference.external_hash !== anchor.event_hash) {
      return result("CONFLICT", "REFERENCE_HASH_CONFLICT", anchor);
    }
    if (reference.external_key && reference.external_key !== anchor.external_object_key) {
      return result("CONFLICT", "REFERENCE_KEY_CONFLICT", anchor);
    }
  }
  const bundleComparison = compareBundle(bundle, anchor);
  if (bundleComparison) {
    return result(bundleComparison.status, bundleComparison.reason_code, anchor);
  }
  const timestamp = timestampResult(reference && !reference.__error ? reference : null, witness && !witness.__error ? witness : null);
  return result(timestamp.status, timestamp.reason_code, anchor, timestamp);
}

function result(status, reasonCode, anchor = null, timestamp = {}) {
  return {
    status,
    reason_code: reasonCode ?? null,
    anchor_id_version: anchor?.anchor_id_version ?? null,
    chain_position: anchor?.chain_position ?? null,
    timestamp_type: timestamp.timestamp_type ?? null,
    timestamp_trust_level: timestamp.timestamp_trust_level ?? null,
    timestamp_verified: timestamp.timestamp_verified ?? null,
  };
}

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(usage());
  process.exit(0);
}
if (!args.anchor) {
  console.error(usage());
  process.exit(2);
}

const verification = verify({
  bundlePath: args.bundle,
  anchorPath: args.anchor,
  referencePath: args.reference,
  witnessPath: args.witness,
});
console.log(JSON.stringify(verification, null, 2));
