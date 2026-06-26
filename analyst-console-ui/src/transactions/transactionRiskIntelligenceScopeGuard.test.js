import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const FDP_116_SOURCE_FILES = [
  "src/components/AnalystRecommendationPanel.jsx",
  "src/components/TransactionRiskIntelligencePanel.jsx",
  "src/transactions/transactionRiskIntelligenceValidation.js",
  "src/transactions/useScoredTransactionDetail.js",
  "src/transactions/transactionRiskIntelligenceFixtures.js",
  "src/transactions/transactionRiskIntelligencePanelId.js",
  "src/pages/TransactionScoringWorkspacePage.jsx",
  "src/components/TransactionMonitorTable.jsx",
  "src/workspace/TransactionScoringWorkspaceContainer.jsx",
  "src/workspace/TransactionScoringWorkspaceRuntime.jsx"
];

const FEEDBACK_CAPTURE_SOURCE_FILES = [
  "src/components/TransactionRiskIntelligencePanel.jsx",
  "src/transactions/fraudFeedbackValidation.js",
  "src/transactions/useFraudFeedback.js"
];

const FORBIDDEN_TERMS = [
  ["approve", "Transaction"].join(""),
  ["decline", "Transaction"].join(""),
  ["block", "Transaction"].join(""),
  ["payment", "Authorization"].join(""),
  ["authorize", "Payment"].join(""),
  ["create", "Case"].join(""),
  ["start", "Workflow"].join(""),
  ["apply", "Recommendation"].join(""),
  ["accept", "Recommendation"].join(""),
  ["reject", "Recommendation"].join(""),
  ["promote", "Model"].join(""),
  ["deploy", "Model"].join(""),
  ["threshold", "Recommendation"].join(""),
  ["recommended", "Threshold"].join(""),
  ["change", "Threshold"].join(""),
  ["recommendation", "Service"].join(""),
  ["recommended", "Action"].join(""),
  ["raw", "ML", "Request"].join(""),
  ["raw", "ML", "Response"].join(""),
  ["raw", "Ml", "Request"].join(""),
  ["raw", "Ml", "Response"].join(""),
  ["raw", "Feature", "Vector"].join(""),
  ["Fraud", "Engine", "Result"].join(""),
  ["raw", "Evidence"].join(""),
  ["ground", "Truth"].join(""),
  ["training", "Label"].join(""),
  ["final", "Decision"].join(""),
  endpointUse("POST"),
  endpointUse("PUT"),
  endpointUse("PATCH"),
  endpointUse("DELETE")
];

const FORBIDDEN_FEEDBACK_ACTION_TERMS = [
  ["approve", "Payment"].join(""),
  ["decline", "Payment"].join(""),
  ["block", "Transaction"].join(""),
  ["authorize", "Payment"].join(""),
  ["automatic", "Decision"].join(""),
  ["auto", "Approve"].join(""),
  ["auto", "Decline"].join(""),
  ["auto", "Block"].join(""),
  ["apply", "Recommendation"].join(""),
  ["accept", "Recommendation"].join(""),
  ["reject", "Recommendation"].join(""),
  ["promote", "Model"].join(""),
  ["deploy", "Model"].join(""),
  ["change", "Threshold"].join(""),
  ["threshold", "Recommendation"].join(""),
  ["train", "Model"].join(""),
  ["retrain", "Model"].join(""),
  ["export", "Training", "Dataset"].join(""),
  ["raw", "Ml", "Request"].join(""),
  ["raw", "Ml", "Response"].join(""),
  ["raw", "Feature", "Vector"].join(""),
  ["raw", "Evidence"].join(""),
  ["final", "Decision"].join(""),
  ["payment", "Decision"].join(""),
  ["payment", "Authorization"].join("")
];

const FORBIDDEN_ACTION_PHRASES = [
  "safe to approve",
  "approve payment",
  "decline payment",
  "block transaction",
  "payment decision",
  "final decision",
  "automatically create case",
  "apply recommendation",
  "accept recommendation",
  "reject recommendation",
  "recommendation accepted",
  "recommendation applied",
  "recommended action",
  "recommended analyst action",
  "model should be promoted",
  "threshold should change",
  "authorize payment",
  "final decision",
  "payment decision"
];

const ALLOWED_NEGATIVE_BOUNDARY_STATEMENTS = [
  "not a final payment decision",
  "does not approve, decline, block",
  "does not approve, decline, block, authorize payment, create a case, trigger workflow, promote a model, or change thresholds",
  "does not authorize payment, approve, decline, block, change scoring, update recommendations, create cases, trigger workflow, train models, promote models, or change thresholds",
  "does not approve",
  "does not decline",
  "does not block",
  "does not authorize payment",
  "does not create a case",
  "does not trigger step-up automatically or start workflow",
  "not operational instructions",
  "not transaction approval and not payment authorization",
  "this panel does not approve, decline, block, authorize payment, recommend action, promote models, or change thresholds"
];

