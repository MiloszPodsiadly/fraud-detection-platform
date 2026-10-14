import { describe, expect, it } from "vitest";
import { modelCard, modelCardWithoutEvaluation } from "../test-utils/modelCardFixtures.js";
import { isValidModelCard } from "./modelCardValidation.js";

describe("isValidModelCard", () => {
  it("accepts exact artifact cards with and without configured evaluation", () => {
    expect(isValidModelCard(modelCard())).toBe(true);
    expect(isValidModelCard(modelCardWithoutEvaluation())).toBe(true);
  });

  it("accepts the bounded truncation sentinel row without expanding the evaluated population", () => {
    const card = modelCard();
    card.evaluationSummary.sourceDataset.rawRowsRead = 1001;
    card.evaluationSummary.sourceDataset.skippedInvalidSourceRecordCount = 998;
    card.evaluationSummary.sourceDataset.truncated = true;

    expect(isValidModelCard(card)).toBe(true);
  });

  it.each([
    ["open start", null, "2026-06-09T00:00:00Z"],
    ["open end", "2026-06-01T00:00:00Z", null]
  ])("accepts an %s evaluation window", (_label, fromInclusive, toInclusive) => {
    const card = modelCard();
    card.evaluationSummary.evaluationWindow = {
      timeBasis: "FEEDBACK_CREATED_AT",
      fromInclusive,
      toInclusive
    };

    expect(isValidModelCard(card)).toBe(true);
  });

  it.each([
    ["unknown field", (card) => { card.approved = true; }],
    ["promotion claim", (card) => { card.promotionState = "APPROVED"; }],
    ["artifact mismatch", (card) => { card.modelArtifactVerification.sha256 = "d".repeat(64); }],
    ["decision authority", (card) => { card.verifiedUsageBoundary.runtimeDecisionAuthority = "PRIMARY"; }],
    ["summary subject mismatch", (card) => { card.evaluationSummary.evaluationSubject.modelVersion = "other"; }],
    ["legacy classification policy", (card) => {
      card.evaluationSummary.supportedMetrics.mlPredictionMetrics.classificationPolicy =
        "HIGH_OR_CRITICAL_IS_POSITIVE";
    }],
    ["population mismatch", (card) => { card.evaluationSummary.population.recordsEvaluated = 1; }],
    ["unsupported contract", (card) => { card.schemaVersion = "ml-model-card-v2"; }]
  ])("rejects %s", (_label, mutate) => {
    const card = modelCard();
    mutate(card);
    expect(isValidModelCard(card)).toBe(false);
  });
});
