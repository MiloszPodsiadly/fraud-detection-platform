import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const workspaceDir = dirname(fileURLToPath(import.meta.url));
const srcDir = resolve(workspaceDir, "..");

describe("SuspiciousTransaction frontend telemetry guard", () => {
  it("suspiciousUiDoesNotLogIdentifiers", () => {
    expect(sensitiveSinkLines(/console\.(log|info|warn|error|debug)\s*\(/)).toEqual([]);
  });

  it("suspiciousUiDoesNotStoreIdentifiers", () => {
    expect(sensitiveSinkLines(/localStorage|sessionStorage|indexedDB|document\.cookie/)).toEqual([]);
  });

  it("suspiciousUiDoesNotEmitAnalyticsIdentifiers", () => {
    expect(sensitiveSinkLines(/trackEvent|dataLayer|navigator\.sendBeacon|analytics|telemetry/)).toEqual([]);
  });

  it("suspiciousUiDoesNotLogSummaryCountAsTelemetryLabel", () => {
    const telemetryLines = allSuspiciousUiSources()
      .split(/\r?\n/)
      .filter((line) => /trackEvent|dataLayer|navigator\.sendBeacon|analytics|telemetry|metric/i.test(line));

    expect(telemetryLines.filter((line) => /totalSuspiciousTransactions/.test(line))).toEqual([]);
  });
});

function sensitiveSinkLines(sinkPattern) {
  return allSuspiciousUiSources()
    .split(/\r?\n/)
    .filter((line) => sinkPattern.test(line))
    .filter((line) => SENSITIVE_TERMS.some((term) => line.includes(term)));
}

function allSuspiciousUiSources() {
  return [
    resolve(srcDir, "api", "alertsApi.js"),
    resolve(srcDir, "workspace", "useWorkspaceCounters.js"),
    resolve(srcDir, "workspace", "WorkspaceRouteRegistry.jsx"),
    resolve(srcDir, "workspace", "WorkspaceNavigation.jsx"),
    resolve(srcDir, "workspace", "useSuspiciousTransactionReadView.js"),
    resolve(srcDir, "workspace", "SuspiciousTransactionWorkspaceRuntime.jsx"),
    resolve(srcDir, "pages", "SuspiciousTransactionWorkspacePage.jsx")
  ].map((file) => readFileSync(file, "utf8")).join("\n");
}

const SENSITIVE_TERMS = [
  "suspiciousTransactionId",
  "transactionId",
  "customerId",
  "accountId",
  "sourceEventId",
  "correlationId",
  "linkedAlertId",
  "cursor",
  "nextCursor",
  "totalSuspiciousTransactions",
  "raw response",
  "raw error",
  "Authorization",
  "token",
  "email",
  "username"
];
