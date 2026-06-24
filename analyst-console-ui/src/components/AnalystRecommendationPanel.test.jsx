import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AnalystRecommendationPanel } from "./AnalystRecommendationPanel.jsx";
import {
  absentRecommendationDetail,
  degradedRecommendationDetail,
  insufficientDataRecommendationDetail,
  notApplicableRecommendationDetail,
  recommendCaseCreationDetail,
  recommendMonitorDetail,
  recommendNoActionDetail,
  recommendReviewDetail,
  recommendStepUpReviewDetail,
  unavailableRecommendationDetail
} from "../transactions/transactionRiskIntelligenceFixtures.js";

describe("AnalystRecommendationPanel", () => {
  it.each([
    [recommendReviewDetail(), "RECOMMEND_REVIEW", "Suggested manual analyst review."],
    [recommendCaseCreationDetail(), "RECOMMEND_CASE_CREATION", "This does not create a case."],
    [recommendStepUpReviewDetail(), "RECOMMEND_STEP_UP_REVIEW", "This does not trigger step-up automatically or start workflow."],
    [recommendMonitorDetail(), "RECOMMEND_MONITOR", "Suggested monitoring or lower-priority review."],
    [recommendNoActionDetail(), "RECOMMEND_NO_ACTION", "not transaction approval and not payment authorization"]
  ])("renders available advisory copy for %s", (detail, recommendation, copy) => {
    render(<AnalystRecommendationPanel recommendation={detail.analystRecommendation} />);

    const panel = screen.getByRole("region", { name: "Analyst Recommendation" });
    expect(within(panel).getByText(recommendation)).toBeInTheDocument();
    expect(within(panel).getByText(copy, { exact: false })).toBeInTheDocument();
    expect(within(panel).getByText("This recommendation is an analyst aid only.", { exact: false })).toBeInTheDocument();
  });

  it.each([
    [absentRecommendationDetail(), "ABSENT", "This is not RECOMMEND_NO_ACTION."],
    [notApplicableRecommendationDetail(), "NOT_APPLICABLE", "did not produce a recommendation"],
    [insufficientDataRecommendationDetail(), "INSUFFICIENT_DATA", "not enough bounded diagnostic evidence"],
    [unavailableRecommendationDetail(), "UNAVAILABLE", "unavailable for this transaction"],
    [degradedRecommendationDetail(), "DEGRADED", "diagnostic limitations"]
  ])("renders status copy for %s", (detail, status, copy) => {
    render(<AnalystRecommendationPanel recommendation={detail.analystRecommendation} />);

    const panel = screen.getByRole("region", { name: "Analyst Recommendation" });
    expect(within(panel).getAllByText(status).length).toBeGreaterThan(0);
    expect(within(panel).getByText(copy, { exact: false })).toBeInTheDocument();
  });

  it("renders reason codes warnings and non-decisioning boundary without controls", () => {
    const detail = degradedRecommendationDetail();
    const { container } = render(<AnalystRecommendationPanel recommendation={detail.analystRecommendation} />);

    expect(screen.getByText("RULES_HIGH_RISK")).toBeInTheDocument();
    expect(screen.getByText("analyst-recommendation-v1")).toBeInTheDocument();
    expect(screen.getByText("Generated at")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Analyst Recommendation Warnings" })).toHaveTextContent("ENGINE_INTELLIGENCE_DEGRADED");
    expect(screen.getByRole("region", { name: "Analyst Recommendation Boundary" })).toHaveTextContent("Not payment authorization");
    expect(screen.getAllByText("Confirmed")).toHaveLength(6);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(container.querySelector("form")).toBeNull();
  });

  it("renders null generatedAt as not available without epoch fallback", () => {
    const detail = absentRecommendationDetail();
    const { container } = render(<AnalystRecommendationPanel recommendation={detail.analystRecommendation} />);

    const panel = screen.getByRole("region", { name: "Analyst Recommendation" });
    expect(within(panel).getByText("Generated at")).toBeInTheDocument();
    expect(within(panel).getByText("Not available")).toBeInTheDocument();
    expect(container).not.toHaveTextContent("1970");
  });
});
