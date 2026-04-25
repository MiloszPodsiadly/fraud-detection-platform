import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(currentFile), "..");
const backendAuthorityFile = resolve(
  repoRoot,
  "alert-service/src/main/java/com/frauddetection/alert/security/authorization/AnalystAuthority.java"
);
const frontendAuthorityFile = resolve(
  repoRoot,
  "analyst-console-ui/src/auth/generatedAuthorities.js"
);
const checkOnly = process.argv.includes("--check");

const source = readFileSync(backendAuthorityFile, "utf8");
const matches = [...source.matchAll(/public static final String (\w+)\s*=\s*"([^"]+)";/g)];

if (matches.length === 0) {
  throw new Error(
    `Failed to parse frontend authority constants from ${backendAuthorityFile}. ` +
      "Expected public static final String constants in AnalystAuthority.java."
  );
}

const constants = matches.map(([, key, value]) => ({ key, value }));
const duplicateValues = constants
  .map((entry) => entry.value)
  .filter((value, index, values) => values.indexOf(value) !== index);

if (duplicateValues.length > 0) {
  throw new Error(
    `Refusing to generate frontend authority constants because duplicate backend values were found: ${[...new Set(duplicateValues)].join(", ")}`
  );
}

const lines = [
  "// GENERATED FILE - DO NOT EDIT MANUALLY.",
  "// Source: alert-service/src/main/java/com/frauddetection/alert/security/authorization/AnalystAuthority.java",
  "",
  "export const AUTHORITIES = Object.freeze({",
  ...constants.map(({ key, value }) => `  ${key}: "${value}",`),
  "});",
  "",
  "export const AUTHORITY_VALUES = Object.freeze([",
  ...constants.map(({ value }) => `  "${value}",`),
  "]);",
  ""
];

const generatedOutput = `${lines.join("\n")}`;

if (checkOnly) {
  const currentOutput = readFileSync(frontendAuthorityFile, "utf8");
  if (currentOutput !== generatedOutput) {
    throw new Error(
      "Frontend authority constants are out of date. Run `npm run generate:authorities` in analyst-console-ui and commit the updated generatedAuthorities.js file."
    );
  }
} else {
  writeFileSync(frontendAuthorityFile, generatedOutput, "utf8");
}
