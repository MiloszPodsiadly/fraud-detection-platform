import assert from "node:assert/strict";
import {
  containsEndpointString,
  containsRawFetch,
  containsSkippedOrFocusedTest,
  fileMatchesAnyPrefix,
  findNamedImportsFrom,
  isBackendProductionFile,
  isFrontendSourceFile,
  readFileIfExists
} from "./fdp-scope/scopeGuardHelpers.mjs";

assert.equal(fileMatchesAnyPrefix("a/b/c.js", ["a/b/"]), true);
assert.equal(isBackendProductionFile("alert-service/src/main/java/com/example/Foo.java"), true);
assert.equal(isBackendProductionFile("alert-service/src/test/java/com/example/FooTest.java"), false);
assert.equal(isFrontendSourceFile("analyst-console-ui/src/App.jsx"), true);
assert.equal(containsRawFetch("const result = fet" + "ch('/" + "api/test')"), true);
assert.equal(containsEndpointString("client.get('/" + "api/fraud-cases')"), true);
assert.equal(containsSkippedOrFocusedTest("describe.only('x', () => {})"), true);
assert.deepEqual(
  findNamedImportsFrom("import { listAlerts, getAlert as readAlert } from '../api/alertsApi.js';", "api/alertsApi.js"),
  ["listAlerts", "getAlert"]
);
assert.equal(readFileIfExists("definitely-missing-fdp-scope-helper-smoke.txt"), "");

console.log("fdp scope helper smoke checks passed");
