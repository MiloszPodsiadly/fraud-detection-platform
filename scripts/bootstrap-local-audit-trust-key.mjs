import { createPrivateKey, createPublicKey, generateKeyPairSync } from "node:crypto";
import { existsSync, lstatSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const mode = process.argv[2] ?? "--ensure";
if (!["--ensure", "--check"].includes(mode) || process.argv.length > 3) {
  throw new Error("Usage: node scripts/bootstrap-local-audit-trust-key.mjs [--ensure|--check]");
}
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dir = join(root, "deployment", ".local", "audit-trust-authority");
const privatePath = join(dir, "ed25519-private.key");
const publicPath = join(dir, "ed25519-public.key");
for (const path of [join(root, "deployment"), join(root, "deployment", ".local"), dir]) {
  if (existsSync(path) && lstatSync(path).isSymbolicLink()) {
    throw new Error("Symlink in local audit-trust key path");
  }
}
const privateExists = existsSync(privatePath);
const publicExists = existsSync(publicPath);
if (privateExists !== publicExists) throw new Error("Partial audit-trust key pair; refusing to replace it");
if (!privateExists) {
  if (mode === "--check") throw new Error("Audit-trust key pair is missing");
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  writeFileSync(privatePath, privateKey.export({ format: "der", type: "pkcs8" }).toString("base64"),
    { flag: "wx", mode: 0o600 });
  writeFileSync(publicPath, publicKey.export({ format: "der", type: "spki" }).toString("base64"),
    { flag: "wx", mode: 0o644 });
}
for (const file of [privatePath, publicPath]) {
  const entry = lstatSync(file);
  if (!entry.isFile() || entry.isSymbolicLink() || entry.size === 0) throw new Error("Invalid audit-trust key file");
}
if (process.platform !== "win32" && (lstatSync(privatePath).mode & 0o077) !== 0) {
  throw new Error("Audit-trust private key grants group or world access");
}
const privateDer = Buffer.from(readFileSync(privatePath, "utf8").trim(), "base64");
const publicDer = Buffer.from(readFileSync(publicPath, "utf8").trim(), "base64");
const privateKey = createPrivateKey({ key: privateDer, format: "der", type: "pkcs8" });
const publicKey = createPublicKey({ key: publicDer, format: "der", type: "spki" });
if (privateKey.asymmetricKeyType !== "ed25519" || publicKey.asymmetricKeyType !== "ed25519"
    || !createPublicKey(privateKey).export({ format: "der", type: "spki" }).equals(publicDer)) {
  throw new Error("Audit-trust key pair does not match");
}
console.log("Local audit-trust key pair ready");
