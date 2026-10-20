import { createHash, createPublicKey, generateKeyPairSync } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const command = process.argv[2];
const options = new Map();
for (let index = 3; index < process.argv.length; index += 2) {
  const name = process.argv[index];
  const value = process.argv[index + 1];
  if (!name?.startsWith("--") || !value || options.has(name)) throw new Error("Invalid or duplicate option");
  options.set(name, value);
}

function required(name) {
  const value = options.get(name);
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function allowed(...names) {
  for (const name of options.keys()) if (!names.includes(name)) throw new Error(`Unsupported option: ${name}`);
}

function identifier(value) {
  if (!/^[A-Za-z0-9._:-]{1,128}$/.test(value)) throw new Error("Invalid identity or key ID");
  return value;
}

function instant(value) {
  const date = new Date(value);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value)
      || !Number.isFinite(date.getTime()) || date.toISOString().replace(".000Z", "Z") !== value) {
    throw new Error("Timestamp must be canonical UTC with whole seconds");
  }
  return date.getTime();
}

function publicMaterial(path) {
  const file = readFileSync(path);
  const asText = file.toString("utf8").trim();
  const der = /^[A-Za-z0-9+/]+={0,2}$/.test(asText) && Buffer.from(asText, "base64").toString("base64") === asText
    ? Buffer.from(asText, "base64") : file;
  const key = createPublicKey({ key: der, format: "der", type: "spki" });
  if (key.asymmetricKeyType !== "ed25519" || !key.export({ format: "der", type: "spki" }).equals(der)) {
    throw new Error("Public key must be canonical Ed25519 SPKI DER");
  }
  return { publicKeySpkiBase64: der.toString("base64"),
    publicKeySha256: createHash("sha256").update(der).digest("hex") };
}

function registry() {
  const kind = required("--kind");
  if (!["approval", "issuer"].includes(kind)) throw new Error("--kind must be approval or issuer");
  const data = JSON.parse(readFileSync(required("--registry"), "utf8"));
  const version = kind === "approval" ? "model-promotion-approval-trust-v1" : "model-promotion-issuer-registry-v1";
  const expected = Number(required("--expected-generation"));
  if (data.version !== version || !Number.isSafeInteger(expected) || expected < 0
      || data.generation !== expected || !Array.isArray(data.keys)
      || (expected === 0 && data.keys.length !== 0)
      || (expected > 0 && data.keys.length === 0)) {
    throw new Error("Registry kind/generation mismatch or invalid keys");
  }
  return { kind, data };
}

function initRegistry() {
  allowed("--kind", "--out");
  const kind = required("--kind");
  if (!["approval", "issuer"].includes(kind)) throw new Error("--kind must be approval or issuer");
  const version = kind === "approval" ? "model-promotion-approval-trust-v1" : "model-promotion-issuer-registry-v1";
  writeNew(required("--out"), `${JSON.stringify({ version, generation: 0, keys: [] }, null, 2)}\n`);
  console.log("Created staging registry; add at least one key before trust-check or deployment");
}

function writeNew(path, value) {
  if (existsSync(path)) throw new Error("Output exists; refusing to overwrite");
  writeFileSync(path, value, { flag: "wx", mode: 0o600 });
}

function keygen() {
  allowed("--private", "--public");
  const privatePath = required("--private");
  const publicPath = required("--public");
  if (privatePath === publicPath || existsSync(privatePath) || existsSync(publicPath)) {
    throw new Error("Distinct new output files are required");
  }
  const pair = generateKeyPairSync("ed25519");
  const privateDer = pair.privateKey.export({ format: "der", type: "pkcs8" });
  const publicDer = pair.publicKey.export({ format: "der", type: "spki" });
  writeNew(privatePath, `${privateDer.toString("base64")}\n`);
  writeNew(publicPath, `${publicDer.toString("base64")}\n`);
  console.log(`Public-key SHA-256 ${createHash("sha256").update(publicDer).digest("hex")}`);
}

