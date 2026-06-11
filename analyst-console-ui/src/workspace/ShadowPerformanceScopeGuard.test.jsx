import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const alertsApiSource = readFileSync(join(process.cwd(), "src/api/alertsApi.js"), "utf8");
const shadowApiSource = [
  lineContaining(alertsApiSource, "getCurrentShadowPerformanceSummary"),
  functionBlock(alertsApiSource, "shadowPerformanceSummaryRequest")
].join("\n");
const shadowSources = [
  "src/components/ShadowPerformanceDashboard.jsx",
  "src/workspace/ShadowPerformanceWorkspaceRuntime.jsx",
  "src/workspace/useShadowPerformanceSummary.js",
  "src/pages/ShadowPerformanceDashboardPage.jsx"
].map((path) => readFileSync(join(process.cwd(), path), "utf8")).concat(shadowApiSource).join("\n");

const docs = readFileSync(join(process.cwd(), "../docs/architecture/shadow_performance_dashboard_ui.md"), "utf8");

describe("FDP-107 shadow performance UI scope guards", () => {
  it("doesNotAddPromotionWorkflow", () => {
    expect(shadowSources).not.toMatch(/promotionWorkflow|promoteModel|safe to promote|promotion readiness score/i);
  });

  it("doesNotAddThresholdControls", () => {
    expect(shadowSources).not.toMatch(/thresholdControl|changeThreshold|recommendedThreshold|threshold suggestion/i);
  });

  it("doesNotAddModelRegistryControls", () => {
    expect(shadowSources).not.toMatch(/modelRegistry|registerModel|model lifecycle mutation/i);
  });

  it("doesNotAddRetrainingControls", () => {
    expect(shadowSources).not.toMatch(/retrain|trainingJob|triggerTraining/i);
  });

  it("doesNotAddKafkaCalls", () => {
    expect(shadowSources).not.toMatch(/kafka|topic|producer|consumer/i);
  });

  it("doesNotAddScoringMutationCalls", () => {
    expect(shadowSources).not.toMatch(/\/scored\/.*(decision|feedback)|scoreTransaction|production scoring/i);
  });

  it("doesNotAddFraudCaseMutationCalls", () => {
    expect(shadowSources).not.toMatch(/updateFraudCase|fraud-case.*PATCH|fraud case status mutation/i);
  });

  it("doesNotAddAlertSeverityMutationCalls", () => {
    expect(shadowSources).not.toMatch(/alertSeverity|severity mutation|\/alerts\/.*severity/i);
  });

  it("doesNotAddPaymentAuthorizationCalls", () => {
    expect(shadowSources).not.toMatch(/authorizePayment|paymentAuthorizationCall|\/payments|payment authorization call/i);
  });

  it("doesNotAddFeedbackMutation", () => {
    expect(shadowSources).not.toMatch(/\bsubmit[A-Za-z]*Feedback\b|feedback.*POST|engine-intelligence\/feedback/i);
  });

  it("doesNotAddFiltersSearchHistoryOrExport", () => {
    expect(shadowSources).not.toMatch(/(?<!\.)\bfilter\b|\b(search|history|download|pagination|modelVersion selector|exportCsv|exportDownload|dataExport)\b/i);
  });

  it("doesNotAddModelComparisonTable", () => {
    expect(shadowSources).not.toMatch(/model comparison|comparison table|compare models/i);
  });

  it("doesNotAddTrendCharts", () => {
    expect(shadowSources).not.toMatch(/trend|chart|sparkline|timeseries/i);
  });

  it("doesNotAddRawArtifactViews", () => {
    expect(shadowSources).not.toMatch(/raw Model Card view|rawEvaluationReport|rawDataset|FDP-102 JSONL/i);
  });

  it("documentsFdp107ArchitectureBoundaries", () => {
    expect(docs).toContain("FDP-107 consumes only the FDP-106 current Shadow Performance Summary endpoint");
    expect(docs).toContain("FDP-107 does not compute metrics");
    expect(docs).toContain("FDP-107 does not recompute shadow performance");
    expect(docs).toContain("FDP-107 does not read raw artifacts");
    expect(docs).toContain("FDP-107 does not recommend promotion");
    expect(docs).toContain("FDP-107 does not recommend thresholds");
    expect(docs).toContain("FDP-107 does not influence analyst recommendations");
    expect(docs).toContain("FDP-107 does not affect production scoring");
    expect(docs).toContain("FDP-107 is not payment authorization");
    expect(docs).toContain("FDP-107 is not automatic decisioning");
    expect(docs).toContain("FDP-107 does not mutate alert state or fraud-case state");
    expect(docs).toContain("FDP-107 does not add filters, search, history, export");
    expect(docs).toContain("404 for `GET /api/v1/governance/shadow-performance/summary/current`, it means no current validated Shadow Performance Summary exists");
    expect(docs).toContain("This 404 state is not a model quality result");
    expect(docs).toContain("fake, zero, sample, fallback, stale, or cached metrics on 404");
    expect(docs).toContain("Metrics appear only after FDP-106 exposes a current validated summary");
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
  const openBrace = source.indexOf("{", start);
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
