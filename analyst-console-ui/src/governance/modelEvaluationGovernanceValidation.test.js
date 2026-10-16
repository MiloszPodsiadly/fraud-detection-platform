import { describe, expect, it } from "vitest";
import { modelCard, modelCardWithoutEvaluation } from "../test-utils/modelCardFixtures.js";
import { isValidModelCard } from "./modelEvaluationGovernanceValidation.js";

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
    card.evaluationSummary.warnings = [
      "SOURCE_DATASET_INVALID_ROWS_SKIPPED",
      "SOURCE_DATASET_TRUNCATED"
    ];

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
    ["approved promotion state", (card) => { card.promotionState = "APPROVED"; }],
    ["artifact mismatch", (card) => { card.modelArtifactVerification.sha256 = "d".repeat(64); }],
    ["decision authority", (card) => { card.verifiedUsageBoundary.runtimeDecisionAuthority = "PRIMARY"; }],
    ["summary subject mismatch", (card) => { card.evaluationSummary.evaluationSubject.modelVersion = "other"; }],
    ["legacy classification policy", (card) => {
      card.evaluationSummary.supportedMetrics.mlPredictionMetrics.classificationPolicy =
        "HIGH_OR_CRITICAL_IS_POSITIVE";
    }],
    ["population mismatch", (card) => { card.evaluationSummary.population.recordsEvaluated = 1; }],
    ["missing required warning", (card) => {
      card.evaluationSummary.sourceDataset.truncated = true;
      card.evaluationSummary.sourceDataset.rawRowsRead += 1;
    }],
    ["additional false warning", (card) => {
      card.evaluationSummary.warnings = ["SOURCE_DATASET_TRUNCATED"];
    }],
    ["incomplete limitations", (card) => { card.evaluationSummary.limitations.pop(); }],
    ["unsupported contract", (card) => { card.schemaVersion = "ml-model-card-v2"; }]
  ])("rejects %s", (_label, mutate) => {
    const card = modelCard();
    mutate(card);
    expect(isValidModelCard(card)).toBe(false);
  });

  it("rejects high claimed precision that contradicts TP=20 and FP=80", () => {
    const card = modelCard();
    const summary = card.evaluationSummary;
    summary.sourceDataset.rawRowsRead = 100;
    summary.sourceDataset.recordsReturned = 100;
    summary.population.recordsConsidered = 100;
    summary.population.recordsWithPredictionEvidence = 100;
    summary.population.recordsEvaluated = 100;
    summary.classBalance = { positiveClassCount: 20, negativeClassCount: 80 };
    const metrics = summary.supportedMetrics.mlPredictionMetrics;
    Object.assign(metrics, {
      recordsEvaluated: 100,
      truePositive: 20,
      falsePositive: 80,
      trueNegative: 0,
      falseNegative: 0
    });
    metrics.precision.value = 0.95;
    metrics.recall.value = 1;
    metrics.truePositiveRate.value = 1;
    metrics.falsePositiveRate.value = 1;
    metrics.falseNegativeRate.value = 0;
    metrics.recallAtK[1].value.value = 0.05;
    metrics.recallAtK[2].value.value = 0.05;
    const disagreement = summary.supportedMetrics.rulesVsMlDisagreement;
    disagreement.recordsWithExactMlEvidence = 100;
    disagreement.recordsCompared = 100;
    disagreement.categoryCounts.BOTH_HIGH = 100;
    disagreement.categoryCounts.BOTH_LOW = 0;

    expect(isValidModelCard(card)).toBe(false);
  });

  it.each([
    ["incorrect precision", (metrics) => { metrics.precision.value = 0.95; }],
    ["incorrect recall", (metrics) => { metrics.recall.value = 0.5; }],
    ["incorrect true-positive rate", (metrics) => { metrics.truePositiveRate.value = 0.5; }],
    ["incorrect false-positive rate", (metrics) => { metrics.falsePositiveRate.value = 0.5; }],
    ["incorrect false-negative rate", (metrics) => { metrics.falseNegativeRate.value = 0.5; }],
    ["numeric unavailable metric", (metrics) => {
      metrics.precision = { available: false, reason: "NO_PREDICTED_POSITIVES", value: 0.5 };
    }],
    ["reason on available metric", (metrics) => { metrics.precision.reason = "ARBITRARY_REASON"; }],
    ["zero denominator represented as zero", (metrics) => {
      metrics.truePositive = 0;
      metrics.falseNegative = 1;
      metrics.precision = { available: true, reason: null, value: 0 };
    }],
    ["impossible precision prefix", (metrics) => { metrics.precisionAtK[2].value.value = 1; }],
    ["inconsistent recall prefix", (metrics) => { metrics.recallAtK[2].value.value = 0.5; }],
    ["conflicting actual K", (metrics) => { metrics.precisionAtK[2].actualK = 1; }],
    ["missing ranking key", (metrics) => { delete metrics.recallAtK[2]; }]
  ])("rejects %s", (_label, mutate) => {
    const card = modelCard();
    mutate(card.evaluationSummary.supportedMetrics.mlPredictionMetrics);

    expect(isValidModelCard(card)).toBe(false);
  });

  it("rejects disagreement categories that do not reconcile", () => {
    const card = modelCard();
    card.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.BOTH_LOW = 0;

    expect(isValidModelCard(card)).toBe(false);
  });

  it.each([
    ["colon syntax", (subject) => { subject.modelVersion = "2026:10"; }],
    ["oversized model name", (subject) => { subject.modelName = "m".repeat(65); }],
    ["forbidden identity content", (subject) => { subject.featureContractVersion = "customer-id-v2"; }]
  ])("rejects %s in model identity", (_label, mutate) => {
    const card = modelCard();
    mutate(card.subject);
    card.evaluationSummary.evaluationSubject = { ...card.subject };

    expect(isValidModelCard(card)).toBe(false);
  });
});
