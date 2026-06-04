import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("EngineIntelligencePanelScopeGuard", () => {
  it("feedbackWorkflowIsDedicatedAndStructuredOnly", () => {
    expect(panelSource()).toContain("EngineIntelligenceFeedbackPanel");
    expect(feedbackPanelSource()).toContain("type=\"radio\"");
    expect(feedbackPanelSource()).not.toMatch(/textarea|comment|freeText|finalDecision|recommendedAction/i);
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
    expect(feedbackClientSource()).not.toMatch(pattern(["/scoring", "/ml", "/rules", "/orchestrator", "ka" + "fka", "events"]));
  });

  it("doesNotAddEngineIntelligenceDashboard", () => {
    expect(panelSource()).not.toMatch(pattern(["dashboard", "bu" + "lk view"]));
    expect(pagePanelSnippet()).not.toMatch(pattern(["dashboard", "bu" + "lk"]));
  });

  it("doesNotAddListSearchEngineIntelligenceView", () => {
    expect(engineClientSource()).not.toMatch(/engine-intelligence\?|\/engine-intelligence\/search|listEngineIntelligence/i);
  });

  it("uiCallsOnlyTransactionScopedEngineIntelligenceEndpoints", () => {
    const source = engineClientSource();

    expect(source).toContain(engineIntelligenceEndpoint("transactions", "scored"));
    expect(source).not.toContain(engineIntelligenceEndpoint("fraud-cases"));
    expect(feedbackClientSource()).toContain(engineIntelligenceFeedbackEndpoint("transactions", "scored"));
    expect(feedbackClientSource()).not.toContain(engineIntelligenceFeedbackEndpoint("fraud-cases"));
    expect(feedbackClientSource()).not.toMatch(/engine-intelligence\/search|listEngineIntelligence/i);
  });

  it("feedbackUiDoesNotRenderDecisioningActionsOrFreeTextInput", () => {
    expect(feedbackPanelSource()).not.toMatch(pattern([
      "approve",
      "decline",
      "block",
      "final decision",
      "recommended action",
      "model training",
      "retrain",
      "update rule",
      "textarea"
    ]));
  });
});

function panelSource() {
  return readFileSync(resolve(process.cwd(), "src/components/EngineIntelligencePanel.jsx"), "utf8");
}

function feedbackPanelSource() {
  return readFileSync(resolve(process.cwd(), "src/components/EngineIntelligenceFeedbackPanel.jsx"), "utf8");
}

function pagePanelSnippet() {
  const source = readFileSync(resolve(process.cwd(), "src/pages/FraudCaseDetailsPage.jsx"), "utf8");
  return source.slice(source.indexOf("<EngineIntelligencePanel"), source.indexOf("</td>", source.indexOf("<EngineIntelligencePanel")));
}

function engineClientSource() {
  const source = readFileSync(resolve(process.cwd(), "src/api/alertsApi.js"), "utf8");
  return source.slice(source.indexOf("async function getEngineIntelligenceWithRequest"), source.indexOf("function engineIntelligenceRequestOptions"));
}

function feedbackClientSource() {
  const source = readFileSync(resolve(process.cwd(), "src/api/alertsApi.js"), "utf8");
  return source.slice(
    source.indexOf("async function submitEngineIntelligenceFeedbackWithRequest"),
    source.indexOf("function normalizeEngineIntelligenceFeedbackPayload")
  );
}

function engineIntelligenceEndpoint(...segments) {
  return ["/api", "v1", ...segments, "${encodeURIComponent(normalizedTransactionId)}", "engine-intelligence"].join("/");
}

function engineIntelligenceFeedbackEndpoint(...segments) {
  return [...engineIntelligenceEndpoint(...segments).split("/"), "feedback"].join("/");
}

function pattern(terms) {
  return new RegExp(terms.map(escapeRegExp).join("|"), "i");
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
