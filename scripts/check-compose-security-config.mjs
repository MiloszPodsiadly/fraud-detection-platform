import fs from "node:fs";
import path from "node:path";

const variant = process.argv[2];
const requireExistingSource = process.argv.includes("--require-existing-source");
const sourceFile = optionValue("--source-file");
const exactModelTarget = "/run/model-shadow-performance";
const exactModelSource = "${MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT:-./local-generated/model-shadow-performance}";
const protectedGovernanceRoots = [
  "/run/model-card",
  "/run/model-shadow-performance",
  "/run/promotion-readiness",
  "/run/shadow-performance"
];
const supportedVariants = new Set([
  "base",
  "dev",
  "ai",
  "security-hardened",
  "model-shadow-performance-source",
  "model-shadow-performance",
  "security-hardened-model-shadow-performance"
]);

if (!supportedVariants.has(variant)) {
  console.error(
    "Usage: node scripts/check-compose-security-config.mjs <variant> "
      + "[--source-file <compose.yml>] [--require-existing-source]"
  );
  process.exit(2);
}

if (variant === "model-shadow-performance-source" && !sourceFile) {
  console.error("model-shadow-performance-source requires --source-file");
  process.exit(2);
}

let config = { services: {} };
if (variant !== "model-shadow-performance-source") {
  try {
    config = JSON.parse(fs.readFileSync(0, "utf8"));
  } catch (error) {
    console.error(`Resolved Compose config must be JSON on stdin: ${error.message}`);
    process.exit(2);
  }
}

const services = config.services ?? {};
const failures = [];

function optionValue(name) {
  const index = process.argv.indexOf(name);
  if (index < 0) return undefined;
  const value = process.argv[index + 1];
  if (!value || value.startsWith("--")) {
    console.error(`${name} requires a value`);
    process.exit(2);
  }
  return value;
}

function fail(message) {
  failures.push(message);
}

function service(name) {
  if (!services[name]) {
    fail(`missing service ${name}`);
    return {};
  }
  return services[name];
}

function env(name, key) {
  return service(name).environment?.[key];
}

function expectEqual(label, actual, expected) {
  if (String(actual) !== String(expected)) {
    fail(`${label} must be ${expected}, resolved value was ${String(actual)}`);
  }
}

function assertNoContainerNames() {
  for (const [name, definition] of Object.entries(services)) {
    if (definition.container_name) {
      fail(`${name} must not define container_name`);
    }
  }
}

function assertNoPublishedPorts() {
  for (const [name, definition] of Object.entries(services)) {
    if ((definition.ports ?? []).length > 0) {
      fail(`${name} must not publish host ports in the base stack`);
    }
  }
}

function assertLocalBindings() {
  for (const [name, definition] of Object.entries(services)) {
    for (const port of definition.ports ?? []) {
      if (port.host_ip !== "127.0.0.1") {
        fail(`${name} published port ${port.published ?? "unknown"} must bind 127.0.0.1`);
      }
    }
  }
}

function assertUiLocalPort() {
  const ports = service("analyst-console-ui").ports ?? [];
  const uiPort = ports.find((port) => String(port.published) === "4173" && String(port.target) === "8080");
  if (!uiPort || uiPort.host_ip !== "127.0.0.1") {
    fail("analyst-console-ui must publish only local 127.0.0.1:4173 -> 8080 in dev");
  }
}

function assertControl(name, key, expected) {
  const value = service(name)[key];
  if (String(value) !== String(expected)) {
    fail(`${name}.${key} must be ${expected}, resolved value was ${String(value)}`);
  }
}

function assertHardening() {
  const readOnlyApps = [
    "audit-trust-authority",
    "transaction-ingest-service",
    "transaction-simulator-service",
    "feature-enricher-service",
    "fraud-scoring-service",
    "alert-service",
    "ml-inference-service",
    "analyst-console-ui",
  ];
  const controlledApps = readOnlyApps;
  for (const name of readOnlyApps) {
    assertControl(name, "read_only", true);
  }
  for (const name of controlledApps) {
    const definition = service(name);
    if (!(definition.cap_drop ?? []).includes("ALL")) {
      fail(`${name} must drop ALL Linux capabilities in the application hardening overlay`);
    }
    if (!(definition.security_opt ?? []).includes("no-new-privileges:true")) {
      fail(`${name} must set no-new-privileges:true in the application hardening overlay`);
    }
    if (!definition.pids_limit) {
      fail(`${name} must define pids_limit in the application hardening overlay`);
    }
  }
}

