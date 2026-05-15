import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReportsWorkspaceContainer } from "./ReportsWorkspaceContainer.jsx";

describe("ReportsWorkspaceContainer", () => {
  it("renders only reports analytics presentation", () => {
    const { container } = render(
      <ReportsWorkspaceContainer
        analyticsState={{
          analytics: {
            status: "AVAILABLE",
            window: { days: 7 },
            totals: { advisories: 0, reviewed: 0, open: 0 },
            decision_distribution: {},
            lifecycle_distribution: {},
            review_timeliness: { status: "LOW_CONFIDENCE" }
          },
          windowDays: 7,
          isLoading: false,
          error: null,
          setWindowDays: vi.fn(),
          refresh: vi.fn()
        }}
      />
    );

    expect(screen.getByRole("heading", { name: "Review visibility" })).toBeInTheDocument();
    expect(screen.getByText("Reports show the last loaded analytics snapshot and refresh when this workspace is opened or retried.")).toBeInTheDocument();
    expect(container.querySelector("[data-workspace-heading]")).toHaveTextContent("Review visibility");
    expect(screen.queryByRole("heading", { name: "Governance review queue" })).not.toBeInTheDocument();
  });
});
