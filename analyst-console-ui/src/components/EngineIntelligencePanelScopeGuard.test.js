import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { parse } from "@babel/parser";
import { describe, expect, it } from "vitest";

const FORBIDDEN_ACTION_NAMES = new Set([
  "submitDecision",
  "submitAnalystDecision",
  "analystAction",
  "updateFraudCase",
  "updateAlertSeverity",
  "setCaseStatus",
  "setAlertSeverity",
  "finalDecision",
  "recommendedAction"
]);

const FORBIDDEN_FEEDBACK_COPY = /\b(?:approve|approved|decline|declined|block|blocked|final decision|recommended action|model training|retrain|update rule)\b/i;
const FORBIDDEN_SERVICE_IMPORT = /(?:^|[/.-])(?:scoring|ml|rules|orchestrator|kafka|events)(?:$|[/.-]|api\b|client\b|service\b)/i;
const FORBIDDEN_ENDPOINT = /\/api\/v\d+\/(?:scoring|ml|rules|orchestrator)(?:\/|\b)/i;

describe("EngineIntelligencePanelScopeGuard", () => {
  it("allows explanatory decision wording without allowing decision actions", () => {
    const disclaimer = `export function Panel() {
      return <p>Diagnostic only. It does not change Rules/ML decisions.</p>;
    }`;

    expect(auditPanelSource(disclaimer)).toEqual([]);
    expect(auditPanelSource(panelSource())).toEqual([]);
  });

  it("rejects analyst decision props and callbacks", () => {
    expect(auditPanelSource("function Panel({ submitDecision }) { return null; }")).toContain(
      "forbidden action name: submitDecision"
    );
    expect(auditPanelSource("<Panel onDecision={submitDecision} />").join(" ")).toMatch(/submitDecision|onDecision/);
  });

  it("rejects case status and severity mutations", () => {
    expect(auditPanelSource('caseRecord.status = "CLOSED";')).toContain("case or alert status/severity mutation");
    expect(auditPanelSource('alert.severity = "HIGH";')).toContain("case or alert status/severity mutation");
  });

  it("rejects scoring service imports, calls, and endpoints", () => {
    const injectedScoringCall = `
      import scoringService from "../services/scoringService.js";
      scoringService.scoreTransaction(transactionId);
      fetch("/api/v1/scoring/transactions");
    `;
    expect(auditPanelSource(injectedScoringCall).join(" ")).toMatch(/forbidden service import|scoring service call|forbidden scoring endpoint/);
  });

  it("rejects interactive decision controls in the diagnostic panel", () => {
    expect(auditPanelSource('<button onClick={submitDecision}>Approve</button>').join(" ")).toMatch(/interactive control|event handler/);
    expect(auditPanelSource("<form onSubmit={submitDecision} />").join(" ")).toMatch(/interactive control|event handler/);
  });

  it("keeps feedback dedicated to structured choices and rejects decision instructions", () => {
    expect(feedbackPanelSource()).toContain('type="radio"');
    expect(auditFeedbackSource(feedbackPanelSource())).toEqual([]);
    expect(auditFeedbackSource('<form><p>Please provide your final decision.</p></form>')).toContain(
      "forbidden decisioning feedback text"
    );
    expect(auditFeedbackSource('<form><textarea /></form>')).toContain("free-text feedback control");
  });

  it("does not add case-level aggregation or a dashboard/search view", () => {
    expect(panelSource()).not.toMatch(/case-level aggregation|aggregateEngine|caseEngineIntelligence/i);
    expect(panelSource()).not.toMatch(pattern(["dashboard", "bulk view"]));
    expect(pagePanelSnippet()).not.toMatch(pattern(["dashboard", "bulk"]));
    expect(engineClientSource()).not.toMatch(/engine-intelligence\?|\/engine-intelligence\/search|listEngineIntelligence/i);
  });

  it("calls only transaction-scoped engine intelligence endpoints", () => {
    const source = engineClientSource();

    expect(source).toContain(engineIntelligenceEndpoint("transactions", "scored"));
    expect(source).not.toContain(engineIntelligenceEndpoint("fraud-cases"));
    expect(feedbackClientSource()).toContain(engineIntelligenceFeedbackEndpoint("transactions", "scored"));
    expect(feedbackClientSource()).not.toContain(engineIntelligenceFeedbackEndpoint("fraud-cases"));
    expect(feedbackClientSource()).not.toMatch(/engine-intelligence\/search|listEngineIntelligence/i);
    expect(source).not.toMatch(pattern(["/scoring", "/ml", "/rules", "/orchestrator", "ka" + "fka", "events"]));
    expect(feedbackClientSource()).not.toMatch(pattern(["/scoring", "/ml", "/rules", "/orchestrator", "ka" + "fka", "events"]));
  });
});

