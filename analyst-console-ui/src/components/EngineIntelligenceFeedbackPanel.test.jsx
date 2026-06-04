import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { EngineIntelligenceFeedbackPanel } from "./EngineIntelligenceFeedbackPanel.jsx";

describe("EngineIntelligenceFeedbackPanel", () => {
  it("rendersStructuredControlsWithoutDecisioningOrFreeText", () => {
    renderFeedbackPanel();

    expect(screen.getByRole("heading", { name: "Was this engine intelligence useful?" })).toBeInTheDocument();
    expect(screen.getByLabelText("Helpful")).toBeInTheDocument();
    expect(screen.getByLabelText("Somewhat helpful")).toBeInTheDocument();
    expect(screen.getByLabelText("Not helpful")).toBeInTheDocument();
    expect(screen.getByLabelText("Not sure")).toBeInTheDocument();
    expect(screen.getByLabelText("Signals looked correct")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/approve|decline|block|final decision|recommended action|retrain|update rule/i);
  });

  it.each([
    ["submitsHelpfulFeedback", "Helpful", "HELPFUL", "ENGINE_INTELLIGENCE_USEFULNESS"],
    ["submitsNotHelpfulFeedback", "Not helpful", "NOT_HELPFUL", "ENGINE_INTELLIGENCE_USEFULNESS"],
    ["submitsNotSureFeedback", "Not sure", "NOT_SURE", "ENGINE_INTELLIGENCE_USEFULNESS"]
  ])("%s", async (_name, label, usefulness, feedbackType) => {
    const submit = vi.fn().mockResolvedValue({ state: "saved", operationStatus: "CREATED" });
    renderFeedbackPanel({ submitEngineIntelligenceFeedback: submit });

    fireEvent.click(screen.getByLabelText(label));
    fireEvent.click(screen.getByLabelText("Signals looked correct"));
    fireEvent.click(screen.getByRole("button", { name: "Submit feedback" }));

    await waitFor(() => expect(submit).toHaveBeenCalled());
    expect(submit).toHaveBeenCalledWith("txn-1", expect.objectContaining({
      feedbackType,
      usefulness,
      accuracyAssessment: "SIGNALS_LOOK_CORRECT",
      engineIntelligenceAvailable: true,
      selectedReasonCodes: []
    }), expect.objectContaining({ idempotencyKey: expect.stringMatching(/^engine-intelligence-feedback-/) }));
    expect(submit.mock.calls[0][1]).not.toHaveProperty("fraudCaseId");
    expect(await screen.findByText("Feedback saved.")).toBeInTheDocument();
  });

  it("notHelpfulDoesNotAutomaticallyMeanDisagreement", async () => {
    const submit = vi.fn().mockResolvedValue({ state: "saved", operationStatus: "CREATED" });
    renderFeedbackPanel({ submitEngineIntelligenceFeedback: submit });

    fireEvent.click(screen.getByLabelText("Not helpful"));
    fireEvent.click(screen.getByLabelText("Signals looked incorrect"));
    fireEvent.click(screen.getByRole("button", { name: "Submit feedback" }));

    await waitFor(() => expect(submit).toHaveBeenCalled());
    expect(submit.mock.calls[0][1]).toMatchObject({
      feedbackType: "ENGINE_INTELLIGENCE_USEFULNESS",
      usefulness: "NOT_HELPFUL",
      accuracyAssessment: "SIGNALS_LOOK_INCORRECT"
    });
  });

  it("operationalIssueMapsToOperationalStatusReview", async () => {
    const submit = vi.fn().mockResolvedValue({ state: "saved", operationStatus: "CREATED" });
    renderFeedbackPanel({ submitEngineIntelligenceFeedback: submit });

    fireEvent.click(screen.getByLabelText("Helpful"));
    fireEvent.click(screen.getByLabelText("Operational issue affected review"));
    fireEvent.click(screen.getByRole("button", { name: "Submit feedback" }));

    await waitFor(() => expect(submit).toHaveBeenCalled());
    expect(submit.mock.calls[0][1]).toMatchObject({
      feedbackType: "OPERATIONAL_STATUS_REVIEW",
      accuracyAssessment: "OPERATIONAL_ISSUE_AFFECTED_REVIEW"
    });
  });

  it("notProjectedMapsToMissingIntelligenceReview", async () => {
    const submit = vi.fn().mockResolvedValue({ state: "saved", operationStatus: "CREATED" });
    renderFeedbackPanel({ submitEngineIntelligenceFeedback: submit, engineIntelligenceAvailable: false });

    fireEvent.click(screen.getByLabelText("Not sure"));
    fireEvent.click(screen.getByLabelText("Operational issue affected review"));
    fireEvent.click(screen.getByRole("button", { name: "Submit feedback" }));

    await waitFor(() => expect(submit).toHaveBeenCalled());
    expect(submit.mock.calls[0][1]).toMatchObject({
      feedbackType: "MISSING_INTELLIGENCE_REVIEW",
      engineIntelligenceAvailable: false
    });
  });

  it("uiV1DoesNotSubmitSelectedReasonCodesWithoutExplicitReasonCodeSelector", async () => {
    const submit = vi.fn().mockResolvedValue({ state: "saved", operationStatus: "CREATED" });
    renderFeedbackPanel({ submitEngineIntelligenceFeedback: submit });

    fireEvent.click(screen.getByLabelText("Helpful"));
    fireEvent.click(screen.getByLabelText("Signals looked correct"));
    fireEvent.click(screen.getByRole("button", { name: "Submit feedback" }));

    await waitFor(() => expect(submit).toHaveBeenCalled());
    expect(submit.mock.calls[0][1].selectedReasonCodes).toEqual([]);
    expect(submit.mock.calls[0][1].selectedReasonCodes).not.toContain("SIGNALS_LOOK_CORRECT");
  });

  it("disablesSubmitWhilePending", async () => {
    const submit = vi.fn(() => new Promise(() => {}));
    renderFeedbackPanel({ submitEngineIntelligenceFeedback: submit });

    fireEvent.click(screen.getByLabelText("Helpful"));
    fireEvent.click(screen.getByLabelText("Signals looked correct"));
    const button = screen.getByRole("button", { name: "Submit feedback" });
    fireEvent.click(button);

    expect(await screen.findByRole("button", { name: "Submitting..." })).toBeDisabled();
  });

  it("showsSafeErrorOnServerFailure", async () => {
    const submit = vi.fn().mockResolvedValue({ state: "unavailable", message: "raw payload token stacktrace endpoint" });
    renderFeedbackPanel({ submitEngineIntelligenceFeedback: submit });

    fireEvent.click(screen.getByLabelText("Helpful"));
    fireEvent.click(screen.getByLabelText("Signals looked correct"));
    fireEvent.click(screen.getByRole("button", { name: "Submit feedback" }));

    const alert = await screen.findByText("Feedback could not be saved. Please try again.");
    expect(alert).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/raw payload|token|stacktrace|endpoint/i);
  });

  it("requiresFeedbackWriteAuthorityHint", () => {
    renderFeedbackPanel({ canSubmitFeedback: false });

    expect(screen.getByText("Feedback requires engine intelligence feedback write authority.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Submit feedback" })).toBeDisabled();
  });
});

function renderFeedbackPanel(overrides = {}) {
  return render(
    <EngineIntelligenceFeedbackPanel
      transactionId="txn-1"
      engineIntelligenceAvailable
      canSubmitFeedback
      submitEngineIntelligenceFeedback={vi.fn().mockResolvedValue({ state: "saved" })}
      {...overrides}
    />
  );
}
