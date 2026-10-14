import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createAlertsApiClient } from "../api/alertsApi.js";
import { createDemoAuthProvider } from "../auth/authProvider.js";
import { isValidModelCard } from "../governance/modelCardValidation.js";
import { ModelCardPanel } from "./ModelCardPanel.jsx";

const MODEL_SHA = "55499538d93a634bb5c5fc2326380d013efaea8b0045320f838cad7405df15b6";
const GOLDEN_CARD = JSON.parse(readFileSync(resolve(
  process.cwd(),
  "../alert-service/src/test/resources/fixtures/model-card/python-generated-available-v1/ml_model_card.json"
), "utf8"));

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
});

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
