import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const workspaceDir = dirname(fileURLToPath(import.meta.url));
const srcDir = resolve(workspaceDir, "..");

describe("FDP-67 alert read-only bridge telemetry guard", () => {
  it("AlertBridgeDoesNotLogRawAlertIdTest", () => {
    expect(sensitiveSinkLines(/console\.(log|info|warn|error|debug)\s*\(/, ["alertId", "linkedAlertId"])).toEqual([]);
  });

  it("AlertBridgeDoesNotLogSuspiciousTransactionIdTest", () => {
    expect(sensitiveSinkLines(/console\.(log|info|warn|error|debug)\s*\(/, ["suspiciousTransactionId"])).toEqual([]);
  });

  it("AlertBridgeDoesNotLogCustomerOrAccountIdTest", () => {
    expect(sensitiveSinkLines(/console\.(log|info|warn|error|debug)\s*\(/, ["customerId", "accountId"])).toEqual([]);
  });

  it("AlertBridgeDoesNotLogRawUrlTest", () => {
    expect(sensitiveSinkLines(/console\.(log|info|warn|error|debug)\s*\(/, ["raw URL", "window.location", "location.search"])).toEqual([]);
  });

  it("AlertBridgeDoesNotStoreIdentifiersTest", () => {
    expect(sensitiveSinkLines(/localStorage|sessionStorage|indexedDB|document\.cookie/, [
      "alertId",
      "linkedAlertId",
      "suspiciousTransactionId",
      "customerId",
      "accountId"
    ])).toEqual([]);
  });

  it("AlertBridgeTelemetryLabelsAreBoundedTest", () => {
    expect(sensitiveSinkLines(/trackEvent|dataLayer|navigator\.sendBeacon|analytics|telemetry/, [
      "alertId",
      "linkedAlertId",
      "suspiciousTransactionId",
      "transactionId",
      "customerId",
      "accountId",
      "correlationId",
      "raw URL"
    ])).toEqual([]);
  });
});

function sensitiveSinkLines(sinkPattern, terms) {
  return bridgeSources()
    .split(/\r?\n/)
    .filter((line) => sinkPattern.test(line))
    .filter((line) => terms.some((term) => line.includes(term)));
}

function bridgeSources() {
  return [
    resolve(srcDir, "api", "alertReadOnlyBridgeApi.js"),
    resolve(srcDir, "workspace", "WorkspaceDetailRouter.jsx"),
    resolve(srcDir, "workspace", "SuspiciousTransactionWorkspaceRuntime.jsx"),
    resolve(srcDir, "pages", "AlertDetailsPage.jsx"),
    resolve(srcDir, "pages", "SuspiciousTransactionWorkspacePage.jsx")
  ].map((file) => readFileSync(file, "utf8")).join("\n");
}
