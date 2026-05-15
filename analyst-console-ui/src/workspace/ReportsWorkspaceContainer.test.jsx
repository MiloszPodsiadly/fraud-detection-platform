import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReportsWorkspaceContainer } from "./ReportsWorkspaceContainer.jsx";

describe("ReportsWorkspaceContainer", () => {
  it("renders only reports analytics presentation", () => {
    const { container } = render(
      <ReportsWorkspaceContainer
        governanceAnalytics={{
          status: "AVAILABLE",
          window: { days: 7 },
          totals: { advisories: 0, reviewed: 0, open: 0 },
          decision_distribution: {},
          lifecycle_distribution: {},
          review_timeliness: { status: "LOW_CONFIDENCE" }
        }}
        analyticsWindowDays={7}
        isAnalyticsLoading={false}
        analyticsError={null}
        onAnalyticsWindowDaysChange={vi.fn()}
        onAnalyticsRetry={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Review visibility" })).toBeInTheDocument();
    expect(container.querySelector("[data-workspace-heading]")).toHaveTextContent("Review visibility");
    expect(screen.queryByRole("heading", { name: "Governance review queue" })).not.toBeInTheDocument();
  });
});
