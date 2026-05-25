import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { availableEvidenceSummary } from "../test-utils/evidenceSummaryFixtures.js";
import { availableTimeline } from "../test-utils/fraudCaseTimelineFixtures.js";
import { FraudCaseDetailsPage } from "./FraudCaseDetailsPage.jsx";

describe("FraudCaseDetailsPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("ignores stale fraud case response after caseId changes", async () => {
    const firstCase = deferred();
    const secondCase = deferred();
    const apiClient = {
      getFraudCase: vi.fn()
        .mockReturnValueOnce(firstCase.promise)
        .mockReturnValueOnce(secondCase.promise),
      updateFraudCase: vi.fn()
    };
    const { rerender } = render(page({ caseId: "case-old", apiClient }));
    await waitFor(() => expect(apiClient.getFraudCase).toHaveBeenCalledWith("case-old", expect.objectContaining({ signal: expect.any(AbortSignal) })));

    rerender(page({ caseId: "case-new", apiClient }));
    await waitFor(() => expect(apiClient.getFraudCase).toHaveBeenCalledWith("case-new", expect.objectContaining({ signal: expect.any(AbortSignal) })));
    secondCase.resolve(fraudCase("case-new", "New case reason"));
    await screen.findByText("New case reason");
    firstCase.resolve(fraudCase("case-old", "Old case reason"));

    await waitFor(() => expect(screen.queryByText("Old case reason")).not.toBeInTheDocument());
  });

  it("refetches with a new apiClient and ignores the old client response", async () => {
    const firstCase = deferred();
    const apiClientA = {
      getFraudCase: vi.fn().mockReturnValue(firstCase.promise),
      updateFraudCase: vi.fn()
    };
    const apiClientB = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Client B case")),
      updateFraudCase: vi.fn()
    };
    const { rerender } = render(page({ caseId: "case-1", apiClient: apiClientA }));
    await waitFor(() => expect(apiClientA.getFraudCase).toHaveBeenCalledTimes(1));

    rerender(page({ caseId: "case-1", apiClient: apiClientB }));
    await waitFor(() => expect(apiClientB.getFraudCase).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("Client B case")).toBeInTheDocument();
    firstCase.resolve(fraudCase("case-1", "Client A case"));

    await waitFor(() => expect(screen.queryByText("Client A case")).not.toBeInTheDocument());
  });

  it("aborts in-flight detail request on unmount", async () => {
    const pendingCase = deferred();
    const apiClient = {
      getFraudCase: vi.fn().mockReturnValue(pendingCase.promise),
      updateFraudCase: vi.fn()
    };
    const { unmount } = render(page({ caseId: "case-1", apiClient }));
    await waitFor(() => expect(apiClient.getFraudCase).toHaveBeenCalledTimes(1));
    const signal = apiClient.getFraudCase.mock.calls[0][1].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("does not update state or call onCaseUpdated after submit resolves post-unmount", async () => {
    const update = deferred();
    const onCaseUpdated = vi.fn();
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn().mockReturnValue(update.promise)
    };
    const { unmount } = render(page({ caseId: "case-1", apiClient, onCaseUpdated }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));
    await waitFor(() => expect(apiClient.updateFraudCase).toHaveBeenCalledTimes(1));
    unmount();
    update.resolve({ ...fraudCase("case-1", "Updated case"), status: "CLOSED", decisionReason: "Reviewed" });

    await waitFor(() => expect(onCaseUpdated).not.toHaveBeenCalled());
  });

  it("aborts in-flight fraud case update on unmount", async () => {
    const update = deferred();
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn().mockReturnValue(update.promise)
    };
    const { unmount } = render(page({ caseId: "case-1", apiClient }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));
    await waitFor(() => expect(apiClient.updateFraudCase).toHaveBeenCalledTimes(1));
    const signal = apiClient.updateFraudCase.mock.calls[0][2].signal;
    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("does not let old submit response overwrite state after caseId changes", async () => {
    const update = deferred();
    const apiClient = {
      getFraudCase: vi.fn()
        .mockResolvedValueOnce(fraudCase("case-old", "Old case"))
        .mockResolvedValueOnce(fraudCase("case-new", "New case")),
      updateFraudCase: vi.fn().mockReturnValue(update.promise)
    };
    const { rerender } = render(page({ caseId: "case-old", apiClient }));
    await screen.findByText("Old case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));
    await waitFor(() => expect(apiClient.updateFraudCase).toHaveBeenCalledTimes(1));
    const signal = apiClient.updateFraudCase.mock.calls[0][2].signal;
    rerender(page({ caseId: "case-new", apiClient }));
    expect(signal.aborted).toBe(true);
    await screen.findByText("New case");
    update.resolve({ ...fraudCase("case-old", "Updated old case"), status: "CLOSED", decisionReason: "Reviewed" });

    await waitFor(() => expect(screen.queryByText("Updated old case")).not.toBeInTheDocument());
    expect(screen.getByText("New case")).toBeInTheDocument();
  });

  it("aborts in-flight submit when apiClient changes", async () => {
    const update = deferred();
    const apiClientA = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Client A case")),
      updateFraudCase: vi.fn().mockReturnValue(update.promise)
    };
    const apiClientB = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Client B case")),
      updateFraudCase: vi.fn()
    };
    const { rerender } = render(page({ caseId: "case-1", apiClient: apiClientA }));
    await screen.findByText("Client A case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));
    await waitFor(() => expect(apiClientA.updateFraudCase).toHaveBeenCalledTimes(1));
    const signal = apiClientA.updateFraudCase.mock.calls[0][2].signal;
    rerender(page({ caseId: "case-1", apiClient: apiClientB }));

    expect(signal.aborted).toBe(true);
    expect(await screen.findByText("Client B case")).toBeInTheDocument();
  });

  it("passes submit signal with the idempotency key and ignores AbortError", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn().mockRejectedValue(new DOMException("aborted", "AbortError"))
    };
    render(page({ caseId: "case-1", apiClient }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));

    await waitFor(() => expect(apiClient.updateFraudCase).toHaveBeenCalledWith(
      "case-1",
      expect.objectContaining({ decisionReason: "Reviewed" }),
      expect.objectContaining({
        idempotencyKey: expect.stringMatching(/^fraud-case-update-/),
        signal: expect.any(AbortSignal)
      })
    ));
    expect(apiClient.updateFraudCase.mock.calls[0][2].idempotencyKey).not.toContain("case-1");
    await waitFor(() => expect(screen.getByRole("button", { name: "Save case decision" })).toBeEnabled());
    expect(screen.queryByText("aborted")).not.toBeInTheDocument();
  });

  it("shows controlled mutation error when current submit fails", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn().mockRejectedValue(new Error("write failed"))
    };
    render(page({ caseId: "case-1", apiClient }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));

    expect(await screen.findByText("write failed")).toBeInTheDocument();
  });

  it("keeps stale fraud case detail visible but disables case update", async () => {
    const apiClientA = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn()
    };
    const apiClientB = {
      getFraudCase: vi.fn().mockRejectedValue(new Error("refresh failed")),
      updateFraudCase: vi.fn()
    };
    const { rerender } = render(page({ caseId: "case-1", apiClient: apiClientA }));
    await screen.findByText("Open case");

    rerender(page({ caseId: "case-1", apiClient: apiClientB }));

    expect(await screen.findByText(/Refresh fraud case detail successfully before updating the case decision/)).toBeInTheDocument();
    expect(screen.getByText("Case update unavailable: refresh required")).toBeInTheDocument();
    expect(screen.getByText("Open case")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save case decision" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));
    expect(apiClientB.updateFraudCase).not.toHaveBeenCalled();
  });

  it("keeps mutation success separate from dashboard refresh failure", async () => {
    const onCaseUpdated = vi.fn().mockRejectedValue(new Error("dashboard refresh failed"));
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn().mockResolvedValue({ ...fraudCase("case-1", "Updated case"), status: "CLOSED", decisionReason: "Reviewed" })
    };
    render(page({ caseId: "case-1", apiClient, onCaseUpdated }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));

    expect(await screen.findByText("Case decision saved.")).toBeInTheDocument();
    expect(screen.getByText("Case decision saved. Latest dashboard state could not be refreshed.")).toBeInTheDocument();
    expect(screen.queryByText("dashboard refresh failed")).not.toBeInTheDocument();
    expect(apiClient.updateFraudCase).toHaveBeenCalledTimes(1);
  });

  it("shows a controlled error and does not update when secure request ID generation fails", async () => {
    vi.stubGlobal("crypto", undefined);
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn()
    };
    render(page({ caseId: "case-1", apiClient }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));

    expect(await screen.findByText("Secure request identifier could not be generated. Reload the page and try again.")).toBeInTheDocument();
    expect(apiClient.updateFraudCase).not.toHaveBeenCalled();
    expect(screen.queryByText("Case decision saved.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save case decision" })).toBeEnabled();
  });

  it("FraudCaseDetailIncludesEvidenceSummaryReadOnlySectionTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    expect(await screen.findByText("Evidence summary")).toBeInTheDocument();
    expect((await screen.findAllByText("Evidence status")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("AVAILABLE").length).toBeGreaterThan(0);
    expect(apiClient.getFraudCaseEvidenceSummary).toHaveBeenCalledWith("case-1", expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it("FraudCaseDetailEvidenceSummaryFailureDoesNotBreakCaseDetailTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockRejectedValue(new Error("backend raw failure")),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    expect(await screen.findByText("Evidence summary unavailable.")).toBeInTheDocument();
    expect(screen.queryByText("backend raw failure")).not.toBeInTheDocument();
  });

  it("FraudCaseDetailEvidenceSummaryDoesNotReceiveMutationHandlersTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence summary")).toBeInTheDocument();
    expect(apiClient.updateFraudCase).not.toHaveBeenCalled();
    expect(apiClient.getFraudCaseEvidenceSummary.mock.calls[0][1]).not.toHaveProperty("onCaseUpdated");
    expect(apiClient.getFraudCaseEvidenceSummary.mock.calls[0][1]).not.toHaveProperty("submitDecision");
  });

  it("FraudCaseDetailEvidenceSummaryIsNotMountedWithoutCaseIdTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn(),
      getFraudCaseEvidenceSummary: vi.fn(),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "", apiClient, canReadFraudCase: false }));

    await screen.findByText("This session does not include fraud case read authority.");
    expect(screen.queryByTestId("fraud-case-evidence-summary-section")).not.toBeInTheDocument();
    expect(apiClient.getFraudCaseEvidenceSummary).not.toHaveBeenCalled();
  });

  it("FraudCaseDetailEvidenceSummaryUsesOnlyEvidenceSummaryClientTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      updateFraudCase: vi.fn(),
      getAlert: vi.fn(),
      getAssistantSummary: vi.fn(),
      submitAnalystDecision: vi.fn(),
      listSuspiciousTransactions: vi.fn(),
      getSuspiciousTransactionLinkedAlertContext: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence summary")).toBeInTheDocument();
    expect(apiClient.getFraudCaseEvidenceSummary).toHaveBeenCalledTimes(1);
    expect(apiClient.getFraudCaseEvidenceSummary).toHaveBeenCalledWith("case-1", expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(apiClient.getAlert).not.toHaveBeenCalled();
    expect(apiClient.getAssistantSummary).not.toHaveBeenCalled();
    expect(apiClient.submitAnalystDecision).not.toHaveBeenCalled();
    expect(apiClient.updateFraudCase).not.toHaveBeenCalled();
    expect(apiClient.listSuspiciousTransactions).not.toHaveBeenCalled();
    expect(apiClient.getSuspiciousTransactionLinkedAlertContext).not.toHaveBeenCalled();
  });

  it("FraudCaseDetailsPageFetchesEvidenceTimelineWithCaseIdOnlyTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence timeline")).toBeInTheDocument();
    await waitFor(() => expect(apiClient.getFraudCaseEvidenceTimeline).toHaveBeenCalledWith("case-1", expect.objectContaining({ signal: expect.any(AbortSignal) })));
    expect(JSON.stringify(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0])).not.toContain("alert");
    expect(JSON.stringify(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0])).not.toContain("customer");
    expect(JSON.stringify(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0])).not.toContain("transaction");
  });

  it("FraudCaseDetailsPageDoesNotPassFullApiClientToTimelineSectionTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
      updateFraudCase: vi.fn(),
      getAlert: vi.fn(),
      getFraudCaseAudit: vi.fn(),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      getSuspiciousTransaction: vi.fn(),
      submitAnalystDecision: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence timeline")).toBeInTheDocument();
    await waitFor(() => expect(apiClient.getFraudCaseEvidenceTimeline).toHaveBeenCalledTimes(1));
    expect(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0][1]).not.toHaveProperty("apiClient");
    expect(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0][1]).not.toHaveProperty("getAlert");
    expect(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0][1]).not.toHaveProperty("submitAnalystDecision");
  });

  it("FraudCaseDetailsPageTimelineFailureDoesNotBreakCaseDetailTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceTimeline: vi.fn().mockRejectedValue(new Error("raw timeline failure")),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    expect(await screen.findByText("Evidence timeline unavailable.")).toBeInTheDocument();
    expect(screen.queryByText("raw timeline failure")).not.toBeInTheDocument();
  });

  it("FraudCaseDetailsPageDoesNotCallAlertOrEvidenceDrilldownFromTimelineTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      updateFraudCase: vi.fn(),
      getAlert: vi.fn(),
      getFraudCaseAudit: vi.fn(),
      getSuspiciousTransaction: vi.fn(),
      submitAnalystDecision: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence timeline")).toBeInTheDocument();
    expect(apiClient.getAlert).not.toHaveBeenCalled();
    expect(apiClient.getFraudCaseAudit).not.toHaveBeenCalled();
    expect(apiClient.getSuspiciousTransaction).not.toHaveBeenCalled();
    expect(apiClient.updateFraudCase).not.toHaveBeenCalled();
    expect(apiClient.submitAnalystDecision).not.toHaveBeenCalled();
  });

  it("FraudCaseDetailsPageDoesNotCallTimelineWithAlertIdTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue({
        ...fraudCase("case-1", "Open case"),
        linkedAlertId: "alert-secret",
        alertId: "alert-secret"
      }),
      getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence timeline")).toBeInTheDocument();
    await waitFor(() => expect(apiClient.getFraudCaseEvidenceTimeline).toHaveBeenCalledWith("case-1", expect.objectContaining({ signal: expect.any(AbortSignal) })));
    expect(JSON.stringify(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0])).not.toContain("alert-secret");
  });

  it("FraudCaseReadSurfaceCompositionRendersSummaryAndTimelineAsSeparateSectionsTest", async () => {
    render(page({ caseId: "case-1" }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    const readSurface = screen.getByTestId("fraud-case-read-surface-layout");
    const summary = within(readSurface).getByTestId("fraud-case-evidence-summary-section");
    const timeline = within(readSurface).getByTestId("fraud-case-evidence-timeline-section");

    expect(summary).not.toBe(timeline);
    expect(within(summary).getByRole("heading", { name: "Evidence summary" })).toBeInTheDocument();
    expect(within(timeline).getByRole("heading", { name: "Evidence timeline" })).toBeInTheDocument();
  });

  it("FraudCaseReadSurfaceCompositionKeepsDecisionRailOutsideReadSurfaceTest", async () => {
    render(page({ caseId: "case-1" }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    const readSurface = screen.getByTestId("fraud-case-read-surface-layout");

    expect(screen.getByRole("heading", { name: "Update case" })).toBeInTheDocument();
    expect(within(readSurface).queryByRole("heading", { name: "Update case" })).not.toBeInTheDocument();
    expect(within(readSurface).queryByRole("button", { name: "Save case decision" })).not.toBeInTheDocument();
  });

  it("FraudCaseReadSurfaceCompositionDoesNotCreateCombinedInvestigationPanelTest", async () => {
    render(page({ caseId: "case-1" }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    expect(screen.queryByTestId("investigation-panel")).not.toBeInTheDocument();
    expect(readPageSource()).not.toContain("InvestigationPanel");
    expect(readPageSource()).not.toContain("EvidenceWorkspace");
  });

  it("FraudCaseReadSurfaceCompositionDoesNotMoveWorkflowControlsIntoReadSurfaceTest", async () => {
    render(page({ caseId: "case-1" }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    const readSurface = screen.getByTestId("fraud-case-read-surface-layout");

    for (const label of ["Save case decision", "Analyst", "Reason", "Tags"]) {
      expect(within(readSurface).queryByLabelText(label)).not.toBeInTheDocument();
      expect(within(readSurface).queryByText(label)).not.toBeInTheDocument();
    }
  });

  it("FraudCaseReadSurfaceCompositionPreservesSectionHelperTextsTest", async () => {
    render(page({ caseId: "case-1" }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    const readSurface = screen.getByTestId("fraud-case-read-surface-layout");

    expect(within(readSurface).getByText("Evidence summary is read-only investigation context. It is not confirmed fraud, not an analyst decision, not a final outcome, and not legal proof.")).toBeInTheDocument();
    expect(within(readSurface).getByText("Evidence timeline is read-only investigation chronology. It is not an audit trail, not complete case history, not confirmed fraud, not an analyst decision, not a final outcome, and not legal proof.")).toBeInTheDocument();
  });

  it("FraudCaseReadSurfaceCompositionDoesNotRenderNewFieldsTest", async () => {
    render(page({ caseId: "case-1" }));

    expect(await screen.findByText("Open case")).toBeInTheDocument();
    const readSurface = screen.getByTestId("fraud-case-read-surface-layout");

    expect(within(readSurface).queryByText("Read-only investigation context")).not.toBeInTheDocument();
    expect(within(readSurface).queryByText("Investigation panel")).not.toBeInTheDocument();
    expect(within(readSurface).queryByText("Case workflow")).not.toBeInTheDocument();
  });

  it("FraudCaseReadSurfaceCompositionPassesNarrowSummaryFetchFunctionOnlyTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence summary")).toBeInTheDocument();
    expect(apiClient.getFraudCaseEvidenceSummary).toHaveBeenCalledWith("case-1", expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(apiClient.getFraudCaseEvidenceSummary.mock.calls[0][1]).not.toHaveProperty("apiClient");
    expect(apiClient.getFraudCaseEvidenceSummary.mock.calls[0][1]).not.toHaveProperty("updateFraudCase");
  });

  it("FraudCaseReadSurfaceCompositionPassesNarrowTimelineFetchFunctionOnlyTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence timeline")).toBeInTheDocument();
    expect(apiClient.getFraudCaseEvidenceTimeline).toHaveBeenCalledWith("case-1", expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0][1]).not.toHaveProperty("apiClient");
    expect(apiClient.getFraudCaseEvidenceTimeline.mock.calls[0][1]).not.toHaveProperty("updateFraudCase");
  });

  it("FraudCaseReadSurfaceCompositionDoesNotPassFullApiClientTest", () => {
    const source = readPageSource();

    expect(source).toContain("getFraudCaseEvidenceSummary={apiClient.getFraudCaseEvidenceSummary}");
    expect(source).toContain("getFraudCaseEvidenceTimeline={apiClient.getFraudCaseEvidenceTimeline}");
    expect(source).not.toContain("apiClient={apiClient}");
  });

  it("FraudCaseReadSurfaceCompositionDoesNotPassWorkflowHandlersTest", () => {
    const readSurfaceSource = readPageSource().slice(
      readPageSource().indexOf("<FraudCaseReadSurfaceLayout>"),
      readPageSource().indexOf("</FraudCaseReadSurfaceLayout>")
    );

    for (const forbidden of [
      "onCaseUpdated",
      "submitDecision",
      "updateFraudCase",
      "closeCase",
      "reopenCase",
      "as" + "signCase",
      "cl" + "aimCase"
    ]) {
      expect(readSurfaceSource).not.toContain(forbidden);
    }
  });

  it("FraudCaseReadSurfaceCompositionSummaryFailureDoesNotHideTimelineTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockRejectedValue(new Error("summary raw failure")),
      getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence summary unavailable.")).toBeInTheDocument();
    expect(await screen.findByText("Evidence timeline")).toBeInTheDocument();
    expect(screen.getByText("Linked alert context")).toBeInTheDocument();
    expect(screen.queryByText("summary raw failure")).not.toBeInTheDocument();
    expect(within(screen.getByTestId("fraud-case-read-surface-layout")).queryByRole("button", { name: "Save case decision" })).not.toBeInTheDocument();
  });

  it("FraudCaseReadSurfaceCompositionTimelineFailureDoesNotHideSummaryTest", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      getFraudCaseEvidenceTimeline: vi.fn().mockRejectedValue(new Error("timeline raw failure")),
      updateFraudCase: vi.fn()
    };

    render(page({ caseId: "case-1", apiClient }));

    expect(await screen.findByText("Evidence timeline unavailable.")).toBeInTheDocument();
    expect(await screen.findByText("Evidence summary")).toBeInTheDocument();
    expect((await screen.findAllByText("Evidence status")).length).toBeGreaterThan(0);
    expect(screen.queryByText("timeline raw failure")).not.toBeInTheDocument();
    expect(within(screen.getByTestId("fraud-case-read-surface-layout")).queryByRole("button", { name: "Save case decision" })).not.toBeInTheDocument();
  });
});

