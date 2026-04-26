import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { normalizeSession } from "../auth/session.js";
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
    expect(screen.getByText(/Advisory context is read-only/i)).toBeInTheDocument();
    expect(screen.getByText(/writes audit history only/i)).toBeInTheDocument();
    expect(screen.getByText(/do not trigger system actions/i)).toBeInTheDocument();
    expect(screen.getByText(/do not affect scoring, model behavior, or system decisions/i)).toBeInTheDocument();
    expect(screen.getByText(/Lifecycle status reflects the latest recorded operator review/i)).toBeInTheDocument();
    expect(screen.getByText(/Results are limited to recent advisory events/i)).toBeInTheDocument();
    expect(screen.getAllByText("HIGH").length).toBeGreaterThan(0);
    expect(screen.getAllByText("OPEN").length).toBeGreaterThan(1);
    expect(screen.getByText("DRIFT")).toBeInTheDocument();
    expect(screen.getByText("SUFFICIENT_DATA")).toBeInTheDocument();
    expect(screen.getByText("python-logistic-fraud-model")).toBeInTheDocument();
    expect(screen.getByText("ESCALATE_MODEL_REVIEW")).toBeInTheDocument();
    expect(screen.getByText("Explanation (heuristic):")).toBeInTheDocument();
    expect(screen.getByText(/This records human review only/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Mark reviewed" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /approve|reject|retrain|rollback/i })).not.toBeInTheDocument();
  });

  it("records bounded human review without actor fields", async () => {
    const onRecordAudit = vi.fn().mockResolvedValue(undefined);
    renderQueue({
      onRecordAudit,
      advisoryQueue: {
        status: "AVAILABLE",
        count: 1,
        retention_limit: 200,
        advisory_events: [advisoryEvent()]
      },
      auditHistories: {
        "advisory-1": {
          advisory_event_id: "advisory-1",
          status: "AVAILABLE",
          audit_events: []
        }
      }
    });

    fireEvent.change(screen.getByLabelText("Decision"), { target: { value: "NEEDS_FOLLOW_UP" } });
    fireEvent.change(screen.getByLabelText("Note"), { target: { value: "Reviewed by operator" } });
    fireEvent.click(screen.getByRole("button", { name: "Mark reviewed" }));

    await waitFor(() => expect(onRecordAudit).toHaveBeenCalledWith("advisory-1", {
      decision: "NEEDS_FOLLOW_UP",
      note: "Reviewed by operator"
    }));
    expect(JSON.stringify(onRecordAudit.mock.calls[0][1])).not.toContain("actor");
  });

  it("shows audit history per advisory event", () => {
    renderQueue({
      advisoryQueue: {
        status: "AVAILABLE",
        count: 1,
        retention_limit: 200,
        advisory_events: [advisoryEvent()]
      },
      auditHistories: {
        "advisory-1": {
          advisory_event_id: "advisory-1",
          status: "AVAILABLE",
          audit_events: [{
            audit_id: "audit-1",
            decision: "ACKNOWLEDGED",
            actor_display_name: "analyst-1",
            created_at: "2026-04-26T00:03:00Z",
            note: "Reviewed by operator"
          }]
        }
      }
    });

    expect(screen.getByText("Audit history")).toBeInTheDocument();
    expect(screen.getAllByText("ACKNOWLEDGED").length).toBeGreaterThan(1);
    expect(screen.getByText("Reviewed by operator")).toBeInTheDocument();
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

    expect(screen.getByRole("heading", { name: "No advisory signals available for the selected filters." })).toBeInTheDocument();
    expect(screen.getByText("This does not guarantee absence of model drift.")).toBeInTheDocument();
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

    expect(screen.getByText("Partial data available. Some advisory events may be missing.")).toBeInTheDocument();

    rerender(queueElement({
      advisoryQueue: {
        status: "UNAVAILABLE",
        count: 0,
        retention_limit: 200,
        advisory_events: []
      }
    }));

    expect(screen.getByText("Advisory data is currently unavailable.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "No advisory signals available for the selected filters." })).not.toBeInTheDocument();
  });

  it("filters call exact API query state", () => {
    const onFiltersChange = vi.fn();
    renderQueue({ onFiltersChange });

    fireEvent.change(screen.getByLabelText("Severity"), { target: { value: "HIGH" } });
    expect(onFiltersChange).toHaveBeenCalledWith({
      severity: "HIGH",
      modelVersion: "",
      lifecycleStatus: "ALL",
      limit: 25
    });

    fireEvent.change(screen.getByLabelText("Model version exact match"), {
      target: { value: "2026-04-21.trained.v1" }
    });
    expect(onFiltersChange).toHaveBeenCalledWith({
      severity: "ALL",
      modelVersion: "2026-04-21.trained.v1",
      lifecycleStatus: "ALL",
      limit: 25
    });

    fireEvent.change(screen.getByLabelText("Lifecycle status"), { target: { value: "ACKNOWLEDGED" } });
    expect(onFiltersChange).toHaveBeenCalledWith({
      severity: "ALL",
      modelVersion: "",
      lifecycleStatus: "ACKNOWLEDGED",
      limit: 25
    });

    fireEvent.change(screen.getByLabelText("Limit"), { target: { value: "50" } });
    expect(onFiltersChange).toHaveBeenCalledWith({
      severity: "ALL",
      modelVersion: "",
      lifecycleStatus: "ALL",
      limit: 50
    });
    expect(screen.queryByRole("option", { name: "10" })).not.toBeInTheDocument();
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
      filters={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
      isLoading={false}
      error={null}
      onFiltersChange={vi.fn()}
      onRetry={vi.fn()}
      onRecordAudit={vi.fn()}
      session={normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] })}
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
    lifecycle_status: "OPEN",
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
