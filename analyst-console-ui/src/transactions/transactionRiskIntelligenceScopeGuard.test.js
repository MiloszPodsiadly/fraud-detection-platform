import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const FDP_116_SOURCE_FILES = [
  "src/components/TransactionRiskIntelligencePanel.jsx",
  "src/transactions/transactionRiskIntelligenceValidation.js",
  "src/transactions/useScoredTransactionDetail.js",
  "src/pages/TransactionScoringWorkspacePage.jsx",
  "src/components/TransactionMonitorTable.jsx",
  "src/workspace/TransactionScoringWorkspaceContainer.jsx",
  "src/workspace/TransactionScoringWorkspaceRuntime.jsx"
];

const FORBIDDEN_TERMS = [
  ["approve", "Transaction"].join(""),
  ["decline", "Transaction"].join(""),
  ["block", "Transaction"].join(""),
  ["payment", "Authorization"].join(""),
  ["authorize", "Payment"].join(""),
  ["promote", "Model"].join(""),
  ["deploy", "Model"].join(""),
  ["threshold", "Recommendation"].join(""),
  ["recommended", "Threshold"].join(""),
  ["change", "Threshold"].join(""),
  ["recommendation", "Service"].join(""),
  ["recommended", "Action"].join(""),
  ["feedback", "Submit"].join(""),
  ["submit", "Feedback"].join(""),
  ["raw", "Ml", "Request"].join(""),
  ["raw", "Ml", "Response"].join(""),
  ["raw", "Feature", "Vector"].join(""),
  ["Fraud", "Engine", "Result"].join(""),
  ["raw", "Evidence"].join(""),
  ["ground", "Truth"].join(""),
  ["training", "Label"].join(""),
  ["final", "Decision"].join(""),
  "POST /api/v1/transactions/scored",
  "PUT /api/v1/transactions/scored",
  "PATCH /api/v1/transactions/scored",
  "DELETE /api/v1/transactions/scored"
];

describe("transactionRiskIntelligenceScopeGuard", () => {
  it("keeps FDP-116 source files read-only and bounded", () => {
    const source = FDP_116_SOURCE_FILES.map(sourceFile).join("\n");

    for (const forbiddenTerm of FORBIDDEN_TERMS) {
      expect(source).not.toContain(forbiddenTerm);
    }
    expect(source).toContain("agreementStatus");
    expect(source).toContain("riskMismatchStatus");
    expect(source).toContain("scoreDeltaBucket");
    expect(source).toContain("reasonCodes");
    expect(source).toContain("warningCode");
  });

  it("uses only the FDP-115 scored transaction detail read endpoint in the new client method", () => {
    const clientMethod = scoredTransactionDetailClientSource();

    expect(clientMethod).toContain("/api/v1/transactions/scored/");
    expect(clientMethod).not.toContain("/engine-intelligence");
    expect(clientMethod).not.toContain("feedback");
    expect(clientMethod).not.toContain("method:");
    expect(clientMethod).not.toContain("body:");
  });
});

function sourceFile(relativePath) {
  return readFileSync(resolve(process.cwd(), relativePath), "utf8");
}

function scoredTransactionDetailClientSource() {
  const source = sourceFile("src/api/alertsApi.js");
  const sourceStart = source.indexOf("function getScoredTransactionDetailWithRequest");
  const sourceEnd = source.indexOf("function listSuspiciousTransactionsWithRequest");
  const methodEntryStart = source.indexOf("getScoredTransactionDetail:");
  const methodEntryEnd = source.indexOf("listSuspiciousTransactions:", methodEntryStart);
  return [
    source.slice(methodEntryStart, methodEntryEnd),
    source.slice(sourceStart, sourceEnd)
  ].join("\n");
}
