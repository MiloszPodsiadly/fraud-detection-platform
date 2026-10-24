import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync } from "node:crypto";
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
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
const approvalDir = join(outputDir, "approval");
const observationDir = join(outputDir, "fleet-observation");
const observationRegistryPath = join(observationDir, "trusted-reporters.json");
const localEnvPath = join(repositoryRoot, "deployment", ".env");
const localEnvDeploymentId = existsSync(localEnvPath)
  ? readFileSync(localEnvPath, "utf8").split(/\r?\n/)
    .map(line => line.match(/^MODEL_PROMOTION_TRUST_DEPLOYMENT_ID\s*=\s*(.*?)\s*$/))
    .find(Boolean)?.[1]?.replace(/^['"]|['"]$/g, "")
  : undefined;
const observationDeploymentId = process.env.MODEL_PROMOTION_TRUST_DEPLOYMENT_ID
  || localEnvDeploymentId || "local-compose";
if (!/^[A-Za-z0-9._:-]{1,128}$/.test(observationDeploymentId)) {
  throw new Error("Invalid local observation deployment ID");
}
const observationKeys = [
  ["ALERT_VERIFIER", "alert-service", "local-alert-observation-1", "alert.private-key.pkcs8.base64"],
  ["PUBLISHER", "model-promotion-policy-publisher", "local-publisher-observation-1", "publisher.private-key.pkcs8.base64"]
];
const approvalRegistryPath = join(approvalDir, "trusted-approvals.json");
const onlineActorTrustPath = join(approvalDir, "online-actor-trust.json");
const onlineIssuer = "http://localhost:8086/realms/fraud-detection";
const onlineActors = [
  ["b15b1124-74f3-4abd-8c53-377972316734", "POLICY_AUTHOR"],
  ["bf7fd870-5c5f-445e-8707-a77652813c6e", "POLICY_APPROVER"]
];
const localActors = [
  ["local-dev-policy-author-a", "local-dev-author-a-key-1", "POLICY_AUTHOR", "author-a.private-key.pkcs8.base64"],
  ["local-dev-policy-approver-b", "local-dev-approver-b-key-1", "POLICY_APPROVER", "approver-b.private-key.pkcs8.base64"],
  ["local-dev-policy-publisher", "local-dev-publisher-key-1", "POLICY_PUBLISHER", "publisher.private-key.pkcs8.base64"]
];

function assertProtectedFile(file) {
  for (const candidate of [localRoot, outputDir, dirname(file), file]) {
    const entry = lstatSync(candidate);
    if (entry.isSymbolicLink()) throw new Error(`Symlink in local policy trust path: ${candidate}`);
  }
  const entry = lstatSync(file);
  if (!entry.isFile() || entry.size === 0) throw new Error(`Invalid local policy credential: ${file}`);
  if (process.platform !== "win32" && (entry.mode & 0o077) !== 0) {
    throw new Error(`Local policy credential must not grant group/other access: ${file}`);
  }
}

function publicEntry(identity, id, role, publicDer, from, until) {
  return {
    identity,
    keyId: id,
    publicKeySpkiBase64: publicDer.toString("base64"),
    publicKeySha256: createHash("sha256").update(publicDer).digest("hex"),
    roles: [role],
    institutionalScopes: ["CURRENT_INSTITUTION"],
    environments: ["DEVELOPMENT", "TEST"],
    intendedUses: ["FRAUD_RISK_SCORING"],
    validFrom: from,
    validUntil: until,
    status: "ACTIVE"
  };
}

function validateApproval() {
  if (!existsSync(approvalRegistryPath)) {
    throw new Error("Local approval trust registry is missing; inspect manually");
  }
  const registry = JSON.parse(readFileSync(approvalRegistryPath, "utf8"));
  if (registry.version !== "model-promotion-approval-trust-v1" || registry.generation !== 1
      || registry.keys?.length !== 4) {
    throw new Error("Local approval trust registry is invalid");
  }
  for (const [identity, id, role, filename] of localActors) {
    const credential = join(approvalDir, filename);
    if (!existsSync(credential)) throw new Error(`Missing local credential: ${filename}`);
    assertProtectedFile(credential);
    const encoded = readFileSync(credential, "utf8").trim();
    const privateDer = Buffer.from(encoded, "base64");
    if (privateDer.toString("base64") !== encoded) throw new Error(`Noncanonical local credential: ${filename}`);
    const privateKey = createPrivateKey({ key: privateDer, format: "der", type: "pkcs8" });
    if (privateKey.asymmetricKeyType !== "ed25519") throw new Error(`Not Ed25519: ${filename}`);
    const publicDer = createPublicKey(privateKey).export({ format: "der", type: "spki" });
    const expected = registry.keys.find(key => key.keyId === id);
    if (expected?.identity !== identity || JSON.stringify(expected.roles) !== JSON.stringify([role])
        || expected.publicKeySpkiBase64 !== publicDer.toString("base64")
        || expected.publicKeySha256 !== createHash("sha256").update(publicDer).digest("hex")
        || expected.status !== "ACTIVE" || Date.parse(expected.validFrom) > Date.now()
        || Date.parse(expected.validUntil) <= Date.now()) {
      throw new Error(`Local approval credential or mandate is invalid: ${id}`);
    }
  }
  const issuer = registry.keys.find(key => key.keyId === keyId);
  const publicDer = createPublicKey(createPrivateKey({
    key: Buffer.from(readFileSync(privateKeyPath, "utf8").trim(), "base64"), format: "der", type: "pkcs8"
  })).export({ format: "der", type: "spki" });
  if (issuer?.identity !== issuerIdentity || JSON.stringify(issuer.roles) !== '["POLICY_ISSUER"]'
      || issuer.publicKeySpkiBase64 !== publicDer.toString("base64")
      || issuer.publicKeySha256 !== createHash("sha256").update(publicDer).digest("hex")) {
    throw new Error("Local approval issuer mandate is invalid");
  }
}

function ensureApproval() {
  if (existsSync(approvalDir)) {
    validateApproval();
    return;
  }
  if (mode === "--check") throw new Error("Local approval credentials are missing");
  const stagingDir = mkdtempSync(join(outputDir, "approval.tmp."));
  try {
    const issuer = JSON.parse(readFileSync(registryPath, "utf8")).keys[0];
    const entries = [];
    for (const [identity, id, role, filename] of localActors) {
      const { privateKey, publicKey } = generateKeyPairSync("ed25519");
      const privateDer = privateKey.export({ format: "der", type: "pkcs8" });
      const publicDer = publicKey.export({ format: "der", type: "spki" });
      writeFileSync(join(stagingDir, filename), `${privateDer.toString("base64")}\n`, { mode: 0o600, flag: "wx" });
      entries.push(publicEntry(identity, id, role, publicDer, issuer.validFrom, issuer.validUntil));
    }
    entries.push(publicEntry(issuerIdentity, keyId, "POLICY_ISSUER",
      Buffer.from(issuer.publicKeySpkiBase64, "base64"), issuer.validFrom, issuer.validUntil));
    writeFileSync(join(stagingDir, "trusted-approvals.json"),
      `${JSON.stringify({ version: "model-promotion-approval-trust-v1", generation: 1, keys: entries }, null, 2)}\n`,
      { mode: 0o444, flag: "wx" });
    renameSync(stagingDir, approvalDir);
  } finally {
    if (existsSync(stagingDir)) {
      if (!resolve(stagingDir).startsWith(`${resolve(outputDir)}${sep}`)) {
        throw new Error("Refusing cleanup outside the local fixture directory");
      }
      rmSync(stagingDir, { recursive: true });
    }
  }
  validateApproval();
  console.log("Generated separate local-only author, approver and publisher credentials; no approval decision was created.");
}

function ensureObservationKeys() {
  if (!existsSync(observationDir)) {
    if (mode === "--check") throw new Error("Local fleet observation keys are missing");
    const stagingDir = mkdtempSync(join(outputDir, "fleet-observation.tmp."));
    try {
      const keys = observationKeys.map(([role, instanceId, keyId, filename]) => {
        const { privateKey, publicKey } = generateKeyPairSync("ed25519");
        const privateDer = privateKey.export({ format: "der", type: "pkcs8" });
        const publicDer = publicKey.export({ format: "der", type: "spki" });
        writeFileSync(join(stagingDir, filename), `${privateDer.toString("base64")}\n`, { mode: 0o600, flag: "wx" });
        return { role, instanceId, keyId, publicKeySpkiBase64: publicDer.toString("base64") };
      });
      writeFileSync(join(stagingDir, "trusted-reporters.json"),
        `${JSON.stringify({ version: "policy-trust-observation-keys-v1", deploymentId: observationDeploymentId, keys }, null, 2)}\n`,
        { mode: 0o444, flag: "wx" });
      renameSync(stagingDir, observationDir);
    } finally {
      if (existsSync(stagingDir)) {
        if (!resolve(stagingDir).startsWith(`${resolve(outputDir)}${sep}`)) {
          throw new Error("Refusing cleanup outside the local fixture directory");
        }
        rmSync(stagingDir, { recursive: true });
      }
    }
  }
  const registry = JSON.parse(readFileSync(observationRegistryPath, "utf8"));
  if (registry.version !== "policy-trust-observation-keys-v1"
      || registry.deploymentId !== observationDeploymentId
      || registry.keys?.length !== observationKeys.length) {
    throw new Error("Local fleet observation trust registry is invalid or belongs to another deployment");
  }
  for (const [role, instanceId, keyId, filename] of observationKeys) {
    const credential = join(observationDir, filename);
    assertProtectedFile(credential);
    const privateKey = createPrivateKey({
      key: Buffer.from(readFileSync(credential, "utf8").trim(), "base64"), format: "der", type: "pkcs8"
    });
    if (privateKey.asymmetricKeyType !== "ed25519") throw new Error(`Invalid observation key: ${filename}`);
    const publicDer = createPublicKey(privateKey).export({ format: "der", type: "spki" });
    const entry = registry.keys.find(key => key.keyId === keyId);
    if (entry?.role !== role || entry.instanceId !== instanceId
        || entry.publicKeySpkiBase64 !== publicDer.toString("base64")) {
      throw new Error(`Local observation identity mismatch: ${keyId}`);
    }
  }
}

function validateExisting() {
  if (!existsSync(privateKeyPath) || !existsSync(registryPath)) {
    throw new Error("Incomplete local model-promotion keys; inspect manually. Existing keys are never replaced automatically.");
  }
  assertProtectedFile(privateKeyPath);
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

function actorId(subject) {
  return `oidc:${createHash("sha256").update(`${onlineIssuer}#${subject}`).digest("hex")}`;
}

function ensureOnlineActorTrust() {
  if (!existsSync(onlineActorTrustPath)) {
    if (mode === "--check") throw new Error("Local online actor mandates are missing");
    const issuer = JSON.parse(readFileSync(registryPath, "utf8")).keys[0];
    const actors = onlineActors.map(([subject, role]) => ({
      actorId: actorId(subject), issuer: onlineIssuer, subject, roles: [role],
      displayName: role === "POLICY_APPROVER" ? "Policy Approver" : "Policy Author",
      institutionalScopes: ["CURRENT_INSTITUTION"], environments: ["DEVELOPMENT", "TEST"],
      intendedUses: ["FRAUD_RISK_SCORING"], validFrom: issuer.validFrom,
      validUntil: issuer.validUntil, status: "ACTIVE"
    }));
    writeFileSync(onlineActorTrustPath,
      `${JSON.stringify({ version: "model-policy-online-actor-trust-v1", generation: 1, actors }, null, 2)}\n`,
      { mode: 0o444, flag: "wx" });
  }
  const registry = JSON.parse(readFileSync(onlineActorTrustPath, "utf8"));
  if (registry.version !== "model-policy-online-actor-trust-v1" || registry.generation !== 1
      || registry.actors?.length !== onlineActors.length) {
    throw new Error("Local online actor mandates are invalid");
  }
  for (const [subject, role] of onlineActors) {
    const actor = registry.actors.find(entry => entry.subject === subject);
    if (actor?.actorId !== actorId(subject) || actor.issuer !== onlineIssuer
        || JSON.stringify(actor.roles) !== JSON.stringify([role]) || actor.status !== "ACTIVE"
        || Date.parse(actor.validFrom) > Date.now() || Date.parse(actor.validUntil) <= Date.now()) {
      throw new Error(`Local online actor mandate is invalid: ${subject}`);
    }
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
ensureApproval();
ensureOnlineActorTrust();
ensureObservationKeys();
