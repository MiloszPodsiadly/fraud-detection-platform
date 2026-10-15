import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createAlertsApiClient } from "../api/alertsApi.js";
import { createDemoAuthProvider } from "../auth/authProvider.js";
import { isValidModelCard } from "../governance/modelCardValidation.js";
import { ModelCardPanel } from "./ModelCardPanel.jsx";

const MODEL_SHA = "55499538d93a634bb5c5fc2326380d013efaea8b0045320f838cad7405df15b6";
const GOLDEN_CARD = goldenCard("python-generated-available-v1");
const MIXED_CARD = goldenCard("python-generated-mixed-v1");
const NO_DATA_CARD = goldenCard("python-generated-no-data-v1");
const PARTIAL_TRUNCATED_CARD = goldenCard("python-generated-partial-truncated-v1");

describe("Python-generated Model Card golden contract", () => {
  afterEach(() => vi.restoreAllMocks());

  it("survives the API client validator and renders the exact evidence", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(GOLDEN_CARD), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));

    const card = await client().getCurrentModelCard();

    expect(isValidModelCard(card)).toBe(true);
    expect(card.subject.modelArtifactSha256).toBe(MODEL_SHA);
    expect(card.evaluationSummary.evaluationSubject.modelArtifactSha256).toBe(MODEL_SHA);
    expect(card.evaluationSummary.population.recordsEvaluated).toBe(1);
    expect(card.verifiedUsageBoundary.intendedUse).toContain("EXACT_MODEL_OFFLINE_EVALUATION");
    render(<ModelCardPanel card={card} isLoading={false} error={null} canReadModelCard />);
    expect(screen.getByText("python-logistic-fraud-model")).toBeInTheDocument();
    expect(screen.getByText("EXACT_MODEL_OFFLINE_EVALUATION")).toBeInTheDocument();
    expect(screen.getByText("Not available: NO_ACTUAL_NEGATIVES")).toBeInTheDocument();
  });

  it("preserves mixed-population rounding, ranking, identity, and usage boundaries", () => {
    expect(isValidModelCard(MIXED_CARD)).toBe(true);
    expect(MIXED_CARD.subject.modelArtifactSha256).toBe(MODEL_SHA);
    expect(MIXED_CARD.evaluationSummary.supportedMetrics.mlPredictionMetrics.precision.value).toBe(0.666667);
    expect(MIXED_CARD.evaluationSummary.supportedMetrics.mlPredictionMetrics.falsePositiveRate.value).toBe(0.333333);
    expect(MIXED_CARD.evaluationSummary.supportedMetrics.mlPredictionMetrics.precisionAtK["3"].actualK).toBe(3);
    expect(MIXED_CARD.evaluationSummary.supportedMetrics.mlPredictionMetrics.precisionAtK["3"].value.value).toBe(0.666667);

    render(<ModelCardPanel card={MIXED_CARD} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getAllByText("66.67%").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("33.33%")).toBeInTheDocument();
    expect(screen.getByRole("row", { name: "3 3 66.67% 50.00%" })).toBeInTheDocument();
    expect(screen.getByRole("row", { name: "7 7 57.14% 100.00%" })).toBeInTheDocument();
    expect(screen.getByText("EXACT_MODEL_OFFLINE_EVALUATION")).toBeInTheDocument();
    expect(screen.getByText("PRIMARY_PRODUCTION_FRAUD_DECISIONING")).toBeInTheDocument();
  });

  it("keeps no-data metric unavailability distinct from numeric zero", () => {
    expect(isValidModelCard(NO_DATA_CARD)).toBe(true);

    render(<ModelCardPanel card={NO_DATA_CARD} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByRole("heading", { name: "No evaluation data" })).toBeInTheDocument();
    expect(screen.getByText("NO_DATA")).toBeInTheDocument();
    expect(screen.getByText("Not available: NO_PREDICTED_POSITIVES")).toBeInTheDocument();
    expect(screen.queryByText("0.00%")).not.toBeInTheDocument();
  });

  it("renders partial truncated evidence and available numeric zero exactly", () => {
    expect(isValidModelCard(PARTIAL_TRUNCATED_CARD)).toBe(true);
    expect(PARTIAL_TRUNCATED_CARD.evaluationSummary.supportedMetrics.mlPredictionMetrics.falsePositiveRate)
      .toEqual({ available: true, reason: null, value: 0.0 });

    render(<ModelCardPanel card={PARTIAL_TRUNCATED_CARD} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByRole("heading", { name: "Partial evaluation coverage" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Source dataset truncated" })).toBeInTheDocument();
    expect(screen.getByText("PARTIAL")).toBeInTheDocument();
    expect(screen.getByText("SOURCE_TRUNCATED")).toBeInTheDocument();
    expect(screen.getAllByText("0.00%")).toHaveLength(2);
    expect(screen.getByText("MODEL_EVALUATION_PARTIAL_COVERAGE")).toBeInTheDocument();
    expect(screen.getByText("SOURCE_DATASET_TRUNCATED")).toBeInTheDocument();
  });

  it("keeps the evidence renderer free of raw HTML injection", () => {
    const source = readFileSync(resolve(process.cwd(), "src/components/ModelCardPanel.jsx"), "utf8");
    expect(source).not.toContain("dangerouslySetInnerHTML");
  });
});

function goldenCard(fixtureName) {
  return JSON.parse(readFileSync(resolve(
    process.cwd(),
    `../alert-service/src/test/resources/fixtures/model-card/${fixtureName}/ml_model_card.json`
  ), "utf8"));
}

function client() {
  return createAlertsApiClient({
    session: {
      userId: "admin-1",
      roles: ["FRAUD_OPS_ADMIN"],
      extraAuthorities: [],
      authorities: ["model-card:read"]
    },
    authProvider: createDemoAuthProvider()
  });
}
