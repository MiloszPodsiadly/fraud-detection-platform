import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const workspaceDir = dirname(fileURLToPath(import.meta.url));
const srcDir = resolve(workspaceDir, "..");

describe("SuspiciousTransaction frontend telemetry guard", () => {
  it("does not log suspicious transaction identifiers, cursors, or raw responses from UI code", () => {
    const sources = [
      resolve(srcDir, "api", "alertsApi.js"),
      resolve(srcDir, "workspace", "useSuspiciousTransactionReadView.js"),
      resolve(srcDir, "workspace", "SuspiciousTransactionWorkspaceRuntime.jsx"),
      resolve(srcDir, "pages", "SuspiciousTransactionWorkspacePage.jsx")
    ].map((file) => readFileSync(file, "utf8")).join("\n");

    expect(sources).not.toMatch(/\bconsole\.(log|info|warn|error|debug)\s*\(/);
    expect(sources).not.toMatch(/trackEvent|dataLayer|navigator\.sendBeacon/);
    expect(sources).not.toMatch(/localStorage|sessionStorage/);
  });
});
