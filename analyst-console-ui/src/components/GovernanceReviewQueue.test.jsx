import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { GovernanceReviewQueue } from "./GovernanceReviewQueue.jsx";

describe("GovernanceReviewQueue", () => {
  it("renders advisory list with safety copy and no decision workflow buttons", () => {
    renderQueue({
      advisoryQueue: {
        status: "AVAILABLE",
        count: 1,
        retention_limit: 200,
        advisory_events: [advisoryEvent()]
      }
    });

    expect(screen.getByRole("heading", { name: "Operator review queue" })).toBeInTheDocument();
    expect(screen.getByText(/Advisory signal, not fraud alert/i)).toBeInTheDocument();
    expect(screen.getByText(/do not change scoring/i)).toBeInTheDocument();
    expect(screen.getAllByText("HIGH").length).toBeGreaterThan(0);
    expect(screen.getByText("DRIFT")).toBeInTheDocument();
    expect(screen.getByText("SUFFICIENT_DATA")).toBeInTheDocument();
    expect(screen.getByText("python-logistic-fraud-model")).toBeInTheDocument();
    expect(screen.getByText("ESCALATE_MODEL_REVIEW")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /approve|reject|retrain|rollback/i })).not.toBeInTheDocument();
  });

  it("renders empty state", () => {
    renderQueue({
      advisoryQueue: {
        status: "AVAILABLE",
        count: 0,
        retention_limit: 200,
        advisory_events: []
      }
    });

    expect(screen.getByRole("heading", { name: "No advisory signals match this view" })).toBeInTheDocument();
  });

  it("renders partial and unavailable backend states", () => {
    const { rerender } = renderQueue({
      advisoryQueue: {
        status: "PARTIAL",
        count: 0,
        retention_limit: 200,
        advisory_events: []
      }
    });

    expect(screen.getByText(/Advisory history is partial/i)).toBeInTheDocument();

    rerender(queueElement({
      advisoryQueue: {
        status: "UNAVAILABLE",
        count: 0,
        retention_limit: 200,
        advisory_events: []
      }
    }));

    expect(screen.getByText(/Advisory history is unavailable/i)).toBeInTheDocument();
  });

  it("filters call exact API query state", () => {
    const onFiltersChange = vi.fn();
    renderQueue({ onFiltersChange });

    fireEvent.change(screen.getByLabelText("Severity"), { target: { value: "HIGH" } });
    expect(onFiltersChange).toHaveBeenCalledWith({
      severity: "HIGH",
      modelVersion: "",
      limit: 25
    });

    fireEvent.change(screen.getByLabelText("Model version exact match"), {
      target: { value: "2026-04-21.trained.v1" }
    });
    expect(onFiltersChange).toHaveBeenCalledWith({
      severity: "ALL",
      modelVersion: "2026-04-21.trained.v1",
      limit: 25
    });

    fireEvent.change(screen.getByLabelText("Limit"), { target: { value: "50" } });
    expect(onFiltersChange).toHaveBeenCalledWith({
      severity: "ALL",
      modelVersion: "",
      limit: 50
    });
  });

  it("renders deterministic API error state", () => {
    renderQueue({
      error: { status: 503, message: "Governance service unavailable." }
    });

    expect(screen.getByRole("heading", { name: "Unable to load data" })).toBeInTheDocument();
    expect(screen.getByText("Governance service unavailable.")).toBeInTheDocument();
  });
});

function renderQueue(overrides = {}) {
  return render(queueElement(overrides));
}

function queueElement(overrides = {}) {
  return (
    <GovernanceReviewQueue
      advisoryQueue={{
        status: "AVAILABLE",
        count: 0,
        retention_limit: 200,
        advisory_events: []
      }}
      filters={{ severity: "ALL", modelVersion: "", limit: 25 }}
      isLoading={false}
      error={null}
      onFiltersChange={vi.fn()}
      onRetry={vi.fn()}
      {...overrides}
    />
  );
}

function advisoryEvent() {
  return {
    event_id: "advisory-1",
    event_type: "GOVERNANCE_DRIFT_ADVISORY",
    severity: "HIGH",
    drift_status: "DRIFT",
    confidence: "HIGH",
    advisory_confidence_context: "SUFFICIENT_DATA",
    model_name: "python-logistic-fraud-model",
    model_version: "2026-04-21.trained.v1",
    lifecycle_context: {
      current_model_version: "2026-04-21.trained.v1",
      model_loaded_at: "2026-04-26T00:00:00+00:00",
      model_changed_recently: false,
      recent_lifecycle_event_count: 2
    },
    recommended_actions: ["ESCALATE_MODEL_REVIEW"],
    explanation: "score p95 increased compared to reference profile",
    created_at: "2026-04-26T00:02:00+00:00"
  };
}
