import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { describe, expect, it } from "vitest";

describe("api client boundary", () => {
  it("does not export legacy default wrappers from alertsApi", () => {
    const source = readFileSync(join(process.cwd(), "src/api/alertsApi.js"), "utf8");

    expect(source).not.toContain("defaultApiClient");
    expect(source).not.toMatch(/^export function (listAlerts|listFraudCaseWorkQueue|getFraudCaseWorkQueueSummary|listScoredTransactions|listGovernanceAdvisories|getGovernanceAdvisoryAnalytics|getGovernanceAdvisoryAudit|recordGovernanceAdvisoryAudit|getAlert|getAssistantSummary|getFraudCase|updateFraudCase|submitAnalystDecision)\(/m);
  });

  it("runs the FDP-49 boundary script that blocks wrappers, re-exports, dynamic imports and raw fetch", () => {
    expect(() => execFileSync("node", ["../scripts/check-fdp49-api-client-boundary.mjs"], {
      cwd: process.cwd(),
      stdio: "pipe"
    })).not.toThrow();
  });

  it("runs the FDP-50 AST boundary script for workspace-scoped API clients", () => {
    expect(() => execFileSync("node", ["../scripts/check-fdp50-api-client-boundary.mjs"], {
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
      expect(() => runBoundaryScript(fixture, "fdp49")).not.toThrow();
      expect(() => runBoundaryScript(fixture, "fdp50")).not.toThrow();
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
      expect(captureBoundaryFailure(fixture, "fdp49")).toContain("Auth-sensitive UI code must use createAlertsApiClient");
      expect(captureBoundaryFailure(fixture, "fdp50")).toContain("FDP-50 requires auth-sensitive UI code");
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });

  it("allows only explicit createAlertsApiClient and safe helpers from alertsApi in auth-sensitive code", () => {
    const fixture = createBoundaryFixture({
      "analyst-console-ui/src/App.jsx": "import { createAlertsApiClient } from './api/alertsApi.js'; export const client = createAlertsApiClient({});",
      "analyst-console-ui/src/workspace/hook.js": "import { isAbortError } from '../api/alertsApi.js'; export const ok = isAbortError;"
    });

    try {
      expect(() => runBoundaryScript(fixture, "fdp50")).not.toThrow();
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });

  it("rejects default wrapper named imports through the FDP-50 AST guard", () => {
    const fixture = createBoundaryFixture({
      "analyst-console-ui/src/workspace/hook.js": "import { listAlerts } from '../api/alertsApi.js'; export const load = () => listAlerts();"
    });

    try {
      expect(captureBoundaryFailure(fixture, "fdp50")).toContain("imports compatibility wrapper listAlerts");
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });

  it("rejects future auth-sensitive folders through the FDP-50 AST guard", () => {
    const fixture = createBoundaryFixture({
      "analyst-console-ui/src/hooks/useCases.js": "import { listAlerts } from '../api/alertsApi.js'; export const load = () => listAlerts();",
      "analyst-console-ui/src/features/rawFetch.js": "export const load = () => fetch('/api/v1/alerts');"
    });

    try {
      const failure = captureBoundaryFailure(fixture, "fdp50");
      expect(failure).toContain("src/hooks/useCases.js");
      expect(failure).toContain("src/features/rawFetch.js");
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });

  it("allows raw fetch only inside API and auth bootstrap folders for the FDP-50 AST guard", () => {
    const fixture = createBoundaryFixture({
      "analyst-console-ui/src/api/bootstrap.js": "export const api = () => fetch('/api/v1/session');",
      "analyst-console-ui/src/auth/authProvider.js": "export const auth = () => window.fetch('/api/v1/session');",
      "analyst-console-ui/src/hooks/useClient.js": "import { createAlertsApiClient } from '../api/alertsApi.js'; export const client = createAlertsApiClient({});"
    });

    try {
      expect(() => runBoundaryScript(fixture, "fdp50")).not.toThrow();
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });

  it("keeps removed wrapper names blocked by the FDP-50 guard", () => {
    const script = readFileSync(join(process.cwd(), "../scripts/check-fdp50-api-client-boundary.mjs"), "utf8");
    const removedWrappers = [
      "listAlerts",
      "listFraudCaseWorkQueue",
      "getFraudCaseWorkQueueSummary",
      "listScoredTransactions",
      "listGovernanceAdvisories",
      "getGovernanceAdvisoryAnalytics",
      "getGovernanceAdvisoryAudit",
      "recordGovernanceAdvisoryAudit",
      "getAlert",
      "getAssistantSummary",
      "getFraudCase",
      "updateFraudCase",
      "submitAnalystDecision"
    ];

    for (const wrapperName of removedWrappers) {
      expect(script).toContain(`"${wrapperName}"`);
    }
  });

  it("fails closed when the FDP-50 scope base ref is missing", () => {
    const failure = captureScopeFailure({
      FDP50_SCOPE_BASE: "refs/heads/does-not-exist-fdp50"
    });

    expect(failure).toContain("Cannot resolve FDP-50 scope base ref refs/heads/does-not-exist-fdp50");
  });

  it("honors an explicit FDP-50 scope base ref", () => {
    expect(() => runScopeGuard({
      FDP50_SCOPE_BASE: "HEAD"
    })).not.toThrow();
  });

  it("lets explicit changed files bypass git base resolution for FDP-50 scope fixtures", () => {
    expect(() => runScopeGuard({
      FDP50_SCOPE_BASE: "refs/heads/does-not-exist-fdp50",
      FDP50_SCOPE_CHANGED_FILES: "docs/fdp-50-frontend-api-client-boundary.md"
    })).not.toThrow();
  });

  it("documents the FDP-50 frontend API client boundary", () => {
    const fdp50 = readFileSync(join(process.cwd(), "../docs/fdp-50-frontend-api-client-boundary.md"), "utf8");
    const howTo = readFileSync(join(process.cwd(), "../docs/frontend/api-client-boundary.md"), "utf8");
    const combined = `${fdp50}\n${howTo}`;

    expect(combined).toContain("createAlertsApiClient");
    expect(combined).toContain("BFF");
    expect(combined).toContain("JWT");
    expect(combined).toContain("demo");
    expect(combined).toContain("raw fetch");
    expect(combined).toContain("default wrappers");
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

function runBoundaryScript(root, version = "fdp49") {
  const script = version === "fdp50"
    ? "../scripts/check-fdp50-api-client-boundary.mjs"
    : "../scripts/check-fdp49-api-client-boundary.mjs";
  const rootEnv = version === "fdp50" ? "FDP50_API_BOUNDARY_ROOT" : "FDP49_API_BOUNDARY_ROOT";
  execFileSync("node", [script], {
    cwd: process.cwd(),
    env: { ...process.env, [rootEnv]: root },
    encoding: "utf8",
    stdio: "pipe"
  });
}

function captureBoundaryFailure(root, version = "fdp49") {
  try {
    runBoundaryScript(root, version);
    throw new Error("Expected FDP-49 API boundary fixture to fail");
  } catch (error) {
    return String(error.stderr ?? error.message);
  }
}

function runScopeGuard(env = {}) {
  execFileSync("node", ["../scripts/check-fdp50-scope.mjs"], {
    cwd: process.cwd(),
    env: { ...process.env, ...env },
    encoding: "utf8",
    stdio: "pipe"
  });
}

function captureScopeFailure(env = {}) {
  try {
    runScopeGuard(env);
    throw new Error("Expected FDP-50 scope guard fixture to fail");
  } catch (error) {
    return String(error.stderr ?? error.message);
  }
}
