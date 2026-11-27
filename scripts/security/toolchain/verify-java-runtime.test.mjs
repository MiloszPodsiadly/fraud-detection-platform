import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

import {
  APPROVED_RUNTIME,
  compareNumericVersions,
  parseJavaProperties,
  parseJavaVersion,
  validateRuntimeProperties
} from "./verify-java-runtime.mjs";

const fixed = {
  "java.vendor": "Eclipse Adoptium",
  "java.vm.vendor": "Eclipse Adoptium",
  "java.specification.version": "21",
  "java.version": "21.0.13.1",
  "java.runtime.version": "21.0.13.1+2-LTS",
  "java.vm.version": "21.0.13.1+2-LTS",
  "java.vendor.version": "Temurin-21.0.13.1+2"
};

const shellValidator = fileURLToPath(new URL("./verify-temurin-java-runtime.sh", import.meta.url));
const runtimePolicy = fileURLToPath(new URL("./java-runtime-policy.properties", import.meta.url));

function findPosixShell() {
  if (process.platform !== "win32") {
    return { executable: "sh", pathDirectory: undefined, toPosixPath: (value) => value };
  }

  const gitBin = join(process.env.ProgramFiles ?? "C:\\Program Files", "Git", "usr", "bin");
  const shellExecutable = join(gitBin, "sh.exe");
  const cygpathExecutable = join(gitBin, "cygpath.exe");
  if (!existsSync(shellExecutable) || !existsSync(cygpathExecutable)) return null;

  return {
    executable: shellExecutable,
    pathDirectory: gitBin,
    toPosixPath(value) {
      const result = spawnSync(cygpathExecutable, ["-u", value], { encoding: "utf8" });
      if (result.status !== 0) throw new Error(`Git Bash could not translate path ${value}: ${result.stderr}`);
      return result.stdout.trim();
    }
  };
}

const posixShell = findPosixShell();

test("accepts the approved patched Temurin 21 build", () => {
  assert.equal(validateRuntimeProperties(fixed).runtimeVersion, fixed["java.runtime.version"]);
});

test("rejects a vulnerable older Temurin patch", () => {
  const properties = { ...fixed, "java.version": "21.0.13", "java.runtime.version": "21.0.13+9-LTS", "java.vm.version": "21.0.13+9-LTS" };
  assert.throws(() => validateRuntimeProperties(properties), /Unapproved Java version|Unapproved java\.runtime\.version/);
});

test("rejects a wrong feature version and a different JVM vendor", () => {
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.specification.version": "17" }), /feature 21/);
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.vendor": "Other Vendor" }), /Unapproved Java vendor/);
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.vm.vendor": "Other Vendor" }), /Unapproved Java vendor/);
});

test("rejects malformed, unrecognized, and incomplete runtime metadata", () => {
  assert.throws(() => parseJavaVersion("21.latest+2"), /Malformed Java version/);
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.version": "21.0.13.1+2" }), /Unapproved Java version/);
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.vm.version": "21.0.13.1+1-LTS" }), /Unapproved java\.vm\.version/);
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.vm.version": "unknown" }), /Unapproved java\.vm\.version/);
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.runtime.version": undefined }), /Missing Java runtime metadata/);
  const { "java.vm.vendor": omitted, ...withoutVmVendor } = fixed;
  void omitted;
  assert.throws(() => validateRuntimeProperties(withoutVmVendor), /Missing Java runtime metadata/);
  const { "java.version": omittedVersion, ...withoutJavaVersion } = fixed;
  void omittedVersion;
  assert.throws(() => validateRuntimeProperties(withoutJavaVersion), /Missing Java runtime metadata/);
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.runtime.version": "21.0.13.1+1-LTS" }), /Unapproved java\.runtime\.version/);
});

test("compares version components numerically at patch boundaries", () => {
  assert.equal(compareNumericVersions("21.0.13.10", "21.0.13.9"), 1);
  assert.equal(compareNumericVersions("21.0.13.1", APPROVED_RUNTIME.version), 0);
  assert.equal(compareNumericVersions("21.0.13.0", "21.0.13.1"), -1);
});

test("parses Java property output without confusing unrelated lines", () => {
  const properties = parseJavaProperties(`Property settings:\n    java.vendor = Eclipse Adoptium\n    java.version = 21.0.13.1\nopenjdk version "21.0.13.1" 2026-11-18`);
  assert.equal(properties["java.vendor"], "Eclipse Adoptium");
  assert.equal(properties["java.version"], "21.0.13.1");
  assert.equal(properties["openjdk"], undefined);
});

test("Node and POSIX shell validators apply the same approved runtime cases", {
  skip: posixShell === null,
}, () => {
  const root = mkdtempSync(join(tmpdir(), "java-runtime-policy-"));
  const javaHome = join(root, "jdk");
  const binDirectory = join(javaHome, "bin");
  const javaExecutable = join(binDirectory, "java");
  mkdirSync(binDirectory, { recursive: true });
  writeFileSync(javaExecutable, "#!/bin/sh\nprintf '%s\\n' \"$JAVA_POLICY_FIXTURE\"\n", { mode: 0o755 });
  chmodSync(javaExecutable, 0o755);

  try {
    const cases = [
      ["approved Temurin", fixed, true],
      ["wrong vendor", { ...fixed, "java.vm.vendor": "Other Vendor" }, false],
      ["unapproved java.version suffix", { ...fixed, "java.version": "21.0.13.1+2" }, false],
      ["unapproved runtime suffix", { ...fixed, "java.runtime.version": "21.0.13.1+2-unreviewed" }, false],
      ["wrong VM build", { ...fixed, "java.vm.version": "21.0.13.1+1-LTS" }, false],
      ["missing vendor build", { ...fixed, "java.vendor.version": undefined }, false],
    ];

    for (const [label, properties, accepted] of cases) {
      const shellJavaHome = posixShell.toPosixPath(javaHome);
      const propertiesOutput = Object.entries({ ...properties, "java.home": shellJavaHome })
        .filter(([, value]) => value !== undefined)
        .map(([key, value]) => `    ${key} = ${value}`)
        .join("\n");
      if (accepted) assert.doesNotThrow(() => validateRuntimeProperties(properties), label);
      else assert.throws(() => validateRuntimeProperties(properties), undefined, label);

      const shellResult = spawnSync(posixShell.executable, [posixShell.toPosixPath(shellValidator)], {
        encoding: "utf8",
        env: {
          ...process.env,
          PATH: [binDirectory, posixShell.pathDirectory, process.env.PATH].filter(Boolean).join(delimiter),
          JAVA_HOME: shellJavaHome,
          JAVA_RUNTIME_POLICY_FILE: posixShell.toPosixPath(runtimePolicy),
          JAVA_POLICY_FIXTURE: propertiesOutput,
        },
      });
      assert.equal(shellResult.error, undefined, `${label}: ${shellResult.error?.message}`);
      assert.equal(shellResult.status === 0, accepted, `${label}: ${shellResult.stderr || shellResult.stdout}`);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
