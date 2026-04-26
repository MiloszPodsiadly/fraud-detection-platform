import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { GovernanceAnalyticsPanel } from "./GovernanceAnalyticsPanel.jsx";

describe("GovernanceAnalyticsPanel", () => {
  it("renders read-only audit analytics summary", () => {
    renderPanel();

    expect(screen.getByRole("heading", { name: "Review visibility" })).toBeInTheDocument();
    expect(screen.getByText(/Derived read-only view/i)).toBeInTheDocument();
    expect(screen.getByText(/do not define SLAs, trigger actions, or change scoring/i)).toBeInTheDocument();
    expect(screen.getByText("Total advisories")).toBeInTheDocument();
    expect(screen.getByText("Reviewed vs open")).toBeInTheDocument();
    expect(screen.getByText("Status AVAILABLE")).toHaveAttribute(
      "title",
      "All data sources were available and processed successfully."
    );
    expect(screen.getByText("25%")).toBeInTheDocument();
    expect(screen.getByText("50%")).toBeInTheDocument();
    expect(screen.getByText("NEEDS_FOLLOW_UP")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /review|alert|retrain|rollback/i })).not.toBeInTheDocument();
  });

  it("changes bounded analytics window only", () => {
    const onWindowDaysChange = vi.fn();
    renderPanel({ onWindowDaysChange });

    fireEvent.change(screen.getByLabelText("Window"), { target: { value: "30" } });

    expect(onWindowDaysChange).toHaveBeenCalledWith(30);
    expect(screen.queryByRole("option", { name: "60 days" })).not.toBeInTheDocument();
  });

  it("renders partial and empty states", () => {
    const { rerender } = renderPanel({
      analytics: {
        ...analyticsFixture(),
        status: "PARTIAL"
      }
    });

    expect(screen.getByText("Partial analytics available. Some audit or advisory data may be missing.")).toHaveAttribute(
      "title",
      "Some data sources were unavailable or limits were exceeded. Results may be incomplete."
    );

    rerender(panelElement({
      analytics: {
        ...analyticsFixture(),
        totals: { advisories: 0, reviewed: 0, open: 0 }
      }
    }));

    expect(screen.getByRole("heading", { name: "No advisory analytics in this window" })).toBeInTheDocument();
  });

  it("explains low-confidence timeliness without implying urgency", () => {
    renderPanel({
      analytics: {
        ...analyticsFixture(),
        review_timeliness: {
          status: "LOW_CONFIDENCE",
          time_to_first_review_p50_minutes: 0,
          time_to_first_review_p95_minutes: 0
        }
      }
    });

    expect(screen.getByText("Timeliness LOW_CONFIDENCE")).toHaveAttribute(
      "title",
      "Insufficient valid samples to compute reliable percentiles."
    );
  });
});

function renderPanel(overrides = {}) {
  return render(panelElement(overrides));
}

function panelElement(overrides = {}) {
  return (
    <GovernanceAnalyticsPanel
      analytics={analyticsFixture()}
      windowDays={7}
      isLoading={false}
      error={null}
      onWindowDaysChange={vi.fn()}
      onRetry={vi.fn()}
      {...overrides}
    />
  );
}

function analyticsFixture() {
  return {
    status: "AVAILABLE",
    window: {
      from: "2026-04-19T00:00:00Z",
      to: "2026-04-26T00:00:00Z",
      days: 7
    },
    totals: {
      advisories: 4,
      reviewed: 4,
      open: 0
    },
    decision_distribution: {
      ACKNOWLEDGED: 1,
      NEEDS_FOLLOW_UP: 1,
      DISMISSED_AS_NOISE: 2
    },
    lifecycle_distribution: {
      OPEN: 0,
      ACKNOWLEDGED: 1,
      NEEDS_FOLLOW_UP: 1,
      DISMISSED_AS_NOISE: 2
    },
    review_timeliness: {
      status: "AVAILABLE",
      time_to_first_review_p50_minutes: 10,
      time_to_first_review_p95_minutes: 20
    }
  };
}