function assertGeneratedIdentityMount(serviceName, target, expectedSourceSuffix) {
  const mounts = service(serviceName).volumes ?? [];
  const mount = mounts.find((volume) => volume.target === target);
  const source = String(mount?.source ?? "").replaceAll("\\", "/");
  if (!mount || mount.type !== "bind" || !mount.read_only || !source.endsWith(expectedSourceSuffix)) {
    fail(`${serviceName} must read ${target} from generated local fixture path ${expectedSourceSuffix}`);
  }
}

function assertMtlsIdentityMounts(serviceName, filenames) {
  const targetRoot = "/run/service-identity/mtls/";
  const mounts = (service(serviceName).volumes ?? [])
    .filter((volume) => String(volume?.target ?? "").startsWith(targetRoot));
  const expectedTargets = new Set(filenames.map((filename) => `${targetRoot}${filename}`));
  const observedTargets = new Set();
  if (mounts.length !== expectedTargets.size) {
    fail(`${serviceName} must expose only its CA, certificate and private key mTLS files`);
  }
  for (const mount of mounts) {
    const target = String(mount?.target ?? "");
    const filename = target.slice(targetRoot.length);
    const source = String(mount?.source ?? "").replaceAll("\\", "/");
    if (observedTargets.has(target)) {
      fail(`${serviceName} must not define duplicate mTLS mount ${target}`);
      continue;
    }
    observedTargets.add(target);
    if (!expectedTargets.has(target)) {
      fail(`${serviceName} must not receive peer mTLS identity ${filename || "<missing>"}`);
      continue;
    }
    if (mount.type !== "bind" || mount.read_only !== true
        || !source.endsWith(`/deployment/.local/service-identity/mtls/${filename}`)) {
      fail(`${serviceName} must read ${target} from its generated read-only local fixture`);
    }
  }
  for (const target of expectedTargets) {
    if (!observedTargets.has(target)) fail(`${serviceName} is missing required mTLS mount ${target}`);
  }
}

function assertReadOnlyArtifactMount(serviceName, target) {
  const mounts = service(serviceName).volumes ?? [];
  const matching = mounts.filter((volume) => volume?.target === target);
  const mount = matching[0];
  if (matching.length !== 1) {
    fail(`${serviceName} must define exactly one mount at ${target}`);
  }
  if (!mount || mount.type !== "bind" || !mount.source || !mount.read_only) {
    fail(`${serviceName} must define one sourced read-only bind at ${target}`);
  }
  return mount;
}

function assertDeclarativeArtifactMount(file) {
  let source;
  try {
    source = fs.readFileSync(file, "utf8");
  } catch (error) {
    fail(`exact-model Compose overlay cannot be read: ${error.message}`);
    return;
  }
  if (source.includes("\t")) {
    fail("exact-model Compose overlay must not use tab indentation");
    return;
  }

  const lines = source.replaceAll("\r\n", "\n").split("\n");
  const servicesBlock = mappingBlock(lines, "services", 0, 0, lines.length);
  const alertServiceBlock = servicesBlock
    ? mappingBlock(lines, "alert-service", 2, servicesBlock.start, servicesBlock.end)
    : undefined;
  const volumesBlock = alertServiceBlock
    ? mappingBlock(lines, "volumes", 4, alertServiceBlock.start, alertServiceBlock.end)
    : undefined;
  const sentinelFileBlock = alertServiceBlock
    ? mappingBlock(lines, "label_file", 4, alertServiceBlock.start, alertServiceBlock.end)
    : undefined;
  if (!servicesBlock || !alertServiceBlock || !volumesBlock || !sentinelFileBlock) return;

  const sentinelFiles = scalarSequence(lines, sentinelFileBlock.start, sentinelFileBlock.end, 6);
  if (sentinelFiles.length !== 1
      || sentinelFiles[0] !== `${exactModelSource}/.artifact-root-ready.labels`) {
    fail("exact-model overlay must require its pre-provisioned artifact-root sentinel");
  }

  const mounts = longSyntaxSequence(lines, volumesBlock.start, volumesBlock.end, 6);
  if (mounts.length !== 1) {
    fail("alert-service exact-model overlay must declare exactly one long-syntax volume");
    return;
  }
  const mount = mounts[0];
  expectEqual("exact-model source mount type", mount.type, "bind");
  expectEqual("exact-model source mount source", mount.source, exactModelSource);
  expectEqual("exact-model source mount target", mount.target, exactModelTarget);
  if (mount.read_only !== true) {
    fail("exact-model source mount must explicitly declare read_only:true");
  }
  if (mount.bind?.create_host_path !== false) {
    fail("exact-model source mount must explicitly declare bind.create_host_path:false");
  }
}

