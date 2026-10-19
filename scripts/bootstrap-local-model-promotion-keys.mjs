import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const mode = process.argv[2] ?? "--ensure";
if (!["--ensure", "--check"].includes(mode) || process.argv.length > 3) {
  throw new Error("Usage: node scripts/bootstrap-local-model-promotion-keys.mjs [--ensure|--check]");
}

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const localRoot = join(repositoryRoot, "deployment", ".local");
const outputDir = join(localRoot, "model-promotion-policy");
const privateKeyPath = join(outputDir, "private-key.pkcs8.base64");
const registryPath = join(outputDir, "trusted-issuers.json");
const issuerIdentity = "local-dev-model-promotion-issuer";
const keyId = "local-dev-model-promotion-key-1";

function validateExisting() {
  if (!existsSync(privateKeyPath) || !existsSync(registryPath)) {
    throw new Error("Incomplete local model-promotion keys; inspect manually. Existing keys are never replaced automatically.");
  }
  const encoded = readFileSync(privateKeyPath, "utf8").trim();
  const privateDer = Buffer.from(encoded, "base64");
  if (privateDer.toString("base64") !== encoded) {
    throw new Error("Local model-promotion private key has noncanonical encoding");
  }
  const privateKey = createPrivateKey({ key: privateDer, format: "der", type: "pkcs8" });
  if (privateKey.asymmetricKeyType !== "ed25519") {
    throw new Error("Local model-promotion private key is not Ed25519");
  }
  const publicDer = createPublicKey(privateKey).export({ format: "der", type: "spki" });
  const registry = JSON.parse(readFileSync(registryPath, "utf8"));
  const key = registry.keys?.[0];
  if (registry.version !== "model-promotion-issuer-registry-v1" || registry.generation !== 1
      || registry.keys?.length !== 1 || key?.issuerIdentity !== issuerIdentity || key.keyId !== keyId
      || key.publicKeySpkiBase64 !== publicDer.toString("base64")
      || key.publicKeySha256 !== createHash("sha256").update(publicDer).digest("hex")
      || JSON.stringify(key.institutionalScopes) !== '["CURRENT_INSTITUTION"]'
      || JSON.stringify(key.environments) !== '["DEVELOPMENT","TEST"]'
      || JSON.stringify(key.intendedUses) !== '["FRAUD_RISK_SCORING"]'
      || key.status !== "ACTIVE" || Date.parse(key.validFrom) > Date.now()
      || Date.parse(key.validUntil) <= Date.now()) {
    throw new Error("Local model-promotion registry is invalid or expired; inspect and rotate explicitly");
  }
}

if (existsSync(outputDir)) {
  validateExisting();
  if (mode === "--ensure") chmodSync(registryPath, 0o444);
  console.log("Existing local model-promotion key and public registry are valid; keeping them unchanged.");
} else {
  if (mode === "--check") {
    throw new Error("Local model-promotion key material is missing");
  }
  mkdirSync(localRoot, { recursive: true, mode: 0o700 });
  const stagingDir = mkdtempSync(join(localRoot, "model-promotion-policy.tmp."));
  try {
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const privateDer = privateKey.export({ format: "der", type: "pkcs8" });
    const publicDer = publicKey.export({ format: "der", type: "spki" });
    const now = Date.now();
    const registry = {
      version: "model-promotion-issuer-registry-v1",
      generation: 1,
      keys: [{
        issuerIdentity,
        keyId,
        publicKeySpkiBase64: publicDer.toString("base64"),
        publicKeySha256: createHash("sha256").update(publicDer).digest("hex"),
        institutionalScopes: ["CURRENT_INSTITUTION"],
        environments: ["DEVELOPMENT", "TEST"],
        intendedUses: ["FRAUD_RISK_SCORING"],
        validFrom: new Date(now - 24 * 60 * 60 * 1000).toISOString().replace(".000Z", "Z"),
        validUntil: new Date(now + 365 * 24 * 60 * 60 * 1000).toISOString().replace(".000Z", "Z"),
        status: "ACTIVE"
      }]
    };
    writeFileSync(join(stagingDir, "private-key.pkcs8.base64"), `${privateDer.toString("base64")}\n`, { mode: 0o600, flag: "wx" });
    writeFileSync(join(stagingDir, "trusted-issuers.json"), `${JSON.stringify(registry, null, 2)}\n`, { mode: 0o444, flag: "wx" });
    renameSync(stagingDir, outputDir);
  } finally {
    if (existsSync(stagingDir)) {
      if (!resolve(stagingDir).startsWith(`${resolve(localRoot)}${sep}`)) {
        throw new Error("Refusing cleanup outside the local fixture directory");
      }
      rmSync(stagingDir, { recursive: true });
    }
  }
  validateExisting();
  console.log("Generated separate local-only model-promotion Ed25519 key and public registry in deployment/.local/model-promotion-policy/.");
}
