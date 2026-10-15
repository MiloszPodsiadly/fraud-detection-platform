import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ExactModelShadowPerformancePanel } from "./ExactModelShadowPerformancePanel.jsx";

describe("ExactModelShadowPerformancePanel", () => {
  it("renders source-byte-verified provenance without moving verification to runtime", () => {
    render(<ExactModelShadowPerformancePanel state={state({ summary: fixture() })} canRead />);

    expect(screen.getByText("Model Shadow Performance")).toBeInTheDocument();
    expect(screen.getAllByText("50.00%").length).toBeGreaterThan(0);
    expect(screen.getByText("Analyst-labeled fraud by disagreement category")).toBeInTheDocument();
    expect(screen.getByText("Evaluation provenance")).toBeInTheDocument();
    expect(screen.getByText(/Source dataset bytes were verified at the upstream evaluation validation and publication boundary/)).toBeInTheDocument();
    expect(screen.getByText("VERIFIED_AGAINST_SOURCE_BYTES")).toBeInTheDocument();
    expect(screen.getByText(/The Java runtime verifies the local published artifact and manifest/)).toHaveTextContent(
      "It does not re-read the upstream evaluation artifact."
    );
    expect(screen.getByText("Immutable storage guarantee")).toBeInTheDocument();
    expect(screen.getByText("Confusion matrix")).toBeInTheDocument();
    expect(screen.getByText("Ranking metrics")).toBeInTheDocument();
    expect(screen.getByText("Governance boundary")).toBeInTheDocument();
    expect(screen.getByText("UNAVAILABLE: EXACT_ARTIFACT_LATENCY_EVIDENCE_UNAVAILABLE")).toBeInTheDocument();
    expect(screen.getByText(/Diagnostic evidence only; production authority: NONE/)).toBeInTheDocument();
  });

  it("renders claim-only provenance as an explicit source-verification limitation", () => {
    render(<ExactModelShadowPerformancePanel state={state({ summary: fixture("python-generated-claim-only-v1") })} canRead />);

    expect(screen.getByText("CLAIM_ONLY")).toBeInTheDocument();
    expect(screen.getByText(/contains a source dataset identity claim/)).toHaveTextContent(
      "Source dataset bytes were not independently checked during exact-model Shadow Performance generation."
    );
    expect(screen.queryByText(/Source dataset bytes were verified/)).not.toBeInTheDocument();
    expect(screen.getByText(/External attestation remains NOT_PROVIDED/)).toHaveTextContent(
      "immutable storage remains NOT_CLAIMED"
    );
  });

  it.each(["python-generated-v1", "python-generated-claim-only-v1"])(
    "preserves exact identity, window and diagnostic-only boundaries for %s",
    (directory) => {
      render(<ExactModelShadowPerformancePanel state={state({ summary: fixture(directory) })} canRead />);

      expect(screen.getByText("python-logistic-fraud-model")).toBeInTheDocument();
      expect(screen.getByText("2026-10-15.shadow")).toBeInTheDocument();
      expect(screen.getByText("feature-contract-v2")).toBeInTheDocument();
      expect(screen.getByText("a".repeat(64))).toBeInTheDocument();
      expect(screen.getByText("2026-06-01T00:00:00Z")).toBeInTheDocument();
      expect(screen.getByText("2026-06-09T23:59:59Z")).toBeInTheDocument();
      expect(screen.getByText(/Diagnostic evidence only; production authority: NONE/)).toBeInTheDocument();
      expect(screen.queryByText(/\bWORM\b/i)).not.toBeInTheDocument();
    }
  );

  it("submits the complete selector prefilled from Model Card evidence", () => {
    const load = vi.fn();
    const summary = fixture();
    render(<ExactModelShadowPerformancePanel
      state={state({ load })}
      canRead
      modelCard={{
        subject: summary.evaluationSummary.evaluationSubject,
        evaluationSummary: summary.evaluationSummary
      }}
    />);

    fireEvent.click(screen.getByRole("button", { name: "Load exact artifact" }));

    expect(load).toHaveBeenCalledWith({
      modelName: "python-logistic-fraud-model",
      modelVersion: "2026-10-15.shadow",
      featureContractVersion: "feature-contract-v2",
      modelArtifactSha256: "a".repeat(64),
      fromInclusive: "2026-06-01T00:00:00Z",
      toInclusive: "2026-06-09T23:59:59Z"
    });
  });

  it("does not expose the selector without authority", () => {
    render(<ExactModelShadowPerformancePanel state={state()} canRead={false} />);
    expect(screen.getByText("Required permission: model-shadow-performance:read.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load exact artifact" })).not.toBeInTheDocument();
  });

  it("invalidates stale evidence and refreshes an untouched Model Card selector", () => {
    const clear = vi.fn();
    const summary = fixture();
    const view = render(<ExactModelShadowPerformancePanel
      state={state({ clear })}
      canRead
      modelCard={modelCard(summary)}
    />);
    const changed = structuredClone(summary);
    changed.evaluationSummary.evaluationSubject.modelVersion = "2026-10-16.shadow";

    view.rerender(<ExactModelShadowPerformancePanel
      state={state({ clear })}
      canRead
      modelCard={modelCard(changed)}
    />);

    expect(screen.getByLabelText("Model version")).toHaveValue("2026-10-16.shadow");
    expect(clear).toHaveBeenCalledTimes(2);
  });

  it("preserves an explicit selector edit when Model Card identity changes", () => {
    const clear = vi.fn();
    const summary = fixture();
    const view = render(<ExactModelShadowPerformancePanel
      state={state({ clear })}
      canRead
      modelCard={modelCard(summary)}
    />);
    fireEvent.change(screen.getByLabelText("Model version"), { target: { value: "manual-version" } });
    const changed = structuredClone(summary);
    changed.evaluationSummary.evaluationSubject.modelVersion = "2026-10-16.shadow";

    view.rerender(<ExactModelShadowPerformancePanel
      state={state({ clear })}
      canRead
      modelCard={modelCard(changed)}
    />);

    expect(screen.getByLabelText("Model version")).toHaveValue("manual-version");
    expect(clear).toHaveBeenCalledTimes(2);
  });
});

function state(overrides = {}) {
  return {
    summary: null, selector: null, isLoading: false, error: null,
    load: vi.fn(), refresh: vi.fn(), ...overrides
  };
}

function fixture(directory = "python-generated-v1") {
  return JSON.parse(readFileSync(resolve(
    "..", "alert-service", "src", "test", "resources", "fixtures",
    "exact-model-shadow-performance", directory, "exact_model_shadow_performance.json"
  ), "utf8"));
}

function modelCard(summary) {
  return {
    subject: summary.evaluationSummary.evaluationSubject,
    evaluationSummary: summary.evaluationSummary
  };
}
