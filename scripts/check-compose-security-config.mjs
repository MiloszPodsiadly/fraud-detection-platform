import fs from "node:fs";
import path from "node:path";

const variant = process.argv[2];
const requireExistingSource = process.argv.includes("--require-existing-source");
const sourceFile = optionValue("--source-file");
const resolvedSourceFile = optionValue("--write-resolved-source");
const exactModelTarget = "/run/model-shadow-performance";
const exactModelSource = "${MODEL_SHADOW_PERFORMANCE_ARTIFACT_ROOT:-./local-generated/model-shadow-performance}";
const protectedGovernanceRoots = [
  "/run/model-card",
  "/run/model-promotion-policy",
  "/run/model-shadow-performance",
  "/run/promotion-readiness",
  "/run/shadow-performance"
];
const supportedVariants = new Set([
  "base",
  "dev",
  "ai",
  "security-hardened",
  "key-loader-source",
  "model-shadow-performance-source",
  "model-shadow-performance",
  "security-hardened-model-shadow-performance"
]);

if (!supportedVariants.has(variant)) {
  console.error(
    "Usage: node scripts/check-compose-security-config.mjs <variant> "
      + "[--source-file <compose.yml>] [--require-existing-source]"
      + " [--write-resolved-source <file>]"
  );
  process.exit(2);
}

if (variant === "model-shadow-performance-source" && !sourceFile) {
  console.error("model-shadow-performance-source requires --source-file");
  process.exit(2);
}

let config = { services: {} };
if (variant !== "model-shadow-performance-source" && variant !== "key-loader-source") {
  try {
    config = JSON.parse(fs.readFileSync(0, "utf8"));
  } catch (error) {
    console.error(`Resolved Compose config must be JSON on stdin: ${error.message}`);
    process.exit(2);
  }
}

const services = config.services ?? {};
const failures = [];
let resolvedExactModelSource;

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