function auditPanelSource(source) {
  return auditAst(source, (node, violations) => {
    if (node.type === "Identifier" && FORBIDDEN_ACTION_NAMES.has(node.name)) {
      violations.push(`forbidden action name: ${node.name}`);
    }

    if (node.type === "ImportDeclaration" && FORBIDDEN_SERVICE_IMPORT.test(node.source.value)) {
      violations.push("forbidden service import");
    }

    if (node.type === "JSXAttribute") {
      const name = jsxName(node.name);
      if (FORBIDDEN_ACTION_NAMES.has(name) || /^on[A-Z]/.test(name)) {
        violations.push(`forbidden action prop or event handler: ${name}`);
      }
    }

    if (node.type === "JSXOpeningElement") {
      const name = jsxName(node.name).toLowerCase();
      if (name === "button" || name === "form") {
        violations.push(`interactive control in diagnostic panel: ${name}`);
      }
      if (name === "input" && ["button", "submit"].includes(jsxAttributeValue(node, "type")?.toLowerCase())) {
        violations.push("interactive control in diagnostic panel: input");
      }
    }

    if (node.type === "CallExpression") {
      const callee = node.callee;
      const calleeName = callee.type === "Identifier" ? callee.name : "";
      const receiverName = callee.type === "MemberExpression" && callee.object.type === "Identifier"
        ? callee.object.name
        : "";
      if (/^(?:scoring|ml|rules|orchestrator)(?:Service|Client|Api)?$/i.test(receiverName)
        || /^(?:scoreTransaction|runScoring|executeScoring|invokeScoringService)$/i.test(calleeName)) {
        violations.push("scoring service call");
      }
    }

    if (node.type === "StringLiteral" && FORBIDDEN_ENDPOINT.test(node.value)) {
      violations.push("forbidden scoring endpoint");
    }
    if (node.type === "TemplateElement" && FORBIDDEN_ENDPOINT.test(node.value.raw)) {
      violations.push("forbidden scoring endpoint");
    }

    if ((node.type === "AssignmentExpression" || node.type === "UpdateExpression")
      && isStatusOrSeverityMember(node.type === "AssignmentExpression" ? node.left : node.argument)) {
      violations.push("case or alert status/severity mutation");
    }
  });
}

function auditFeedbackSource(source) {
  let hasForm = false;
  let hasSubmitButton = false;
  let hasRadioInput = false;
  const violations = auditAst(source, (node, currentViolations) => {
    if (node.type === "JSXOpeningElement") {
      const name = jsxName(node.name).toLowerCase();
      const inputType = jsxAttributeValue(node, "type")?.toLowerCase();
      if (name === "form") hasForm = true;
      if (name === "textarea" || (name === "input" && inputType && inputType !== "radio" && inputType !== "checkbox")) {
        currentViolations.push("free-text feedback control");
      }
      if (name === "input" && inputType === "radio") hasRadioInput = true;
      if (name === "button" && inputType === "submit") hasSubmitButton = true;
      if (jsxAttributeValue(node, "contentEditable") === true || jsxAttributeValue(node, "contentEditable") === "true") {
        currentViolations.push("free-text feedback control");
      }
    }

    if (node.type === "Identifier" && FORBIDDEN_ACTION_NAMES.has(node.name)) {
      currentViolations.push(`forbidden action name: ${node.name}`);
    }
    if (node.type === "StringLiteral" && FORBIDDEN_FEEDBACK_COPY.test(node.value)) {
      currentViolations.push("forbidden decisioning feedback text");
    }
    if (node.type === "JSXText" && FORBIDDEN_FEEDBACK_COPY.test(node.value)) {
      currentViolations.push("forbidden decisioning feedback text");
    }
  });

  if (!hasForm) violations.push("structured feedback form is missing");
  if (!hasSubmitButton) violations.push("feedback submit button is missing");
  if (!hasRadioInput) violations.push("structured feedback choices are missing");
  return violations;
}

