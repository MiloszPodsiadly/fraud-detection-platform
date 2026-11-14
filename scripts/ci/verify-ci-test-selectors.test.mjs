import assert from "node:assert/strict";
import test from "node:test";
import { validateCiTestSelectors } from "./verify-ci-test-selectors.mjs";

const liveClasses = new Set([
  "com.example.ProofTest",
  "ProofTest",
  "com.example.OtherTest",
  "OtherTest"
]);

test("accepts live FQCN and method selectors in a multiline workflow command and a JS guard file", () => {
  const failures = validateCiTestSelectors({
    suites: {
      proof: {
        junitRequired: ["com.example.ProofTest"],
        activationGroups: [{
          label: "frontend tests",
          files: ["ui/src/proof.test.js"]
        }]
      }
    },
    workflows: [{
      path: ".github/workflows/ci.yml",
      source: [
        "steps:",
        "  - name: proof",
        "    run: |",
        "      mvn test \\",
        "        -Dtest=com.example.ProofTest#first+second,OtherTest",
        "        -Dgroups=integration"
      ].join("\n")
    }],
    javaTestClasses: liveClasses,
    existingFiles: ["ui/src/proof.test.js"]
  });
  assert.deepEqual(failures, []);
});

test("rejects a missing junitRequired class", () => {
  const failures = validateCiTestSelectors({
    suites: { proof: { junitRequired: ["MissingProofTest"] } },
    workflows: [],
    javaTestClasses: liveClasses,
    existingFiles: []
  });
  assert.match(failures.join("\n"), /missing Java test class MissingProofTest/);
});

test("rejects a stale Maven selector in a multiline YAML run block", () => {
  const failures = validateCiTestSelectors({
    suites: {},
    workflows: [{
      path: ".github/workflows/ci.yml",
      source: [
        "steps:",
        "  - name: stale proof selector",
        "    run: >-",
        "      mvn test",
        "      -Dtest=MissingProofTest,com.example.ProofTest#existingMethod"
      ].join("\n")
    }],
    javaTestClasses: liveClasses,
    existingFiles: []
  });
  assert.match(failures.join("\n"), /missing Java test class MissingProofTest/);
});

test("rejects a missing activation group file, including JavaScript test files", () => {
  const failures = validateCiTestSelectors({
    suites: {
      proof: {
        activationGroups: [{ label: "frontend tests", files: ["ui/src/missing.test.jsx"] }]
      }
    },
    workflows: [],
    javaTestClasses: liveClasses,
    existingFiles: []
  });
  assert.match(failures.join("\n"), /missing activation file ui\/src\/missing.test.jsx/);
});
