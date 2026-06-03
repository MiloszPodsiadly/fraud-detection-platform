import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("EngineIntelligencePanelScopeGuard", () => {
  it("doesNotCallFeedbackWorkflow", () => {
    expect(panelSource()).not.toMatch(/feedback|submitFeedback|recordFeedback/i);
    expect(engineClientSource()).not.toMatch(/feedback|submitFeedback|recordFeedback/i);
  });

  it("doesNotSubmitAnalystAction", () => {
    expect(panelSource()).not.toMatch(/submitAnalyst|analystAction|decision/i);
    expect(pagePanelSnippet()).not.toContain("submitDecision");
  });

  it("doesNotChangeFraudCaseStatus", () => {
    expect(panelSource()).not.toMatch(/updateFraudCase|case status|fraud case status/i);
    expect(pagePanelSnippet()).not.toContain("updateFraudCase");
  });

  it("doesNotChangeAlertSeverity", () => {
    expect(panelSource()).not.toMatch(/severity|updateAlert|alert priority/i);
  });

  it("doesNotAddCaseLevelAggregation", () => {
    expect(panelSource()).not.toMatch(/case-level aggregation|aggregateEngine|caseEngineIntelligence/i);
  });

  it("doesNotCallScoringMlRulesOrOrchestrator", () => {
    expect(engineClientSource()).not.toMatch(/\/scoring|\/ml|\/rules|\/orchestrator|kafka|events/i);
  });

  it("doesNotAddEngineIntelligenceDashboard", () => {
    expect(panelSource()).not.toMatch(/dashboard|bulk view/i);
    expect(pagePanelSnippet()).not.toMatch(/dashboard|bulk/i);
  });

  it("doesNotAddListSearchEngineIntelligenceView", () => {
    expect(engineClientSource()).not.toMatch(/engine-intelligence\?|\/engine-intelligence\/search|listEngineIntelligence/i);
  });

  it("uiCallsOnlyFdp96EngineIntelligenceEndpoint", () => {
    const source = engineClientSource();

    expect(source).toContain("/api/v1/transactions/scored/${encodeURIComponent(normalizedTransactionId)}/engine-intelligence");
    expect(source).not.toContain("/api/v1/fraud-cases/${encodeURIComponent(normalizedTransactionId)}/engine-intelligence");
  });
});

function panelSource() {
  return readFileSync(resolve(process.cwd(), "src/components/EngineIntelligencePanel.jsx"), "utf8");
}

function pagePanelSnippet() {
  const source = readFileSync(resolve(process.cwd(), "src/pages/FraudCaseDetailsPage.jsx"), "utf8");
  return source.slice(source.indexOf("<EngineIntelligencePanel"), source.indexOf("</td>", source.indexOf("<EngineIntelligencePanel")));
}

function engineClientSource() {
  const source = readFileSync(resolve(process.cwd(), "src/api/alertsApi.js"), "utf8");
  return source.slice(source.indexOf("async function getEngineIntelligenceWithRequest"), source.indexOf("function engineIntelligenceRequestOptions"));
}
