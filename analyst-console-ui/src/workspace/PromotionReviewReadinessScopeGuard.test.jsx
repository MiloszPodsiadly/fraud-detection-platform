import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const alertsApiSource = readFileSync(join(process.cwd(), "src/api/alertsApi.js"), "utf8");
const promotionApiSource = [
  lineContaining(alertsApiSource, "getCurrentPromotionReviewReadinessReport"),
  functionBlock(alertsApiSource, "promotionReviewReadinessReportRequest")
].join("\n");
const promotionSources = [
  "src/components/PromotionReviewReadinessPanel.jsx",
  "src/workspace/ShadowPerformanceWorkspaceRuntime.jsx",
  "src/workspace/ShadowPerformanceWorkspaceContainer.jsx",
  "src/pages/ShadowPerformanceDashboardPage.jsx",
  "src/workspace/usePromotionReviewReadinessReport.js"
].map((path) => readFileSync(join(process.cwd(), path), "utf8")).concat(promotionApiSource).join("\n");

const docs = readFileSync(join(process.cwd(), "../docs/architecture/promotion_review_readiness_ui_panel.md"), "utf8");

describe("FDP-114 promotion review readiness UI scope guards", () => {
  it("apiClientCallsOnlyCurrentReadEndpoint", () => {
    expect(promotionApiSource).toContain("/api/v1/governance/promotion-review-readiness/current");
    expect(promotionApiSource).not.toMatch(/\/generate|\/workflow|\/model-registry|\/threshold|\/scored|\/payments?/i);
  });

  it("doesNotIntroduceMutationMethodsForPromotionReadiness", () => {
    expect(promotionApiSource).not.toMatch(/\bmethod:\s*["'](?:POST|PUT|PATCH|DELETE)["']/);
    expect(promotionSources).not.toMatch(/recordGovernanceAdvisoryAudit|updateFraudCase|submitAnalystDecision|submitEngineIntelligenceFeedback/);
  });

  it("doesNotIntroduceWorkflowRegistryScoringPaymentOrReportGenerationCalls", () => {
    expect(promotionSources).not.toMatch(/workflowClient|modelRegistryClient|scoringMutation|paymentAuthorizationCall|generateReport/i);
    expect(promotionSources).not.toMatch(/python|makefile|docker compose/i);
  });

  it.each([
    /Approved/,
    /Safe to promote/i,
    /Ready for production/i,
    /Promote model/i,
    /Deploy model/i,
    /Recommended threshold/i,
    /Change threshold/i,
    /Threshold approved/i,
    /Payment authorized/i,
    /Auto approve/i,
    /Auto decline/i,
    /Block transaction/i
  ])("doesNotRenderUnsafePositiveActionLanguage %s", (unsafePattern) => {
    expect(promotionSources).not.toMatch(unsafePattern);
  });

  it("doesNotDisplayRawArtifactOrEntityIdentifierLabels", () => {
    expect(promotionSources).not.toMatch(/Raw FDP-10[234]|Raw artifact content|Transaction reference|Customer ID|Account ID|Card ID|Device ID|Merchant ID/);
  });

  it("documentsFdp114ArchitectureBoundaries", () => {
    expect(docs).toContain("FDP-114 is a read-only UI panel");
    expect(docs).toContain("consumes FDP-112");
    expect(docs).toContain("does not generate reports");
    expect(docs).toContain("does not approve promotion");
    expect(docs).toContain("does not recommend thresholds");
    expect(docs).toContain("does not trigger workflow");
    expect(docs).toContain("does not change scoring");
    expect(docs).toContain("does not mutate model registry");
    expect(docs).toContain("does not authorize payments");
    expect(docs).toContain("does not recommend analyst actions");
  });
});

function lineContaining(source, needle) {
  return source.split(/\r?\n/).find((line) => line.includes(needle)) || "";
}

function functionBlock(source, functionName) {
  const start = source.indexOf(`function ${functionName}`);
  if (start < 0) {
    return "";
  }
  const parametersEnd = source.indexOf(")", start);
  const openBrace = source.indexOf("{", parametersEnd);
  if (openBrace < 0) {
    return "";
  }
  let depth = 0;
  for (let index = openBrace; index < source.length; index += 1) {
    const char = source[index];
    if (char === "{") {
      depth += 1;
    }
    if (char === "}") {
      depth -= 1;
      if (depth === 0) {
        return source.slice(start, index + 1);
      }
    }
  }
  return source.slice(start);
}