function expectResolved(label, actual, expected) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label}: expected ${formatResolved(expected)}, resolved ${formatResolved(actual)}`);
  }
}

function expectFalseOrRendererOmitted(label, actual) {
  if (actual !== false && actual !== undefined) {
    fail(`${label}: expected false or renderer omission backed by explicit source policy, resolved ${formatResolved(actual)}`);
  }
}

function expectNotResolved(label, actual, forbidden) {
  if (actual === forbidden) {
    fail(`${label}: expected a value other than ${formatResolved(forbidden)}, resolved ${formatResolved(actual)}`);
  }
}

function formatResolved(value) {
  return value === undefined ? "undefined" : JSON.stringify(value);
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
    "model-promotion-policy-publisher",
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

function assertModelPromotionPolicyTrustMount() {
  const serviceName = "alert-service";
  const sourceRoot = path.resolve("deployment", ".local", "model-promotion-policy");
  const realSourceRoot = realPathIfPresent(sourceRoot);
  const targetRoot = "/run/model-promotion-policy";
  const expected = new Map([
    [`${targetRoot}/trusted-issuers.json`, path.join(sourceRoot, "trusted-issuers.json")],
    [`${targetRoot}/trusted-approvals.json`, path.join(sourceRoot, "approval", "trusted-approvals.json")],
    [`${targetRoot}/online-actor-trust.json`, path.join(sourceRoot, "approval", "online-actor-trust.json")],
    [`${targetRoot}/trusted-reporters.json`, path.join(sourceRoot, "fleet-observation", "trusted-reporters.json")]
  ]);
  assertDeclarativeModelPromotionPolicyTrustMount();
  expectEqual("model-promotion trusted issuer path",
    env(serviceName, "MODEL_PROMOTION_TRUSTED_ISSUERS_PATH"), `${targetRoot}/trusted-issuers.json`);
  expectEqual("model-promotion minimum trust generation",
    env(serviceName, "MODEL_PROMOTION_TRUST_MIN_GENERATION"), "1");
  expectEqual("model-promotion approval trust path",
    env(serviceName, "MODEL_PROMOTION_APPROVAL_TRUST_PATH"), `${targetRoot}/trusted-approvals.json`);
  expectEqual("model-promotion actor trust path",
    env(serviceName, "MODEL_PROMOTION_ONLINE_ACTOR_TRUST_PATH"), `${targetRoot}/online-actor-trust.json`);
  const mounts = (service(serviceName).volumes ?? []).filter((mount) => {
    if (mount?.type !== "bind") return pathsOverlap(String(mount?.target ?? ""), targetRoot);
    return pathsOverlap(String(mount.source ?? ""), sourceRoot)
      || pathsOverlap(realPathIfPresent(String(mount.source ?? "")), realSourceRoot)
      || pathsOverlap(String(mount.target ?? ""), targetRoot);
  });
  if (mounts.length !== expected.size) {
    fail("alert-service must mount exactly four public model-promotion registries, never a private key or directory");
  }
  const seen = new Set();
  for (const mount of mounts) {
    const target = normalizeContainerPath(mount.target);
    const expectedSource = expected.get(target);
    if (seen.has(target)) fail(`duplicate model-promotion public registry mount ${target}`);
    seen.add(target);
    if (!expectedSource || mount.type !== "bind" || mount.read_only !== true
        || normalizeComparablePath(mount.source) !== normalizeComparablePath(expectedSource)
        || (mount.bind?.create_host_path !== false && mount.bind?.create_host_path !== undefined)) {
      fail(`alert-service model-promotion registry mount ${target} must be the exact read-only public file`);
      continue;
    }
    let candidate = expectedSource;
    while (true) {
      try {
        const stat = fs.lstatSync(candidate);
        if (stat.isSymbolicLink()) {
          fail(`model-promotion public registry source must not traverse symbolic link ${candidate}`);
          break;
        }
        if (candidate === expectedSource && !stat.isFile()) {
          fail("model-promotion public registry source must be a regular file");
        }
      } catch (error) {
        if (error?.code === "ENOENT") {
          fail(`model-promotion public registry source is missing: ${candidate}`);
        } else {
          fail(`model-promotion public registry source cannot be inspected: ${error.message}`);
        }
        break;
      }
      const parent = path.dirname(candidate);
      if (parent === candidate) break;
      candidate = parent;
    }
  }
  for (const target of expected.keys()) {
    if (!seen.has(target)) fail(`alert-service is missing model-promotion registry ${target}`);
  }
}

function assertModelPromotionPublisherIsolation() {
  const name = "model-promotion-policy-publisher";
  const loaderName = "model-promotion-policy-key-loader";
  const root = path.resolve("deployment", ".local", "model-promotion-policy");
  const mounts = service(name).volumes ?? [];
  if (mounts.length !== 1 || (service(name).ports ?? []).length
      || service(name).user !== "10001:10001") {
    fail("publisher must run as UID/GID 10001, have only one private volume and no published port");
  }
  const seen = new Set();
  for (const mount of mounts) {
    const target = normalizeContainerPath(mount.target);
    if (seen.has(target)) fail(`publisher has duplicate mount ${target}`);
    seen.add(target);
    if (target === "/run/policy-publication") {
      if (mount.type !== "volume" || mount.source !== "model-promotion-policy-signing-keys"
          || mount.read_only !== true) {
        fail("publisher signing volume must be private and read-only");
      }
      continue;
    }
    fail(`publisher must not have an additional mount ${target}`);
  }
  if (!seen.has("/run/policy-publication")) fail("publisher is missing its private signing volume");
  const loader = service(loaderName);
  const loaderMounts = loader.volumes ?? [];
  const signingSources = new Map([
    ["/run/policy-publication-source/publisher.private-key.pkcs8.base64",
      path.join(root, "approval", "publisher.private-key.pkcs8.base64")],
    ["/run/policy-publication-source/issuer.private-key.pkcs8.base64",
      path.join(root, "private-key.pkcs8.base64")],
    ["/run/policy-publication-source/trusted-approvals.json",
      path.join(root, "approval", "trusted-approvals.json")],
    ["/run/policy-publication-source/online-actor-trust.json",
      path.join(root, "approval", "online-actor-trust.json")],
    ["/run/policy-publication-source/trusted-issuers.json",
      path.join(root, "trusted-issuers.json")],
    ["/run/policy-publication-source/observation.private-key.pkcs8.base64",
      path.join(root, "fleet-observation", "publisher.private-key.pkcs8.base64")],
    ["/run/policy-publication-source/trusted-reporters.json",
      path.join(root, "fleet-observation", "trusted-reporters.json")]
  ]);
  if (loaderMounts.length !== signingSources.size + 1 || (loader.ports ?? []).length || loader.network_mode !== "none") {
    fail("one-shot key loader must have exact source files, one private volume, no network and no ports");
  }
  if (loader.restart !== "no" || loader.read_only !== true
      || loader.pids_limit !== 64
      || !(loader.cap_drop ?? []).includes("ALL")
      || JSON.stringify((loader.cap_add ?? []).slice().sort()) !== JSON.stringify(["CHOWN", "DAC_OVERRIDE", "FOWNER"])
      || !(loader.security_opt ?? []).includes("no-new-privileges:true")) {
    fail("key loader must be one-shot, read-only and prohibit privilege escalation");
  }
  const observedLoaderTargets = new Set();
  for (const mount of loaderMounts) {
    const target = normalizeContainerPath(mount.target);
    if (observedLoaderTargets.has(target)) fail(`key loader has duplicate mount ${target}`);
    observedLoaderTargets.add(target);
    if (target === "/run/policy-publication") {
      if (mount.type !== "volume" || mount.source !== "model-promotion-policy-signing-keys"
          || mount.read_only === true) fail("key loader needs only the private writable signing volume");
      continue;
    }
    const source = signingSources.get(target);
    if (!source || mount.type !== "bind" || mount.read_only !== true
        || normalizeComparablePath(mount.source) !== normalizeComparablePath(source)
        || (mount.bind?.create_host_path !== false && mount.bind?.create_host_path !== undefined)) {
      fail(`key loader mount ${target} must be an exact read-only source file`);
    }
  }
  for (const [target, source] of signingSources) {
    if (!observedLoaderTargets.has(target)) fail(`key loader is missing signing source ${target}`);
    try {
      const entry = fs.lstatSync(source);
      const isPrivate = target.endsWith("private-key.pkcs8.base64");
      if (!entry.isFile() || entry.isSymbolicLink() || entry.size === 0
          || (isPrivate && process.platform !== "win32" && (entry.mode & 0o077) !== 0)) {
        fail(`signing source is missing, linked or insufficiently protected: ${source}`);
      }
    } catch (error) {
      fail(`signing source cannot be inspected: ${source}: ${error.message}`);
    }
  }
  if (!observedLoaderTargets.has("/run/policy-publication")) fail("key loader is missing the signing volume");
  if (service(name).depends_on?.[loaderName]?.condition !== "service_completed_successfully") {
    fail("publisher must wait for successful private-key loading");
  }
  for (const [otherName, definition] of Object.entries(services)) {
    if (otherName === loaderName) continue;
    for (const mount of definition.volumes ?? []) {
      const source = normalizeComparablePath(mount.source);
      if (source === normalizeComparablePath(path.join(root, "private-key.pkcs8.base64"))
          || source === normalizeComparablePath(path.join(root, "approval", "publisher.private-key.pkcs8.base64"))
          || (mount.type === "volume" && mount.source === "model-promotion-policy-signing-keys"
            && otherName !== name)) {
        fail(`${otherName} must not mount a model-promotion private signing key`);
      }
    }
  }
}

function assertObservationKeyIsolation() {
  const alert = service("alert-service");
  const loaderName = "model-promotion-observation-key-loader";
  const loader = service(loaderName);
  const volume = "model-promotion-alert-observation-key";
  const root = path.resolve("deployment", ".local", "model-promotion-policy", "fleet-observation");
  const privateSource = path.join(root, "alert.private-key.pkcs8.base64");
  const alertMount = (alert.volumes ?? []).find(mount => mount.target === "/run/policy-observation");
  expectResolved("alert-service observation volume type", alertMount?.type, "volume");
  expectResolved("alert-service observation volume source", alertMount?.source, volume);
  expectResolved("alert-service observation volume read_only", alertMount?.read_only, true);
  expectResolved("alert-service observation loader dependency",
    alert.depends_on?.[loaderName]?.condition, "service_completed_successfully");
  const loaderMounts = loader.volumes ?? [];
  const source = loaderMounts.find(mount => mount.target === "/run/observation-source/alert.private-key.pkcs8.base64");
  const destination = loaderMounts.find(mount => mount.target === "/run/policy-observation");
  expectResolved(`${loaderName} profile list`, loader.profiles ?? [], []);
  expectResolved(`${loaderName} mount count`, loaderMounts.length, 2);
  expectResolved(`${loaderName}.network_mode`, loader.network_mode, "none");
  expectResolved(`${loaderName} published ports`, loader.ports ?? [], []);
  expectResolved(`${loaderName}.restart`, loader.restart, "no");
  expectResolved(`${loaderName}.read_only`, loader.read_only, true);
  expectResolved(`${loaderName}.pids_limit`, loader.pids_limit, 64);
  expectNotResolved(`${loaderName}.privileged`, loader.privileged, true);
  expectResolved(`${loaderName}.cap_drop`, (loader.cap_drop ?? []).slice().sort(), ["ALL"]);
  expectResolved(`${loaderName}.cap_add`, (loader.cap_add ?? []).slice().sort(),
    ["CHOWN", "DAC_OVERRIDE", "FOWNER"]);
  expectResolved(`${loaderName}.security_opt`, loader.security_opt ?? [], ["no-new-privileges:true"]);
  expectResolved(`${loaderName}.tmpfs`, loader.tmpfs ?? [], ["/tmp:rw,noexec,nosuid,size=6m"]);
  expectResolved(`${loaderName} private source mount type`, source?.type, "bind");
  expectResolved(`${loaderName} private source mount target`, source?.target,
    "/run/observation-source/alert.private-key.pkcs8.base64");
  expectResolved(`${loaderName} private source mount read_only`, source?.read_only, true);
  expectResolved(`${loaderName} private source path`, normalizeComparablePath(source?.source),
    normalizeComparablePath(privateSource));
  expectFalseOrRendererOmitted(`${loaderName} private source create_host_path`, source?.bind?.create_host_path);
  expectResolved(`${loaderName} destination mount type`, destination?.type, "volume");
  expectResolved(`${loaderName} destination mount source`, destination?.source, volume);
  expectResolved(`${loaderName} destination mount target`, destination?.target, "/run/policy-observation");
  expectNotResolved(`${loaderName} destination mount read_only`, destination?.read_only, true);
  try {
    const stat = fs.lstatSync(privateSource);
    if (!stat.isFile() || stat.isSymbolicLink() || !stat.size
        || (process.platform !== "win32" && (stat.mode & 0o077) !== 0)) {
      fail("alert observation private source is missing or insufficiently protected");
    }
  } catch (error) {
    fail(`alert observation private source cannot be inspected: ${error.message}`);
  }
  for (const [name, definition] of Object.entries(services)) {
    if (name === loaderName || name === "alert-service") continue;
    for (const mount of definition.volumes ?? []) {
      if (normalizeComparablePath(mount.source) === normalizeComparablePath(privateSource)
          || (mount.type === "volume" && mount.source === volume)) {
        fail(`${name} must not mount the alert observation private key`);
      }
    }
  }
}

function assertAuditTrustKeyIsolation() {
  const name = "audit-trust-authority";
  const loaderName = "audit-trust-key-loader";
  const volume = "audit-trust-keys";
  const sourceRoot = path.resolve("deployment", ".local", "audit-trust-authority");
  const authority = service(name);
  const loader = service(loaderName);
  const authorityMount = (authority.volumes ?? []).find(mount => mount.target === "/run/audit-trust-keys");
  expectResolved(`${name} loader dependency`, authority.depends_on?.[loaderName]?.condition,
    "service_completed_successfully");
  expectResolved(`${name} key volume type`, authorityMount?.type, "volume");
  expectResolved(`${name} key volume source`, authorityMount?.source, volume);
  expectResolved(`${name} key volume target`, authorityMount?.target, "/run/audit-trust-keys");
  expectResolved(`${name} key volume read_only`, authorityMount?.read_only, true);
  expectResolved(`${name}.TRUST_AUTHORITY_ACTIVE_PRIVATE_KEY_PATH`,
    env(name, "TRUST_AUTHORITY_ACTIVE_PRIVATE_KEY_PATH"), "/run/audit-trust-keys/ed25519-private.key");
  expectResolved(`${name}.TRUST_AUTHORITY_ACTIVE_PUBLIC_KEY_PATH`,
    env(name, "TRUST_AUTHORITY_ACTIVE_PUBLIC_KEY_PATH"), "/run/audit-trust-keys/ed25519-public.key");
  const source = (loader.volumes ?? []).find(mount => mount.target === "/run/audit-trust-source");
  const destination = (loader.volumes ?? []).find(mount => mount.target === "/run/audit-trust-keys");
  expectResolved(`${loaderName} profile list`, loader.profiles ?? [], []);
  expectResolved(`${loaderName} mount count`, (loader.volumes ?? []).length, 2);
  expectResolved(`${loaderName}.network_mode`, loader.network_mode, "none");
  expectResolved(`${loaderName} published ports`, loader.ports ?? [], []);
  expectResolved(`${loaderName}.restart`, loader.restart, "no");
  expectResolved(`${loaderName}.read_only`, loader.read_only, true);
  expectResolved(`${loaderName}.pids_limit`, loader.pids_limit, 64);
  expectNotResolved(`${loaderName}.privileged`, loader.privileged, true);
  expectResolved(`${loaderName}.cap_drop`, (loader.cap_drop ?? []).slice().sort(), ["ALL"]);
  expectResolved(`${loaderName}.cap_add`, (loader.cap_add ?? []).slice().sort(),
    ["CHOWN", "DAC_OVERRIDE", "FOWNER"]);
  expectResolved(`${loaderName}.security_opt`, loader.security_opt ?? [], ["no-new-privileges:true"]);
  expectResolved(`${loaderName}.tmpfs`, loader.tmpfs ?? [], ["/tmp:rw,noexec,nosuid,size=6m"]);
  expectResolved(`${loaderName} source mount type`, source?.type, "bind");
  expectResolved(`${loaderName} source mount target`, source?.target, "/run/audit-trust-source");
  expectResolved(`${loaderName} source mount read_only`, source?.read_only, true);
  expectResolved(`${loaderName} protected source path`, normalizeComparablePath(source?.source),
    normalizeComparablePath(sourceRoot));
  expectFalseOrRendererOmitted(`${loaderName} protected source create_host_path`, source?.bind?.create_host_path);
  expectResolved(`${loaderName} destination mount type`, destination?.type, "volume");
  expectResolved(`${loaderName} destination mount source`, destination?.source, volume);
  expectResolved(`${loaderName} destination mount target`, destination?.target, "/run/audit-trust-keys");
  expectNotResolved(`${loaderName} destination mount read_only`, destination?.read_only, true);
  for (const file of ["ed25519-private.key", "ed25519-public.key"]) {
    try {
      const stat = fs.lstatSync(path.join(sourceRoot, file));
      if (!stat.isFile() || stat.isSymbolicLink() || !stat.size
          || (file === "ed25519-private.key" && process.platform !== "win32" && (stat.mode & 0o077) !== 0)) {
        fail(`audit trust ${file} is missing or insufficiently protected`);
      }
    } catch (error) {
      fail(`audit trust ${file} cannot be inspected: ${error.message}`);
    }
  }
  for (const [otherName, definition] of Object.entries(services)) {
    if (otherName === loaderName || otherName === name) continue;
    for (const mount of definition.volumes ?? []) {
      if (normalizeComparablePath(mount.source) === normalizeComparablePath(sourceRoot)
          || (mount.type === "volume" && mount.source === volume)) {
        fail(`${otherName} must not mount audit trust authority keys`);
      }
    }
  }
}

function assertDeclarativeModelPromotionPolicyTrustMount() {
  const file = path.resolve("deployment", "docker-compose.model-promotion-policy-trust.yml");
  let source;
  try {
    source = fs.readFileSync(file, "utf8");
  } catch (error) {
    fail(`model-promotion trust overlay cannot be read: ${error.message}`);
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
  if (!volumesBlock) return;
  const mounts = longSyntaxSequence(lines, volumesBlock.start, volumesBlock.end, 6);
  const expected = new Map([
    ["/run/model-promotion-policy/trusted-issuers.json", "./.local/model-promotion-policy/trusted-issuers.json"],
    ["/run/model-promotion-policy/trusted-approvals.json", "./.local/model-promotion-policy/approval/trusted-approvals.json"],
    ["/run/model-promotion-policy/online-actor-trust.json", "./.local/model-promotion-policy/approval/online-actor-trust.json"],
    ["/run/model-promotion-policy/trusted-reporters.json", "./.local/model-promotion-policy/fleet-observation/trusted-reporters.json"]
  ]);
  const publicMounts = mounts.filter((mount) => mount.type === "bind");
  if (publicMounts.length !== expected.size || publicMounts.some((mount) => mount.type !== "bind"
      || expected.get(mount.target) !== mount.source
      || mount.read_only !== true || mount.bind?.create_host_path !== false)) {
    fail("model-promotion trust overlay must declare four exact read-only public file binds");
  }
  const privateMounts = mounts.filter((mount) => mount.type !== "bind");
  if (privateMounts.length !== 1 || privateMounts[0].type !== "volume"
      || privateMounts[0].source !== "model-promotion-alert-observation-key"
      || privateMounts[0].target !== "/run/policy-observation"
      || privateMounts[0].read_only !== true) {
    fail("alert-service must read only its dedicated private observation volume");
  }
}

function assertMtlsIdentityMounts(serviceName, filenames) {
  const targetRoot = "/run/service-identity/mtls";
  const sourceRoot = path.resolve("deployment", ".local", "service-identity", "mtls");
  const realSourceRoot = realPathIfPresent(sourceRoot);
  const expected = new Map(filenames.map((filename) => [
    `${targetRoot}/${filename}`,
    path.join(sourceRoot, filename)
  ]));
  const mounts = (service(serviceName).volumes ?? []).filter((mount) => {
    if (mount?.type !== "bind") return false;
    const source = path.resolve(String(mount.source ?? ""));
    const target = normalizeContainerPath(mount.target);
    const realSource = realPathIfPresent(source);
    return pathsOverlap(source, sourceRoot)
      || pathsOverlap(realSource, realSourceRoot)
      || pathsOverlap(target, targetRoot);
  });
  const observedTargets = new Set();
  if (mounts.length !== expected.size) {
    fail(`${serviceName} must expose only its CA, certificate and private key mTLS files`);
  }
  for (const mount of mounts) {
    const target = normalizeContainerPath(mount?.target);
    const source = path.resolve(String(mount?.source ?? ""));
    if (observedTargets.has(target)) {
      fail(`${serviceName} must not define duplicate mTLS mount ${target}`);
      continue;
    }
    observedTargets.add(target);
    const expectedSource = expected.get(target);
    if (!expectedSource) {
      fail(`${serviceName} must not expose mTLS identity material at ${target || "<missing>"}`);
      continue;
    }
    if (mount.read_only !== true || normalizeHostPath(source) !== normalizeHostPath(expectedSource)) {
      fail(`${serviceName} must read ${target} from its generated read-only local fixture`);
    }
    assertSafeIdentitySource(serviceName, source, expectedSource);
  }
  for (const target of expected.keys()) {
    if (!observedTargets.has(target)) fail(`${serviceName} is missing required mTLS mount ${target}`);
  }
}

function assertSafeIdentitySource(serviceName, source, expectedSource) {
  if (normalizeHostPath(realPathIfPresent(source)) !== normalizeHostPath(realPathIfPresent(expectedSource))) {
    fail(`${serviceName} mTLS source must not use an alias or symbolic-link traversal: ${source}`);
    return;
  }
  let candidate = source;
  while (true) {
    try {
      if (fs.lstatSync(candidate).isSymbolicLink()) {
        fail(`${serviceName} mTLS source must not traverse symbolic link ${candidate}`);
        return;
      }
    } catch (error) {
      if (error?.code === "ENOENT") {
        fail(`${serviceName} mTLS source is missing: ${source}`);
      } else {
        fail(`${serviceName} mTLS source cannot be inspected: ${error.message}`);
      }
      return;
    }
    const parent = path.dirname(candidate);
    if (parent === candidate) return;
    candidate = parent;
  }
}

function realPathIfPresent(value) {
  try {
    return fs.realpathSync(value);
  } catch {
    return path.resolve(value);
  }
}

function normalizeHostPath(value) {
  const normalized = path.resolve(String(value)).replaceAll("\\", "/");
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
}

function normalizeContainerPath(value) {
  return path.posix.normalize(String(value ?? ""));
}

function pathsOverlap(left, right) {
  const first = normalizeComparablePath(left);
  const second = normalizeComparablePath(right);
  return first === second || first.startsWith(`${second}/`) || second.startsWith(`${first}/`);
}

function normalizeComparablePath(value) {
  const normalized = String(value ?? "").replaceAll("\\", "/").replace(/\/$/, "");
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
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

function assertDeclarativeKeyLoaderSources() {
  assertDeclarativeKeyLoaderMount(
    path.resolve("deployment", "docker-compose.yml"),
    "audit-trust-key-loader",
    "./.local/audit-trust-authority",
    "/run/audit-trust-source",
    "audit-trust-keys",
    "/run/audit-trust-keys"
  );
  assertDeclarativeKeyLoaderMount(
    path.resolve("deployment", "docker-compose.model-promotion-policy-trust.yml"),
    "model-promotion-observation-key-loader",
    "./.local/model-promotion-policy/fleet-observation/alert.private-key.pkcs8.base64",
    "/run/observation-source/alert.private-key.pkcs8.base64",
    "model-promotion-alert-observation-key",
    "/run/policy-observation"
  );
}

function assertDeclarativeKeyLoaderMount(file, serviceName, sourcePath, sourceTarget, volumeName, volumeTarget) {
  let source;
  try {
    source = fs.readFileSync(file, "utf8");
  } catch (error) {
    fail(`${serviceName} source overlay cannot be read: ${error.message}`);
    return;
  }
  if (source.includes("\t")) {
    fail(`${serviceName} source overlay must not use tab indentation`);
    return;
  }
  const lines = source.replaceAll("\r\n", "\n").split("\n");
  const loaderHeaders = lines.flatMap((line, index) =>
    leadingSpaces(line) === 2 && line.trim() === `${serviceName}:` ? [index] : []);
  if (loaderHeaders.length !== 1) {
    fail(`${serviceName} source overlay must declare exactly one service`);
    return;
  }
  const serviceStart = loaderHeaders[0] + 1;
  let serviceEnd = lines.length;
  for (let index = serviceStart; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() && !line.trimStart().startsWith("#") && leadingSpaces(line) <= 2) {
      serviceEnd = index;
      break;
    }
  }
  const volumeHeaders = lines.flatMap((line, index) =>
    index >= serviceStart && index < serviceEnd
      && leadingSpaces(line) === 4 && line.trim() === "volumes:" ? [index] : []);
  if (volumeHeaders.length !== 1) {
    fail(`${serviceName} source overlay must declare exactly one volumes block`);
    return;
  }
  const volumesStart = volumeHeaders[0] + 1;
  let volumesEnd = serviceEnd;
  for (let index = volumesStart; index < serviceEnd; index += 1) {
    const line = lines[index];
    if (line.trim() && !line.trimStart().startsWith("#") && leadingSpaces(line) <= 4) {
      volumesEnd = index;
      break;
    }
  }
  const mounts = longSyntaxSequence(lines, volumesStart, volumesEnd, 6);
  if (mounts.length !== 2) {
    fail(`${serviceName} source overlay must declare exactly one protected bind and one dedicated key volume`);
    return;
  }
  const sourceMounts = mounts.filter((mount) => mount.target === sourceTarget);
  const keyMounts = mounts.filter((mount) => mount.target === volumeTarget);
  if (sourceMounts.length !== 1 || sourceMounts[0].type !== "bind"
      || sourceMounts[0].source !== sourcePath || sourceMounts[0].read_only !== true
      || sourceMounts[0].bind?.create_host_path !== false) {
    fail(`${serviceName} source overlay must explicitly bind ${sourcePath} read-only to ${sourceTarget} with bind.create_host_path:false`);
  }
  if (keyMounts.length !== 1 || keyMounts[0].type !== "volume"
      || keyMounts[0].source !== volumeName || keyMounts[0].read_only === true) {
    fail(`${serviceName} source overlay must use its dedicated writable ${volumeName} key volume at ${volumeTarget}`);
  }
}

function assertKeyLoaderFixtures() {
  const auditRoot = path.resolve("deployment", ".local", "audit-trust-authority");
  assertProtectedKeyFixture(path.join(auditRoot, "ed25519-private.key"), "audit trust private key", true);
  assertProtectedKeyFixture(path.join(auditRoot, "ed25519-public.key"), "audit trust public key", false);
  const observationKey = path.resolve("deployment", ".local", "model-promotion-policy",
    "fleet-observation", "alert.private-key.pkcs8.base64");
  assertProtectedKeyFixture(observationKey, "model-promotion observation private key", true);
}

function assertProtectedKeyFixture(file, label, privateKey) {
  let candidate = path.resolve(file);
  const ancestors = [];
  while (true) {
    ancestors.push(candidate);
    const parent = path.dirname(candidate);
    if (parent === candidate) break;
    candidate = parent;
  }
  for (const entryPath of ancestors.reverse()) {
    try {
      if (fs.lstatSync(entryPath).isSymbolicLink()) {
        fail(`${label} path must not traverse a symbolic link`);
        return;
      }
    } catch (error) {
      fail(error?.code === "ENOENT"
        ? `${label} is missing`
        : `${label} cannot be inspected: ${error.message}`);
      return;
    }
  }
  try {
    const stat = fs.lstatSync(file);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0) {
      fail(`${label} must be a non-empty regular file`);
    }
    if (privateKey && process.platform !== "win32" && (stat.mode & 0o077) !== 0) {
      fail(`${label} must not grant group or other access`);
    }
  } catch (error) {
    fail(`${label} cannot be inspected: ${error.message}`);
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
  if (mount?.source) {
    resolvedExactModelSource = path.resolve(mount.source);
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

if (variant === "key-loader-source") {
  assertDeclarativeKeyLoaderSources();
  assertKeyLoaderFixtures();
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
  assertDeclarativeKeyLoaderSources();
  assertKeyLoaderFixtures();
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
  assertModelPromotionPolicyTrustMount();
  assertModelPromotionPublisherIsolation();
  assertObservationKeyIsolation();
  assertAuditTrustKeyIsolation();
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

  if (resolvedSourceFile) {
    if (!resolvedExactModelSource) {
      console.error("Resolved exact-model source is unavailable for export.");
      process.exit(1);
    }
    fs.writeFileSync(resolvedSourceFile, resolvedExactModelSource, { encoding: "utf8", mode: 0o600 });
  }

  console.log(`Compose security assertions passed for ${variant}.`);
  process.exit(0);
}