const FORBIDDEN_FEEDBACK_SURFACES = [
  "EngineIntelligenceFeedbackPanel",
  "submitEngineIntelligenceFeedback",
  "submitFeedback",
  "feedbackSubmit"
];

describe("transactionRiskIntelligenceScopeGuard", () => {
  it("keeps transaction risk intelligence diagnostics bounded and feedback capture non-decisioning", () => {
    const source = FDP_116_SOURCE_FILES.map(sourceFile).join("\n");

    for (const forbiddenTerm of FORBIDDEN_TERMS) {
      expect(source).not.toContain(forbiddenTerm);
    }
    expect(source).toContain("agreementStatus");
    expect(source).toContain("riskMismatchStatus");
    expect(source).toContain("scoreDeltaBucket");
    expect(source).toContain("reasonCodes");
    expect(source).toContain("warningCode");
    expect(source).toContain("RECOMMEND_REVIEW");
    expect(source).toContain("RECOMMEND_CASE_CREATION");
    expect(source).toContain("RECOMMEND_STEP_UP_REVIEW");
    expect(source).toContain("RECOMMEND_MONITOR");
    expect(source).toContain("RECOMMEND_NO_ACTION");
  });

  it("does not introduce positive payment decisioning or recommendation language", () => {
    const source = withoutAllowedNegativeBoundaryStatements(normalizedSource(FDP_116_SOURCE_FILES));

    for (const forbiddenPhrase of FORBIDDEN_ACTION_PHRASES) {
      expect(source).not.toContain(forbiddenPhrase);
    }
  });

  it("allows only bounded transaction fraud feedback as the write surface", () => {
    const source = [
      FEEDBACK_CAPTURE_SOURCE_FILES.map(sourceFile).join("\n"),
      fraudFeedbackClientSource()
    ].join("\n");

    expect(source).toContain("/api/v1/transactions/scored/${encodeURIComponent(normalizedTransactionId)}/feedback");
    expect(source).toContain("method: \"POST\"");
    expect(source).not.toContain("/scoring");
    expect(source).not.toContain("/recommendation");
    expect(source).not.toContain("/workflow");
    expect(source).not.toContain("/fraud-cases");
    expect(source).not.toContain("/payment");
    expect(source).not.toContain("/models");
    expect(source).not.toContain("/datasets");
    expect(source).not.toContain("/feedback/bulk");

    for (const forbiddenTerm of FORBIDDEN_FEEDBACK_ACTION_TERMS) {
      expect(source).not.toContain(forbiddenTerm);
    }
  });

  it("uses only the FDP-115 scored transaction detail read endpoint in the new client method", () => {
    const clientMethod = scoredTransactionDetailClientSource();

    expect(clientMethod).toContain(scoredTransactionsEndpoint());
    expect(clientMethod).not.toContain("/engine-intelligence");
    expect(clientMethod).not.toContain("feedback");
    expect(clientMethod).not.toContain("method:");
    expect(clientMethod).not.toContain("body:");
  });
});

function sourceFile(relativePath) {
  return readFileSync(resolve(process.cwd(), relativePath), "utf8");
}

function endpointUse(method) {
  return `${method} ${scoredTransactionsEndpoint().replace(/\/$/, "")}`;
}

function scoredTransactionsEndpoint() {
  return ["/", "api", "/v1/transactions/", "scored", "/"].join("");
}

function normalizedSource(relativePaths) {
  return relativePaths
    .map(sourceFile)
    .join("\n")
    .toLowerCase()
    .replace(/\s+/g, " ");
}

function withoutAllowedNegativeBoundaryStatements(source) {
  return [...ALLOWED_NEGATIVE_BOUNDARY_STATEMENTS].sort((left, right) => right.length - left.length).reduce(
    (current, allowedStatement) => current.replaceAll(allowedStatement, ""),
    source
  );
}

function scoredTransactionDetailClientSource() {
  const source = sourceFile("src/api/alertsApi.js");
  const sourceStart = source.indexOf("function getScoredTransactionDetailWithRequest");
  const sourceEnd = source.indexOf("function scoredTransactionDetailRequestOptions", sourceStart);
  const methodEntryStart = source.indexOf("getScoredTransactionDetail:");
  const methodEntryEnd = source.indexOf("getFraudFeedback:", methodEntryStart);
  return [
    source.slice(methodEntryStart, methodEntryEnd),
    source.slice(sourceStart, sourceEnd)
  ].join("\n");
}

function fraudFeedbackClientSource() {
  const source = sourceFile("src/api/alertsApi.js");
  const methodStart = source.indexOf("getFraudFeedback:");
  const methodEnd = source.indexOf("listSuspiciousTransactions:", methodStart);
  const getStart = source.indexOf("function getFraudFeedbackWithRequest");
  const createStart = source.indexOf("function createFraudFeedbackWithRequest");
  const createEnd = source.indexOf("function listSuspiciousTransactionsWithRequest", createStart);
  return [
    source.slice(methodStart, methodEnd),
    source.slice(getStart, createStart),
    source.slice(createStart, createEnd)
  ].join("\n");
}