function auditAst(source, inspectNode) {
  const ast = parse(source, { sourceType: "module", plugins: ["jsx"] });
  const violations = [];
  visitAst(ast, (node) => inspectNode(node, violations));
  return [...new Set(violations)];
}

function visitAst(node, callback) {
  if (!node || typeof node !== "object") return;
  if (typeof node.type === "string") callback(node);
  for (const [key, value] of Object.entries(node)) {
    if (["loc", "start", "end", "extra", "comments", "tokens", "errors"].includes(key)) continue;
    if (Array.isArray(value)) {
      for (const child of value) visitAst(child, callback);
    } else if (value && typeof value === "object") {
      visitAst(value, callback);
    }
  }
}

function isStatusOrSeverityMember(node) {
  return node?.type === "MemberExpression"
    && !node.computed
    && node.property?.type === "Identifier"
    && ["status", "severity"].includes(node.property.name.toLowerCase());
}

function jsxName(node) {
  if (!node) return "";
  if (node.type === "JSXIdentifier") return node.name;
  if (node.type === "JSXMemberExpression") return `${jsxName(node.object)}.${jsxName(node.property)}`;
  return "";
}

function jsxAttributeValue(element, expectedName) {
  const attribute = element.attributes.find((candidate) => candidate.type === "JSXAttribute"
    && jsxName(candidate.name) === expectedName);
  if (!attribute) return undefined;
  if (attribute.value?.type === "StringLiteral") return attribute.value.value;
  if (attribute.value?.type === "JSXExpressionContainer") {
    const expression = attribute.value.expression;
    if (expression.type === "BooleanLiteral") return expression.value;
    if (expression.type === "StringLiteral") return expression.value;
  }
  return undefined;
}

function panelSource() {
  return readFileSync(resolve(process.cwd(), "src/components/EngineIntelligencePanel.jsx"), "utf8");
}

function feedbackPanelSource() {
  return readFileSync(resolve(process.cwd(), "src/components/EngineIntelligenceFeedbackPanel.jsx"), "utf8");
}

function pagePanelSnippet() {
  const source = readFileSync(resolve(process.cwd(), "src/pages/FraudCaseDetailsPage.jsx"), "utf8");
  const start = source.indexOf("<EngineIntelligencePanel");
  const end = source.indexOf("</td>", start);
  if (start < 0 || end < 0) throw new Error("EngineIntelligencePanel page integration boundary was not found");
  return source.slice(start, end);
}

function engineClientSource() {
  const source = readFileSync(resolve(process.cwd(), "src/api/alertsApi.js"), "utf8");
  return boundedSlice(source, "async function getEngineIntelligenceWithRequest", "function engineIntelligenceRequestOptions");
}

function feedbackClientSource() {
  const source = readFileSync(resolve(process.cwd(), "src/api/alertsApi.js"), "utf8");
  return boundedSlice(
    source,
    "async function submitEngineIntelligenceFeedbackWithRequest",
    "function normalizeEngineIntelligenceFeedbackPayload"
  );
}

function boundedSlice(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start);
  if (start < 0 || end < 0) throw new Error(`Could not locate bounded API slice: ${startMarker}`);
  return source.slice(start, end);
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