function page({ caseId, apiClient, onCaseUpdated = vi.fn(), canReadFraudCase = true }) {
  const client = {
    getFraudCase: vi.fn().mockResolvedValue(fraudCase(caseId, "Open case")),
    getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
    getFraudCaseEvidenceTimeline: vi.fn().mockResolvedValue(availableTimeline()),
    ...apiClient
  };
  return (
    <FraudCaseDetailsPage
      caseId={caseId}
      session={session()}
      apiClient={client}
      canReadFraudCase={canReadFraudCase}
      onBack={vi.fn()}
      onCaseUpdated={onCaseUpdated}
    />
  );
}

function session() {
  return {
    userId: "analyst-1",
    roles: ["FRAUD_OPS_ADMIN"],
    authorities: ["fraud-case:read", "fraud-case:update"]
  };
}

function fraudCase(caseId, reason) {
  return {
    caseId,
    suspicionType: "Grouped transfers",
    reason,
    status: "OPEN",
    totalAmountPln: 1000,
    transactions: [],
    aggregationWindow: "PT10M",
    thresholdPln: 500,
    decisionReason: "",
    decisionTags: ["rapid-transfer"]
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function readPageSource() {
  return readFileSync(resolve(process.cwd(), "src/pages/FraudCaseDetailsPage.jsx"), "utf8");
}
