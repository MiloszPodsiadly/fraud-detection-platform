import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { repoRoot } from "./fdp-scope/scopeGuardHelpers.mjs";

const riskyPhrases = [
  ["production-ready", /\bproduction-ready\b/i],
  ["regulator-grade", /\bregulator-grade\b/i],
  ["fully secure", /\bfully secure\b/i],
  ["guaranteed", /\bguaranteed\b/i],
  ["regulator-proof", /\bregulator-proof\b/i],
  ["bank-grade certified", /\bbank-grade certified\b/i],
  ["WORM", /\bWORM\b/],
  ["notarized", /\bnotarized\b/i],
  ["legal finality", /\blegal finality\b/i],
  ["KMS/HSM", /\bKMS\/HSM\b/i],
  ["immutable audit", /\bimmutable audit\b/i],
  ["snapshot-consistent", /\bsnapshot-consistent\b/i],
  ["enterprise IAM", /\benterprise IAM\b/i]
];

const files = [
  ...walkMarkdown("docs"),
  ...["README.md"].filter((file) => existsSync(join(repoRoot, file)))
].sort();

const violations = [];

for (const file of files) {
  const lines = readFileSync(join(repoRoot, file), "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const [phrase, pattern] of riskyPhrases) {
      if (!pattern.test(line)) {
        continue;
      }
      const context = lines.slice(Math.max(0, index - 4), Math.min(lines.length, index + 5)).join(" ");
      if (isQualifiedOrDenied(line, context)) {
        continue;
      }
      violations.push({
        file,
        line: index + 1,
        phrase,
        remediation: "Qualify the claim with concrete evidence or rewrite it as a limitation/non-goal."
      });
    }
  });
}

if (violations.length > 0) {
  console.error("Documentation overclaim guard failed:");
  for (const violation of violations) {
    console.error(`- ${violation.file}:${violation.line}: "${violation.phrase}" - ${violation.remediation}`);
  }
  process.exit(1);
}

console.log("Documentation overclaim guard passed");

function walkMarkdown(relativeDirectory) {
  const directory = join(repoRoot, relativeDirectory);
  if (!existsSync(directory)) {
    return [];
  }
  return readdirSync(directory).flatMap((entry) => {
    const relative = `${relativeDirectory}/${entry}`;
    const absolute = join(repoRoot, relative);
    return statSync(absolute).isDirectory()
      ? walkMarkdown(relative)
      : relative.endsWith(".md")
        ? [relative.replaceAll("\\", "/")]
        : [];
  });
}

function isQualifiedOrDenied(line, context = line) {
  const value = line.toLowerCase();
  const surrounding = context.toLowerCase();
  return /\b(no|not|never|without|unless|cannot|can't|forbid(?:s|den)?|avoid|denies|denied|deny|non-goal|non-goals|limitation|limitations|future|readiness-only|does not|do not|must not|is not|are not|not a|not an|outside|out of scope)\b/.test(value)
    || /\b(currently|only when|requires|reserved for|depends on|qualified|explicitly|architecture tests|appends|append-only)\b/.test(value)
    || /\b(no|not|never|without|unless|cannot|can't|forbid(?:s|den)?|avoid|denies|denied|deny|non-goal|non-goals|limitation|limitations|future|readiness-only|does not|do not|must not|is not|are not|not a|not an|outside|out of scope|does not prove|does not provide|what .* does not provide|this module is not)\b/.test(surrounding)
    || /\b(currently|only when|requires|reserved for|depends on|qualified|explicitly|architecture tests|appends|append-only)\b/.test(surrounding);
}
