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
    expect(screen.queryByRole("button", { name: /approve|promote|deploy|decline/i })).not.toBeInTheDocument();
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
    card.evaluationSummary.supportedMetrics.mlPredictionMetrics.recordsEvaluated = 1;
    card.evaluationSummary.supportedMetrics.mlPredictionMetrics.precision = {
      available: false,
      reason: "SINGLE_CLASS_MODEL_LINEAGE_RECORDS",
      value: null
    };
    card.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.recordsWithExactMlEvidence = 1;
    card.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.recordsCompared = 1;
    card.evaluationSummary.supportedMetrics.rulesVsMlDisagreement.categoryCounts.BOTH_LOW = 0;
    card.evaluationSummary.warnings = ["MODEL_EVALUATION_PARTIAL_COVERAGE"];

    render(<ModelCardPanel card={card} isLoading={false} error={null} canReadModelCard />);

    expect(screen.getByText("Partial evaluation coverage")).toBeInTheDocument();
    expect(screen.getByText("Not available: SINGLE_CLASS_MODEL_LINEAGE_RECORDS")).toBeInTheDocument();
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
