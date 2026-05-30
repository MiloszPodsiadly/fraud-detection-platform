import fs from "node:fs";

const variant = process.argv[2];
const supportedVariants = new Set(["base", "dev", "ai", "security-hardened"]);

if (!supportedVariants.has(variant)) {
  console.error("Usage: node scripts/check-compose-security-config.mjs <base|dev|ai|security-hardened>");
  process.exit(2);
}

let config;
try {
  config = JSON.parse(fs.readFileSync(0, "utf8"));
} catch (error) {
  console.error(`Resolved Compose config must be JSON on stdin: ${error.message}`);
  process.exit(2);
}

const services = config.services ?? {};
const failures = [];

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

assertNoContainerNames();

if (variant === "base") {
  assertNoPublishedPorts();
}

if (variant === "dev") {
  if (services.ollama || services["ollama-model-init"]) {
    fail("normal dev stack must not include Ollama services");
  }
  expectEqual("dev ml-inference-service INTERNAL_AUTH_MODE", env("ml-inference-service", "INTERNAL_AUTH_MODE"), "DISABLED_LOCAL_ONLY");
  expectEqual("dev alert-service ASSISTANT_MODE", env("alert-service", "ASSISTANT_MODE"), "DETERMINISTIC");
  assertLocalBindings();
  assertUiLocalPort();
}

if (variant === "ai") {
  service("ollama");
  service("ollama-model-init");
  expectEqual("AI alert-service ASSISTANT_MODE", env("alert-service", "ASSISTANT_MODE"), "OLLAMA");
  expectEqual("AI alert-service OLLAMA_BASE_URL", env("alert-service", "OLLAMA_BASE_URL"), "http://ollama:11434");
  assertLocalBindings();
  assertUiLocalPort();
}

if (variant === "security-hardened") {
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
  assertGeneratedIdentityMount("ml-inference-service", "/run/service-identity/mtls", "/deployment/.local/service-identity/mtls");
  assertGeneratedIdentityMount("fraud-scoring-service", "/run/service-identity/mtls", "/deployment/.local/service-identity/mtls");
  assertGeneratedIdentityMount("alert-service", "/run/service-identity/mtls", "/deployment/.local/service-identity/mtls");
  assertGeneratedIdentityMount("alert-service", "/run/service-identity/alert-service-private.pem", "/deployment/.local/service-identity/alert-service-private.pem");
  assertGeneratedIdentityMount("audit-trust-authority", "/run/service-identity/jwks.json", "/deployment/.local/service-identity/jwks.json");
  assertLocalBindings();
  assertUiLocalPort();
  assertHardening();
}

if (failures.length > 0) {
  console.error(`Resolved Compose security assertion failed for ${variant}:`);
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(`Resolved Compose security assertions passed for ${variant}.`);