function mappingBlock(lines, key, indent, start, end) {
  const prefix = `${" ".repeat(indent)}${key}:`;
  const matches = [];
  for (let index = start; index < end; index += 1) {
    if (lines[index].trimEnd() === prefix) matches.push(index);
  }
  if (matches.length !== 1) {
    fail(`exact-model Compose overlay must declare exactly one ${key} mapping at indentation ${indent}`);
    return undefined;
  }
  const blockStart = matches[0] + 1;
  let blockEnd = end;
  for (let index = blockStart; index < end; index += 1) {
    const line = lines[index];
    if (line.trim() === "" || line.trimStart().startsWith("#")) continue;
    if (leadingSpaces(line) <= indent) {
      blockEnd = index;
      break;
    }
  }
  return { start: blockStart, end: blockEnd };
}

function longSyntaxSequence(lines, start, end, itemIndent) {
  const mounts = [];
  let current;
  let nestedKey;
  for (let index = start; index < end; index += 1) {
    const line = lines[index];
    if (line.trim() === "" || line.trimStart().startsWith("#")) continue;
    const indent = leadingSpaces(line);
    const content = line.trim();
    if (indent === itemIndent && content.startsWith("- ")) {
      current = {};
      mounts.push(current);
      nestedKey = undefined;
      assignYamlField(current, content.slice(2), "volume item");
    } else if (current && indent === itemIndent + 2) {
      const [key, value] = yamlField(content, "volume field");
      if (value === undefined) {
        assignUnique(current, key, {}, "volume field");
        nestedKey = key;
      } else {
        assignUnique(current, key, value, "volume field");
        nestedKey = undefined;
      }
    } else if (current && nestedKey && indent === itemIndent + 4) {
      assignYamlField(current[nestedKey], content, `volume ${nestedKey} field`);
    } else {
      fail(`unsupported exact-model volume declaration at source line ${index + 1}`);
    }
  }
  return mounts;
}

function scalarSequence(lines, start, end, itemIndent) {
  const values = [];
  for (let index = start; index < end; index += 1) {
    const line = lines[index];
    if (line.trim() === "" || line.trimStart().startsWith("#")) continue;
    if (leadingSpaces(line) !== itemIndent || !line.trim().startsWith("- ")) {
      fail(`unsupported exact-model scalar sequence at source line ${index + 1}`);
      continue;
    }
    values.push(line.trim().slice(2));
  }
  return values;
}

function assignYamlField(target, content, label) {
  const [key, value] = yamlField(content, label);
  if (value === undefined) {
    fail(`${label} ${key} must have a scalar value`);
    return;
  }
  assignUnique(target, key, value, label);
}

function yamlField(content, label) {
  const separator = content.indexOf(":");
  if (separator <= 0) {
    fail(`${label} must use mapping syntax`);
    return ["<invalid>", undefined];
  }
  const key = content.slice(0, separator).trim();
  const raw = content.slice(separator + 1).trim();
  if (!/^[a-z_]+$/.test(key)) {
    fail(`${label} has unsupported key ${key}`);
  }
  if (raw === "") return [key, undefined];
  if (raw === "true") return [key, true];
  if (raw === "false") return [key, false];
  if ((raw.startsWith('"') && raw.endsWith('"')) || (raw.startsWith("'") && raw.endsWith("'"))) {
    return [key, raw.slice(1, -1)];
  }
  return [key, raw];
}

