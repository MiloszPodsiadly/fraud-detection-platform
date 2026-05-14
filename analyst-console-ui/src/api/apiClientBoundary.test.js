import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

describe("api client boundary", () => {
  it("keeps default wrappers compatibility-only", () => {
    const source = readFileSync(join(process.cwd(), "src/api/alertsApi.js"), "utf8");

    expect(source).toContain("Compatibility-only default client. Auth-sensitive workspace code must use createAlertsApiClient({ session, authProvider }).");
  });

  it("runs the FDP-49 boundary script that blocks wrappers, re-exports, dynamic imports and raw fetch", () => {
    expect(() => execFileSync("node", ["../scripts/check-fdp49-api-client-boundary.mjs"], {
      cwd: process.cwd(),
      stdio: "pipe"
    })).not.toThrow();
  });

  it("allows raw fetch only in API and auth bootstrap fixtures", () => {
    const fixture = createBoundaryFixture({
      "analyst-console-ui/src/api/bootstrap.js": "export const api = () => fetch('/api/v1/session');",
      "analyst-console-ui/src/auth/authProvider.js": "export const auth = () => window.fetch('/api/v1/session');",
      "analyst-console-ui/src/workspace/panel.js": "export const panel = () => 'ok';"
    });

    try {
      expect(() => runBoundaryScript(fixture)).not.toThrow();
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });

  it.each([
    ["raw fetch in components", "analyst-console-ui/src/components/FraudPanel.js", "export const load = () => fetch('/api/v1/alerts');"],
    ["raw fetch in workspace", "analyst-console-ui/src/workspace/load.js", "export const load = () => window['fetch']('/api/v1/alerts');"],
    ["global fetch string access", "analyst-console-ui/src/workspace/global.js", "export const load = () => globalThis['fetch']('/api/v1/alerts');"],
    ["fetch alias", "analyst-console-ui/src/workspace/alias.js", "const f = fetch; export const load = () => f('/api/v1/alerts');"],
    ["dynamic alerts api import", "analyst-console-ui/src/workspace/dynamic.js", "export const load = () => import('../api/alertsApi.js');"],
    ["alerts api barrel re-export", "analyst-console-ui/src/workspace/index.js", "export * from '../api/alertsApi.js';"],
    ["default wrapper re-export", "analyst-console-ui/src/workspace/defaultClient.js", "export { default as alertsApi } from '../api/alertsApi.js';"],
    ["namespace wrapper usage", "analyst-console-ui/src/workspace/ns.js", "import * as alertsApi from '../api/alertsApi.js'; export const load = () => alertsApi.listAlerts();"]
  ])("rejects fixture violation: %s", (_name, path, source) => {
    const fixture = createBoundaryFixture({ [path]: source });

    try {
      expect(captureBoundaryFailure(fixture)).toContain("Auth-sensitive UI code must use createAlertsApiClient");
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });
});

function createBoundaryFixture(files) {
  const root = mkdtempSync(join(tmpdir(), "fdp49-api-boundary-"));
  for (const [relativePath, source] of Object.entries(files)) {
    const absolutePath = join(root, relativePath);
    mkdirSync(dirname(absolutePath), { recursive: true });
    writeFileSync(absolutePath, source);
  }
  return root;
}

function runBoundaryScript(root) {
  execFileSync("node", ["../scripts/check-fdp49-api-client-boundary.mjs"], {
    cwd: process.cwd(),
    env: { ...process.env, FDP49_API_BOUNDARY_ROOT: root },
    encoding: "utf8",
    stdio: "pipe"
  });
}

function captureBoundaryFailure(root) {
  try {
    runBoundaryScript(root);
    throw new Error("Expected FDP-49 API boundary fixture to fail");
  } catch (error) {
    return String(error.stderr ?? error.message);
  }
}
