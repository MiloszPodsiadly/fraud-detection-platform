import assert from "node:assert/strict";
import test from "node:test";
import { extractMavenTestSelectors, validateCiTestSelectors } from "./verify-ci-test-selectors.mjs";

const liveClasses = new Set([
  "com.example.ProofTest",
  "ProofTest",
  "com.example.OtherTest",
  "OtherTest"
]);

test("extracts a double-quoted whole -Dtest argument", () => {
  assert.deepEqual(
    extractMavenTestSelectors('mvn -B "-Dtest=ProofTest,OtherTest" test'),
    ["ProofTest,OtherTest"]
  );
});

test("extracts a single-quoted whole -Dtest argument with method selectors", () => {
  assert.deepEqual(
    extractMavenTestSelectors("mvn '-Dtest=com.example.ProofTest#first+second,OtherTest' test"),
    ["com.example.ProofTest#first+second,OtherTest"]
  );
});

test("keeps supporting quotes around only the selector value", () => {
  assert.deepEqual(
    extractMavenTestSelectors('mvn -Dtest="ProofTest,OtherTest" test'),
    ["ProofTest,OtherTest"]
  );
});

test("quoted FQCN method selectors are validated", () => {
  const command = 'mvn "-Dtest=com.example.ProofTest#first+second" test';
  const failures = validateCiTestSelectors({
    suites: {},
    workflows: [{ path: ".github/workflows/ci.yml", source: `steps:\n  - name: proof\n    run: ${command}` }],
    javaTestClasses: liveClasses,
    existingFiles: []
  });
  assert.deepEqual(failures, []);
});

test("a missing class in a quoted whole argument fails validation", () => {
  const failures = validateCiTestSelectors({
    suites: {},
    workflows: [{
      path: ".github/workflows/ci.yml",
      source: 'steps:\n  - name: stale proof selector\n    run: mvn -B "-Dtest=MissingProofTest" test'
    }],
    javaTestClasses: liveClasses,
    existingFiles: []
  });
  assert.match(failures.join("\n"), /missing Java test class MissingProofTest/);
});

test("quoted missing classes remain visible in literal and folded YAML run blocks", () => {
  for (const style of ["|", ">-"]) {
    const failures = validateCiTestSelectors({
      suites: {},
      workflows: [{
        path: ".github/workflows/ci.yml",
        source: [
          "steps:",
          "  - name: stale proof selector",
          `    run: ${style}`,
          "      mvn -B",
          '        "-Dtest=MissingProofTest"',
          "        test"
        ].join("\n")
      }],
      javaTestClasses: liveClasses,
      existingFiles: []
    });
    assert.match(failures.join("\n"), /missing Java test class MissingProofTest/, style);
  }
});

test("a quoted selector copied from the live workflow fails when one class is replaced", () => {
  const liveCommand = 'mvn -B "-Dsurefire.failIfNoSpecifiedTests=false" -pl alert-service -am "-Dtest=FraudCaseControllerTest,FraudCaseManagementServiceTest,FraudCaseMutationInvariantTest,FraudCasePatchRegulatedMutationProofTest,FraudCaseRemovedRouteRuntimeTest,FraudCaseRemovedApiSurfaceTest,FraudCaseCurrentApiSurfaceTest,FraudCaseSecurityIntegrationTest,AlertSecurityConfigTest,AlertSecurityConfigJwtEnabledTest,DemoAuthSecurityConfigTest,AnalystRoleTest" test';
  const realSelector = liveCommand.match(/"-Dtest=([^"]+)"/)[1];
  const mutatedCommand = liveCommand.replace("FraudCaseControllerTest", "MissingProofTest");
  const failures = validateCiTestSelectors({
    suites: {},
    workflows: [{ path: ".github/workflows/ci.yml", source: `steps:\n  - name: copied FDP-42 command\n    run: ${mutatedCommand}` }],
    javaTestClasses: new Set(realSelector.split(",").slice(1)),
    existingFiles: []
  });
  assert.match(failures.join("\n"), /missing Java test class MissingProofTest/);
});

test("rejects an explicitly empty Maven test selector", () => {
  const failures = validateCiTestSelectors({
    suites: {},
    workflows: [{ path: ".github/workflows/ci.yml", source: 'steps:\n  - name: empty selector\n    run: mvn "-Dtest=" test' }],
    javaTestClasses: liveClasses,
    existingFiles: []
  });
  assert.match(failures.join("\n"), /empty class selector/);
});

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