function fingerprint() {
  allowed("--public", "--expected");
  const actual = publicMaterial(required("--public")).publicKeySha256;
  if (options.has("--expected") && actual !== required("--expected")) throw new Error("Fingerprint mismatch");
  console.log(`Public-key SHA-256 ${actual}`);
}

function addKey() {
  allowed("--kind", "--registry", "--expected-generation", "--out", "--public", "--identity", "--key-id",
    "--role", "--environments", "--valid-from", "--valid-until");
  const { kind, data } = registry();
  const identity = identifier(required("--identity"));
  const keyId = identifier(required("--key-id"));
  if (data.keys.some(key => key.keyId === keyId)) throw new Error("Key ID already exists");
  const environments = required("--environments").split(",");
  if (environments.length === 0 || new Set(environments).size !== environments.length
      || environments.some(value => !["DEVELOPMENT", "TEST", "PRODUCTION"].includes(value))) {
    throw new Error("Environments must be explicit and unique");
  }
  const validFrom = required("--valid-from");
  const validUntil = required("--valid-until");
  if (instant(validFrom) >= instant(validUntil)) throw new Error("Invalid validity interval");
  const material = publicMaterial(required("--public"));
  if (data.keys.some(key => key.publicKeySha256 === material.publicKeySha256)) {
    throw new Error("Public key already exists in this registry, including retired or revoked entries");
  }
  const entry = { [kind === "approval" ? "identity" : "issuerIdentity"]: identity, keyId, ...material };
  if (kind === "approval") {
    const role = required("--role");
    if (!["POLICY_AUTHOR", "POLICY_APPROVER", "POLICY_PUBLISHER", "POLICY_ISSUER"].includes(role)) {
      throw new Error("Unsupported approval role");
    }
    entry.roles = [role];
  } else if (options.has("--role")) throw new Error("Issuer registry has no role field");
  Object.assign(entry, { institutionalScopes: ["CURRENT_INSTITUTION"], environments,
    intendedUses: ["FRAUD_RISK_SCORING"], validFrom, validUntil, status: "ACTIVE" });
  data.keys.push(entry);
  data.generation += 1;
  writeNew(required("--out"), `${JSON.stringify(data, null, 2)}\n`);
  console.log(`Created ${kind} registry generation ${data.generation}; key SHA-256 ${material.publicKeySha256}`);
}

function setStatus() {
  allowed("--kind", "--registry", "--expected-generation", "--out", "--key-id", "--status", "--valid-until");
  const { kind, data } = registry();
  const key = data.keys.find(entry => entry.keyId === identifier(required("--key-id")));
  if (!key) throw new Error("Unknown key ID");
  const status = required("--status");
  if (!((status === "RETIRED" && key.status === "ACTIVE")
      || (status === "REVOKED" && ["ACTIVE", "RETIRED"].includes(key.status)))) {
    throw new Error("Only ACTIVE to RETIRED/REVOKED or RETIRED to REVOKED is permitted");
  }
  if (status === "RETIRED") {
    const until = required("--valid-until");
    if (instant(until) <= instant(key.validFrom) || instant(until) > instant(key.validUntil)
        || instant(until) > Date.now()) throw new Error("Retirement cutoff is invalid");
    key.validUntil = until;
  } else if (options.has("--valid-until")) throw new Error("Revocation preserves the recorded validity window");
  key.status = status;
  data.generation += 1;
  writeNew(required("--out"), `${JSON.stringify(data, null, 2)}\n`);
  console.log(`Created ${kind} registry generation ${data.generation}; key status ${status}`);
}

switch (command) {
  case "init-registry": initRegistry(); break;
  case "keygen": keygen(); break;
  case "fingerprint": fingerprint(); break;
  case "add-key": addKey(); break;
  case "set-status": setStatus(); break;
  default: throw new Error("Usage: model-promotion-trust-admin.mjs init-registry|keygen|fingerprint|add-key|set-status [options]");
}
