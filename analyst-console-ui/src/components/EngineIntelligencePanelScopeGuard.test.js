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
    expect(engineClientSource()).not.toMatch(pattern(["/scoring", "/ml", "/rules", "/orchestrator", "ka" + "fka", "events"]));
  });

  it("doesNotAddEngineIntelligenceDashboard", () => {
    expect(panelSource()).not.toMatch(pattern(["dashboard", "bu" + "lk view"]));
    expect(pagePanelSnippet()).not.toMatch(pattern(["dashboard", "bu" + "lk"]));
  });

  it("doesNotAddListSearchEngineIntelligenceView", () => {
    expect(engineClientSource()).not.toMatch(/engine-intelligence\?|\/engine-intelligence\/search|listEngineIntelligence/i);
  });

  it("uiCallsOnlyFdp96EngineIntelligenceEndpoint", () => {
    const source = engineClientSource();

    expect(source).toContain(engineIntelligenceEndpoint("transactions", "scored"));
    expect(source).not.toContain(engineIntelligenceEndpoint("fraud-cases"));
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

function engineIntelligenceEndpoint(...segments) {
  return ["/api", "v1", ...segments, "${encodeURIComponent(normalizedTransactionId)}", "engine-intelligence"].join("/");
}

function pattern(terms) {
  return new RegExp(terms.map(escapeRegExp).join("|"), "i");
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
