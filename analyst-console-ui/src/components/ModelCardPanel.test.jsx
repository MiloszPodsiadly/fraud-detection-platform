import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { modelCard, modelCardWithoutEvaluation } from "../test-utils/modelCardFixtures.js";
import { ModelCardPanel } from "./ModelCardPanel.jsx";

describe("ModelCardPanel", () => {
  it("renders identity, provenance, metrics, limitations, and no decision controls", () => {
    render(<ModelCardPanel card={modelCard()} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByRole("heading", { name: "Model Card" })).toBeInTheDocument();
    expect(screen.getByText("python-logistic-fraud-model")).toBeInTheDocument();
    expect(screen.getAllByText("a".repeat(64)).length).toBeGreaterThan(0);
    expect(screen.getByText("VERIFIED_REPORT_AND_MANIFEST")).toBeInTheDocument();
    expect(screen.getByText("NOT_EVALUATED_FOR_PROMOTION", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION")).toBeInTheDocument();
    expect(screen.getByText("EXACT_MODEL_OFFLINE_EVALUATION")).toBeInTheDocument();
    expect(screen.getByText("PRIMARY_PRODUCTION_FRAUD_DECISIONING")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Confusion matrix" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Ranking metrics" })).toBeInTheDocument();
    expect(screen.getByText("COMPLETE_EVALUATED_POPULATION")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Precision at K" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /approve|promote|deploy|decline/i })).not.toBeInTheDocument();
  });

  it("shows zero population as no data rather than complete coverage", () => {
    const card = zeroPopulationCard();

    render(<ModelCardPanel card={card} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByText("NO_DATA")).toBeInTheDocument();
    expect(screen.getByText("No evaluation data")).toBeInTheDocument();
    expect(screen.queryByText("COMPLETE_EVALUATED_POPULATION")).not.toBeInTheDocument();
  });

  it("shows source truncation independently from evaluated-population coverage", () => {
    const card = modelCard();
    card.evaluationSummary.sourceDataset.rawRowsRead = 3;
    card.evaluationSummary.sourceDataset.truncated = true;
    card.evaluationSummary.warnings = ["SOURCE_DATASET_TRUNCATED"];

    render(<ModelCardPanel card={card} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByText("COMPLETE_EVALUATED_POPULATION")).toBeInTheDocument();
    expect(screen.getByText("SOURCE_TRUNCATED")).toBeInTheDocument();
    expect(screen.getByText("Source dataset truncated")).toBeInTheDocument();
  });

  it("rejects a response missing the approved intended-use policy", () => {
    const card = modelCard();
    delete card.verifiedUsageBoundary.intendedUse;

    render(<ModelCardPanel card={card} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByText("Unsupported Model Card contract")).toBeInTheDocument();
  });

  it("shows evaluation-not-configured without hiding verified model identity", () => {
    render(<ModelCardPanel card={modelCardWithoutEvaluation()} isLoading={false} error={null} canReadModelCard />);
    expect(screen.getByText("No model evaluation evidence")).toBeInTheDocument();
    expect(screen.getByText("MODEL_EVALUATION_NOT_CONFIGURED", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("python-logistic-fraud-model")).toBeInTheDocument();
  });

  it("makes partial coverage and unavailable metrics explicit", () => {
    const card = modelCard();
    card.evaluationSummary.population.recordsEvaluated = 1;
    card.evaluationSummary.population.recordsWithPredictionEvidence = 1;
    card.evaluationSummary.population.recordsExcludedMissingPredictionEvidence = 1;
    card.evaluationSummary.classBalance.negativeClassCount = 0;
    const prediction = card.evaluationSummary.supportedMetrics.mlPredictionMetrics;
    prediction.recordsEvaluated = 1;
    prediction.trueNegative = 0;
    prediction.falsePositiveRate = {
      available: false,
      reason: "NO_ACTUAL_NEGATIVES",
      value: null
    };
    prediction.precisionAtK[2] = {
      requestedK: 2,
      actualK: 1,
      value: { available: true, reason: null, value: 1 }
    };
    prediction.recallAtK[2] = {
      requestedK: 2,
      actualK: 1,
      value: { available: true, reason: null, value: 1 }
    };
    card.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.recordsWithExactMlEvidence = 1;
    card.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.recordsCompared = 1;
    card.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.BOTH_LOW = 0;
    card.evaluationSummary.warnings = ["MODEL_EVALUATION_PARTIAL_COVERAGE", "SINGLE_CLASS_MODEL_LINEAGE_RECORDS"];

    render(<ModelCardPanel card={card} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByText("Partial evaluation coverage")).toBeInTheDocument();
    expect(screen.getByText("Not available: NO_ACTUAL_NEGATIVES")).toBeInTheDocument();
  });

  it.each([[404, "No current Model Card"], [503, "Model Card unavailable"]])(
    "renders HTTP %s distinctly",
    (status, title) => {
      render(<ModelCardPanel card={null} isLoading={false} error={{ status }} canReadModelCard />);
      expect(screen.getByText(title)).toBeInTheDocument();
    }
  );

  it("keeps unauthorized state free of retry actions", () => {
    const onRetry = vi.fn();
    render(<ModelCardPanel card={null} isLoading={false} error={null} canReadModelCard={false} onRetry={onRetry} />);
    expect(screen.getByText("Access denied")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(onRetry).not.toHaveBeenCalled();
  });
});

function zeroPopulationCard() {
  const card = modelCard();
  const summary = card.evaluationSummary;
  Object.assign(summary.sourceDataset, { rawRowsRead: 0, recordsReturned: 0 });
  Object.keys(summary.population).forEach((field) => { summary.population[field] = 0; });
  Object.assign(summary.classBalance, { positiveClassCount: 0, negativeClassCount: 0 });
  summary.supportedMetrics.classBalance = {
    available: false,
    reason: "INSUFFICIENT_MODEL_LINEAGE_RECORDS"
  };
  const prediction = summary.supportedMetrics.mlPredictionMetrics;
  Object.assign(prediction, {
    available: false,
    reason: "MODEL_PREDICTION_SIGNAL_UNAVAILABLE",
    recordsEvaluated: 0,
    truePositive: 0,
    falsePositive: 0,
    trueNegative: 0,
    falseNegative: 0,
    precision: unavailableMetric("NO_PREDICTED_POSITIVES"),
    recall: unavailableMetric("NO_ACTUAL_POSITIVES"),
    truePositiveRate: unavailableMetric("NO_ACTUAL_POSITIVES"),
    falsePositiveRate: unavailableMetric("NO_ACTUAL_NEGATIVES"),
    falseNegativeRate: unavailableMetric("NO_ACTUAL_POSITIVES")
  });
  for (const rows of [prediction.precisionAtK, prediction.recallAtK]) {
    for (const row of Object.values(rows)) {
      row.actualK = 0;
      row.value = unavailableMetric("NO_SCORED_RECORDS");
    }
  }
  Object.assign(summary.supportedMetrics.rulesVsMlDisagreement, {
    available: false,
    reason: "RULES_SIGNAL_UNAVAILABLE",
    recordsWithExactMlEvidence: 0,
    recordsCompared: 0,
    recordsExcludedRulesEvidenceUnavailable: 0,
    categoryCounts: { ML_HIGH_RULES_LOW: 0, RULES_HIGH_ML_LOW: 0, BOTH_HIGH: 0, BOTH_LOW: 0 }
  });
  summary.warnings = ["INSUFFICIENT_MODEL_LINEAGE_RECORDS", "MODEL_PREDICTION_SIGNAL_UNAVAILABLE"];
  return card;
}

function unavailableMetric(reason) {
  return { available: false, reason, value: null };
}
