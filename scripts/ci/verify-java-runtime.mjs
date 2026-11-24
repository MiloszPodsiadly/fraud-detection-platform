import { spawnSync } from "node:child_process";
import { accessSync, constants, realpathSync } from "node:fs";
import { delimiter, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

export const APPROVED_RUNTIME = Object.freeze({
  vendor: "Eclipse Adoptium",
  version: [21, 0, 13, 1],
  versionText: "21.0.13.1",
  build: 2,
  runtimePrefix: "21.0.13.1+2",
  linuxX64JdkSha256: "bb8a93f9ae6e7a693b2b721e85dbeff26ae1a77447985a8d7afcdd9c7cf6222e",
  releaseUrl: "https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13.1%2B2"
});

export function parseJavaVersion(value) {
  const match = String(value ?? "").trim().match(/^(\d+(?:\.\d+){0,3})(?:\+(\d+))?(?:-([A-Za-z0-9.-]+))?$/);
  if (!match) throw new Error(`Malformed Java version: ${String(value)}`);
  return {
    components: match[1].split(".").map(Number),
    build: match[2] === undefined ? null : Number(match[2]),
    suffix: match[3] ?? null
  };
}

export function compareNumericVersions(left, right) {
  const leftParts = Array.isArray(left) ? left : parseJavaVersion(left).components;
  const rightParts = Array.isArray(right) ? right : parseJavaVersion(right).components;
  const width = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < width; index += 1) {
    const a = leftParts[index] ?? 0;
    const b = rightParts[index] ?? 0;
    if (a !== b) return a < b ? -1 : 1;
  }
  return 0;
}

export function parseJavaProperties(output) {
  const properties = Object.create(null);
  for (const line of String(output).split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z][A-Za-z0-9._]*)\s*=\s*(.*?)\s*$/);
    if (match) properties[match[1]] = match[2];
  }
  return properties;
}

function sameVersion(actual, expected) {
  return actual.length === expected.length && actual.every((part, index) => part === expected[index]);
}

export function validateRuntimeProperties(properties, policy = APPROVED_RUNTIME) {
  const required = [
    "java.vendor",
    "java.vm.vendor",
    "java.version",
    "java.runtime.version",
    "java.vm.version",
    "java.specification.version"
  ];
  const missing = required.filter((key) => !properties[key]);
  if (missing.length) throw new Error(`Missing Java runtime metadata: ${missing.join(", ")}`);

  if (properties["java.vendor"] !== policy.vendor || properties["java.vm.vendor"] !== policy.vendor) {
    throw new Error(`Unapproved Java vendor: ${properties["java.vendor"]}/${properties["java.vm.vendor"]}`);
  }
  if (properties["java.specification.version"] !== "21") {
    throw new Error(`Expected Java feature 21; got ${properties["java.specification.version"]}`);
  }

  const version = parseJavaVersion(properties["java.version"]);
  if (!sameVersion(version.components, policy.version)
      || (version.build !== null && version.build !== policy.build)) {
    throw new Error(`Unapproved Java version ${properties["java.version"]}; expected ${policy.runtimePrefix}`);
  }

  for (const key of ["java.runtime.version", "java.vm.version"]) {
    const parsed = parseJavaVersion(properties[key]);
    if (!sameVersion(parsed.components, policy.version) || parsed.build !== policy.build) {
      throw new Error(`Unapproved ${key} ${properties[key]}; expected ${policy.runtimePrefix}`);
    }
  }

  if (properties["java.vendor.version"]
      && !properties["java.vendor.version"].startsWith(`Temurin-${policy.runtimePrefix}`)) {
    throw new Error(`Unapproved Temurin vendor build: ${properties["java.vendor.version"]}`);
  }

  return {
    vendor: properties["java.vendor"],
    feature: properties["java.specification.version"],
    javaVersion: properties["java.version"],
    runtimeVersion: properties["java.runtime.version"],
    vmVersion: properties["java.vm.version"],
    vendorVersion: properties["java.vendor.version"] ?? null
  };
}

function realPath(path) {
  try {
    return realpathSync(path);
  } catch (error) {
    throw new Error(`Cannot resolve Java path ${path}: ${error.message}`);
  }
}

function findJavaOnPath() {
  const executable = process.platform === "win32" ? "java.exe" : "java";
  for (const directory of (process.env.PATH ?? "").split(delimiter)) {
    if (!directory) continue;
    const candidate = join(directory, executable);
    try {
      accessSync(candidate, process.platform === "win32" ? constants.F_OK : constants.X_OK);
      return realPath(candidate);
    } catch {
      // Continue through PATH until the first executable Java is found.
    }
  }
  throw new Error("No java executable is available on PATH");
}

export function validateJavaHome(properties, javaHome, javaExecutable) {
  if (!javaHome) throw new Error("JAVA_HOME is unset");
  const configuredHome = realPath(javaHome);
  const reportedHome = realPath(properties["java.home"] ?? "");
  const selectedJava = realPath(javaExecutable);
  const expectedJava = realPath(join(configuredHome, "bin", process.platform === "win32" ? "java.exe" : "java"));

  if (reportedHome !== configuredHome) {
    throw new Error(`java.home does not match JAVA_HOME: ${reportedHome} != ${configuredHome}`);
  }
  if (selectedJava !== expectedJava) {
    throw new Error(`PATH selects a Java outside JAVA_HOME: ${selectedJava} != ${expectedJava}`);
  }
  const relativeHome = relative(configuredHome, selectedJava);
  if (relativeHome.startsWith(`..${sep}`) || relativeHome === "..") {
    throw new Error("Selected Java executable escapes JAVA_HOME");
  }
}

function main() {
  const javaExecutable = findJavaOnPath();
  const probe = spawnSync(javaExecutable, ["-XshowSettings:properties", "-version"], { encoding: "utf8" });
  if (probe.error) throw new Error(`Failed to inspect Java runtime: ${probe.error.message}`);
  if (probe.status !== 0) throw new Error(`Java runtime inspection exited with ${probe.status}`);

  const properties = parseJavaProperties(`${probe.stdout ?? ""}\n${probe.stderr ?? ""}`);
  const verified = validateRuntimeProperties(properties);
  validateJavaHome(properties, process.env.JAVA_HOME, javaExecutable);
  console.log(`Verified Java runtime: ${verified.vendor} ${verified.runtimeVersion} at ${properties["java.home"]}`);
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    main();
  } catch (error) {
    console.error(`Java runtime policy failed: ${error.message}`);
    process.exitCode = 1;
  }
}
