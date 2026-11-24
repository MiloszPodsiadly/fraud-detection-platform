import assert from "node:assert/strict";
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
  assert.throws(() => validateRuntimeProperties({ ...fixed, "java.vm.version": "unknown" }), /Malformed Java version/);
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
