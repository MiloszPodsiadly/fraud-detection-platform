import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ModelPromotionPanel } from "./ModelPromotionPanel.jsx";

function workflow(overrides = {}) {
  return {
    clear: vi.fn(), load: vi.fn(), register: vi.fn(), propose: vi.fn(), retire: vi.fn(), review: vi.fn(),
    retryLastMutation: vi.fn(), isLoading: false, isMutating: false,
    state: {
      artifactIdentity: { modelName: "fraud", modelVersion: "1", featureContractVersion: "v1", modelArtifactSha256: "a".repeat(64) },
      governanceScope: { institutionalScope: "CURRENT_INSTITUTION", environment: "TEST" },
      intendedUse: "FRAUD_RISK_SCORING", lifecycleState: "CHALLENGER", currentRevision: 1,
      structurallyAllowedTargetStates: ["SHADOW_APPROVED", "REJECTED"],
      deploymentStatus: "NOT_AUTHORIZED_BY_MODEL_PROMOTION_GOVERNANCE", decisionHistory: [], proposals: []
    },
    assessment: {
      assessmentCreatedAt: "2026-06-11T12:00:00Z", assessmentStatus: "REVIEWABLE_BLOCKED",
      appliedPolicy: { policyId: "test-policy", canonicalSha256: "b".repeat(64) },
      sourceEvidence: { evidenceBundleSha256: "c".repeat(64), modelCardManifestSha256: "d".repeat(64), shadowPerformanceManifestSha256: "e".repeat(64) },
      humanReviewable: true, formalRecommendationEligible: false, governanceApproved: false,
      deploymentAuthorized: false, deploymentStatus: "NOT_AUTHORIZED_BY_MODEL_PROMOTION_GOVERNANCE",
      criteria: [], blockers: ["MATCHED_COMPARISON_UNAVAILABLE"], limitations: []
    },
    ...overrides
  };
}

describe("ModelPromotionPanel governance semantics", () => {
  it("shows a structural target without claiming that blocked evidence authorizes it", () => {
    const current = workflow();
    render(<ModelPromotionPanel workflow={current} canRead canPropose />);

    expect(screen.getByText("Structurally supported targets (not eligibility)")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Formal recommendation assessment blocked" })).toBeInTheDocument();
    expect(screen.getAllByText("MATCHED_COMPARISON_UNAVAILABLE")).toHaveLength(2);
    expect(screen.getByText(/CHAMPION never authorizes runtime deployment/)).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Structurally supported target (not approved)" })).toHaveValue("SHADOW_APPROVED");
    expect(current.propose).not.toHaveBeenCalled();

    fireEvent.change(screen.getByRole("textbox", { name: "Operator rationale" }),
      { target: { value: "Request independent review" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit independent-review proposal" }));
    expect(current.propose).toHaveBeenCalledWith("SHADOW_APPROVED", "Request independent review");
    expect(current.review).not.toHaveBeenCalled();
  });

  it("requires an explicit independent-review action for a pending proposal", () => {
    const current = workflow({
      state: {
        ...workflow().state,
        proposals: [{ proposalId: "proposal-1", requestedTargetState: "SHADOW_APPROVED", reviewStatus: "PENDING", submittedAt: "2026-06-11T12:00:00Z" }]
      }
    });
    render(<ModelPromotionPanel workflow={current} canRead canApprove />);

    expect(screen.getByText("Pending proposal: SHADOW_APPROVED")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Independently approve" })).toBeDisabled();
    expect(current.review).not.toHaveBeenCalled();
    fireEvent.change(screen.getByRole("textbox", { name: "Operator rationale" }),
      { target: { value: "Independent assessment" } });
    fireEvent.click(screen.getByRole("button", { name: "Independently approve" }));
    expect(current.review).toHaveBeenCalledWith("proposal-1", "APPROVED", "Independent assessment");
  });
});
