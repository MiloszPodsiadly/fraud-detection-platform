import { createHash, createPrivateKey, createSign, randomBytes, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const project = process.argv[2];
if (!project || process.argv.length !== 3) {
  console.error("Usage: node scripts/ci/verify-audit-trust-runtime.mjs <compose-project>");
  process.exit(2);
}

const privateKeyPath = "deployment/.local/service-identity/alert-service-private.pem";
const privateKey = createPrivateKey(readFileSync(privateKeyPath));
const now = Math.floor(Date.now() / 1000);
const header = { alg: "RS256", typ: "JWT", kid: "alert-key-1" };
const claims = {
  iss: "fraud-platform-local",
  aud: ["audit-trust-authority"],
  sub: "compose-runtime-smoke",
  service_name: "alert-service",
  authorities: ["AUDIT_ANCHOR"],
  iat: now,
  exp: now + 60
};
const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
const unsigned = `${encode(header)}.${encode(claims)}`;
const signer = createSign("RSA-SHA256");
signer.update(unsigned);
signer.end();
const token = `${unsigned}.${signer.sign(privateKey).toString("base64url")}`;

const anchor = {
  purpose: "AUDIT_ANCHOR",
  payload_hash: createHash("sha256").update(randomBytes(32)).digest("hex"),
  partition_key: "source_service:runtime-smoke",
  chain_position: 1,
  anchor_id: `runtime-smoke-${randomBytes(12).toString("hex")}`
};

const signResponse = composeRequest("/api/v1/trust/sign", anchor, token);
if (signResponse.exitCode !== 0 || signResponse.statusCode !== "200") {
  throw new Error(`Trust authority sign request failed with HTTP ${signResponse.statusCode}, exit ${signResponse.exitCode}: ${signResponse.stderr}`);
}
const signed = JSON.parse(signResponse.body);
if (!signed.signature || !signed.key_id || signed.algorithm !== "Ed25519" || !signed.signed_at) {
  throw new Error("Trust authority returned an incomplete Ed25519 signature response.");
}

const verifyResponse = composeRequest("/api/v1/trust/verify", {
  ...anchor,
  signature: signed.signature,
  key_id: signed.key_id,
  signed_at: signed.signed_at
}, token);
if (verifyResponse.exitCode !== 0 || verifyResponse.statusCode !== "200") {
  throw new Error(`Trust authority verify request failed with HTTP ${verifyResponse.statusCode}, exit ${verifyResponse.exitCode}: ${verifyResponse.stderr}`);
}
if (JSON.parse(verifyResponse.body).status !== "VALID") {
  throw new Error("Trust authority did not validate its runtime Ed25519 signature.");
}

const denied = composeRequest("/api/v1/trust/sign", anchor, null);
if (denied.exitCode !== 0 || !["401", "403"].includes(denied.statusCode)) {
  throw new Error(`Trust authority accepted an unsigned caller (HTTP ${denied.statusCode}).`);
}

console.log("Runtime trust authority sign/verify and unsigned-request denial passed.");

function composeRequest(endpoint, body, bearerToken) {
  const files = [
    "deployment/docker-compose.yml",
    "deployment/docker-compose.dev.yml",
    "deployment/docker-compose.oidc.yml",
    "deployment/docker-compose.service-identity-mtls.yml",
    "deployment/docker-compose.trust-authority-jwt.yml",
    "deployment/docker-compose.hardened.yml",
    "deployment/docker-compose.model-promotion-policy-trust.yml"
  ];
  const args = ["compose", "-p", project, "--env-file", "deployment/.env",
    ...files.flatMap((file) => ["-f", file]), "exec", "-T"];
  if (bearerToken) args.push("-e", "TRUST_SMOKE_JWT");
  args.push("audit-trust-authority", "sh", "-ec",
    `curl -sS -w '\\n%{http_code}' -H 'Content-Type: application/json' `
      + `-H 'X-Internal-Trust-Request-Id: ${randomUUID()}' `
      + `${bearerToken ? `-H "Authorization: Bearer $TRUST_SMOKE_JWT" ` : ""}`
      + `-X POST --data-binary @- http://localhost:8080${endpoint}`);
  const result = spawnSync("docker", args, {
    cwd: process.cwd(),
    encoding: "utf8",
    input: JSON.stringify(body),
    env: bearerToken ? { ...process.env, TRUST_SMOKE_JWT: bearerToken } : process.env
  });
  const output = result.stdout ?? "";
  const separator = output.lastIndexOf("\n");
  return {
    exitCode: result.status,
    statusCode: separator < 0 ? "" : output.slice(separator + 1).trim(),
    body: separator < 0 ? output : output.slice(0, separator),
    stderr: result.stderr ?? result.error?.message ?? ""
  };
}