function assignUnique(target, key, value, label) {
  if (Object.hasOwn(target, key)) {
    fail(`${label} duplicates ${key}`);
    return;
  }
  target[key] = value;
}

function leadingSpaces(line) {
  return line.length - line.trimStart().length;
}

function assertNoOverlappingWritableMounts(serviceName) {
  for (const volume of service(serviceName).volumes ?? []) {
    if (!volume || typeof volume !== "object") {
      fail(`${serviceName} must use structured volume declarations`);
      continue;
    }
    const target = String(volume.target ?? "");
    if (overlapsProtectedGovernanceRoot(target) && volume.read_only !== true) {
      fail(`${serviceName} mount ${target || "<missing>"} must not expose governance evidence as writable`);
    }
  }
  for (const declaration of service(serviceName).tmpfs ?? []) {
    const target = typeof declaration === "string"
      ? declaration.split(":", 1)[0]
      : String(declaration?.target ?? "");
    if (overlapsProtectedGovernanceRoot(target)) {
      fail(`${serviceName} tmpfs ${target || "<missing>"} must not overlap governance evidence`);
    }
  }
}

function overlapsProtectedGovernanceRoot(target) {
  if (!target.startsWith("/")) return false;
  const normalized = path.posix.normalize(target);
  return normalized === "/" || protectedGovernanceRoots.some((root) => normalized === root
    || normalized.startsWith(`${root}/`)
    || root.startsWith(`${normalized}/`));
}

function assertExactModelReaderDisabled() {
  if (String(env("alert-service", "MODEL_SHADOW_PERFORMANCE_ARTIFACTS_ENABLED")) === "true") {
    fail("exact-model shadow reader must remain disabled without its opt-in overlay");
  }
  if ((service("alert-service").volumes ?? [])
      .some((volume) => volume?.target === "/run/model-shadow-performance")) {
    fail("exact-model shadow mount must be absent without its opt-in overlay");
  }
}

function assertExactModelReaderResolved() {
  expectEqual(
    "model shadow performance reader enabled",
    env("alert-service", "MODEL_SHADOW_PERFORMANCE_ARTIFACTS_ENABLED"),
    "true"
  );
  expectEqual(
    "model shadow performance artifact base directory",
    env("alert-service", "MODEL_SHADOW_PERFORMANCE_ARTIFACTS_BASE_DIR"),
    "/run/model-shadow-performance"
  );
  expectEqual(
    "model shadow performance artifact root readiness label",
    service("alert-service").labels?.["com.frauddetection.artifact-root-ready"],
    "true"
  );
  const mount = assertReadOnlyArtifactMount(
    "alert-service",
    exactModelTarget
  );
  assertNoOverlappingWritableMounts("alert-service");
  if (requireExistingSource) {
    assertPreProvisionedSource(mount);
  }
}

function assertPreProvisionedSource(mount) {
  const source = mount?.source;
  if (typeof source !== "string" || source.length === 0) {
    fail("exact-model shadow host source is missing");
    return;
  }
  try {
    const resolvedSource = path.resolve(source);
    let candidate = resolvedSource;
    while (true) {
      if (fs.lstatSync(candidate).isSymbolicLink()) {
        fail(`exact-model shadow host source must not traverse a symbolic link: ${candidate}`);
        return;
      }
      const parent = path.dirname(candidate);
      if (parent === candidate) break;
      candidate = parent;
    }
    if (!fs.lstatSync(resolvedSource).isDirectory()) {
      fail("exact-model shadow host source must be a pre-provisioned directory");
    }
  } catch (error) {
    if (error?.code === "ENOENT") {
      fail(`exact-model shadow host source is not pre-provisioned: ${source}`);
    } else {
      fail(`exact-model shadow host source cannot be inspected: ${error.message}`);
    }
  }
}

if (variant === "model-shadow-performance-source") {
  assertDeclarativeArtifactMount(sourceFile);
  finish();
}

assertNoContainerNames();

if (variant === "base") {
  assertNoPublishedPorts();
  assertExactModelReaderDisabled();
}

if (variant === "dev") {
  if (services.ollama || services["ollama-model-init"]) {
    fail("normal dev stack must not include Ollama services");
  }
  expectEqual("dev ml-inference-service INTERNAL_AUTH_MODE", env("ml-inference-service", "INTERNAL_AUTH_MODE"), "DISABLED_LOCAL_ONLY");
  expectEqual("dev alert-service ASSISTANT_MODE", env("alert-service", "ASSISTANT_MODE"), "DETERMINISTIC");
  assertLocalBindings();
  assertUiLocalPort();
  assertExactModelReaderDisabled();
}

if (variant === "ai") {
  service("ollama");
  service("ollama-model-init");
  expectEqual("AI alert-service ASSISTANT_MODE", env("alert-service", "ASSISTANT_MODE"), "OLLAMA");
  expectEqual("AI alert-service OLLAMA_BASE_URL", env("alert-service", "OLLAMA_BASE_URL"), "http://ollama:11434");
  assertLocalBindings();
  assertUiLocalPort();
  assertExactModelReaderDisabled();
}

if (variant === "security-hardened" || variant === "security-hardened-model-shadow-performance") {
  expectEqual("security ml-inference-service INTERNAL_AUTH_MODE", env("ml-inference-service", "INTERNAL_AUTH_MODE"), "MTLS_SERVICE_IDENTITY");
  expectEqual("security fraud-scoring-service ML_MODEL_BASE_URL", env("fraud-scoring-service", "ML_MODEL_BASE_URL"), "https://ml-inference-service:8090");
  expectEqual("security alert-service ML_GOVERNANCE_BASE_URL", env("alert-service", "ML_GOVERNANCE_BASE_URL"), "https://ml-inference-service:8090");
  expectEqual("security alert-service APP_SECURITY_DEMO_AUTH_ENABLED", env("alert-service", "APP_SECURITY_DEMO_AUTH_ENABLED"), "false");
  expectEqual("security alert-service APP_SECURITY_BFF_ENABLED", env("alert-service", "APP_SECURITY_BFF_ENABLED"), "true");
  expectEqual("security alert-service AUDIT_TRUST_AUTHORITY_ENABLED", env("alert-service", "AUDIT_TRUST_AUTHORITY_ENABLED"), "true");
  expectEqual("security alert-service AUDIT_TRUST_AUTHORITY_SIGNING_REQUIRED", env("alert-service", "AUDIT_TRUST_AUTHORITY_SIGNING_REQUIRED"), "true");
  expectEqual("security audit-trust-authority TRUST_AUTHORITY_IDENTITY_MODE", env("audit-trust-authority", "TRUST_AUTHORITY_IDENTITY_MODE"), "jwt-service-identity");
  expectEqual("security alert-service ASSISTANT_MODE", env("alert-service", "ASSISTANT_MODE"), "DETERMINISTIC");
  expectEqual("security analyst-console-ui VITE_AUTH_PROVIDER", service("analyst-console-ui").build?.args?.VITE_AUTH_PROVIDER, "bff");
  assertMtlsIdentityMounts("ml-inference-service", [
    "local-dev-ca.pem", "ml-inference-service.pem", "ml-inference-service-key.pem"
  ]);
  assertMtlsIdentityMounts("fraud-scoring-service", [
    "local-dev-ca.pem", "fraud-scoring-service.pem", "fraud-scoring-service-key.pem"
  ]);
  assertMtlsIdentityMounts("alert-service", [
    "local-dev-ca.pem", "alert-service.pem", "alert-service-key.pem"
  ]);
  assertGeneratedIdentityMount("alert-service", "/run/service-identity/alert-service-private.pem", "/deployment/.local/service-identity/alert-service-private.pem");
  assertGeneratedIdentityMount("audit-trust-authority", "/run/service-identity/jwks.json", "/deployment/.local/service-identity/jwks.json");
  assertLocalBindings();
  assertUiLocalPort();
  assertHardening();
  if (variant === "security-hardened") {
    assertExactModelReaderDisabled();
  }
}

if (variant === "model-shadow-performance" || variant === "security-hardened-model-shadow-performance") {
  assertExactModelReaderResolved();
}

finish();

function finish() {
  if (failures.length > 0) {
    console.error(`Compose security assertion failed for ${variant}:`);
    for (const failure of failures) {
      console.error(`- ${failure}`);
    }
    process.exit(1);
  }

  console.log(`Compose security assertions passed for ${variant}.`);
  process.exit(0);
}
