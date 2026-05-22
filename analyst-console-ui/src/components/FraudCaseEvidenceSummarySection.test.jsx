import { render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  EVIDENCE_SUMMARY_HELPER_TEXT,
  FraudCaseEvidenceSummarySection
} from "./FraudCaseEvidenceSummarySection.jsx";

describe("FraudCaseEvidenceSummarySection", () => {
  it("FraudCaseEvidenceSummarySectionRendersAvailableSummaryTest", async () => {
    renderSection({ summary: availableSummary() });

    const section = await findSection();
    expect(within(section).getByText("Evidence summary")).toBeInTheDocument();
    expect(within(section).getByText(EVIDENCE_SUMMARY_HELPER_TEXT)).toBeInTheDocument();
    expect(within(section).getByText("Evidence status")).toBeInTheDocument();
    expect(within(section).getAllByText("AVAILABLE").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("HIGH_AMOUNT_ACTIVITY").length).toBeGreaterThan(0);
    expect(within(section).getByText("Model signal")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersPartialSummaryTest", async () => {
    renderSection({ summary: { ...availableSummary(), aggregateEvidenceStatus: "PARTIAL", partial: true } });

    expect(await screen.findByText("Partial summary. Some linked evidence context is incomplete or unavailable.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersLegacyContextTest", async () => {
    renderSection({ summary: { ...emptySummary(), aggregateEvidenceStatus: "LEGACY", legacy: true } });

    expect(await screen.findByText("Legacy context. This case may not have structured evidence summary data.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersTruncatedSummaryTest", async () => {
    renderSection({
      summary: {
        ...availableSummary(),
        aggregateEvidenceStatus: "PARTIAL",
        partial: true,
        truncated: true,
        truncationReason: "LINKED_ALERT_LIMIT_EXCEEDED"
      }
    });

    expect(await screen.findByText("Truncated summary. Only the first bounded set of linked alert evidence was included.")).toBeInTheDocument();
    expect(screen.getByText("LINKED_ALERT_LIMIT_EXCEEDED")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersUnavailableStateTest", async () => {
    renderSection({ summary: { ...emptySummary(), aggregateEvidenceStatus: "UNAVAILABLE" } });

    expect(await screen.findByText("Evidence summary unavailable.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersLoadingStateTest", () => {
    render(
      <FraudCaseEvidenceSummarySection
        caseId="case-1"
        getFraudCaseEvidenceSummary={vi.fn().mockReturnValue(new Promise(() => {}))}
      />
    );

    expect(screen.getByText("Loading evidence summary…")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersSafeErrorStateTest", async () => {
    renderSection({ error: new Error("database payload with customer-secret") });

    expect(await screen.findByText("Evidence summary unavailable.")).toBeInTheDocument();
    expect(screen.queryByText(/customer-secret/)).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionFailureDoesNotBreakCaseDetailTest", async () => {
    render(
      <div>
        <h1>Case detail remains visible</h1>
        <FraudCaseEvidenceSummarySection
          caseId="case-1"
          getFraudCaseEvidenceSummary={vi.fn().mockRejectedValue(new Error("failure"))}
        />
      </div>
    );

    expect(screen.getByText("Case detail remains visible")).toBeInTheDocument();
    expect(await screen.findByText("Evidence summary unavailable.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawPayloadFieldsTest", async () => {
    renderSection({
      summary: {
        ...availableSummary(),
        highestSeverityEvidence: [{
          ...availableSummary().highestSeverityEvidence[0],
          rawPayload: "raw-secret",
          attributes: { customerId: "customer-secret" },
          scoreDetails: { model: "model-secret" },
          featureSnapshot: { amount: 1 }
        }]
      }
    });

    await findSection();
    expect(screen.queryByText("raw-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("customer-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("model-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderUnexpectedExtraFieldsTest", async () => {
    renderSection({
      summary: {
        ...availableSummary(),
        unexpectedField: "unexpected-secret",
        highestSeverityEvidence: [{ ...availableSummary().highestSeverityEvidence[0], extraField: "extra-secret" }]
      }
    });

    await findSection();
    expect(screen.queryByText("unexpected-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("extra-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawIdentifiersTest", async () => {
    renderSection({
      summary: {
        ...availableSummary(),
        alertId: "alert-secret",
        transactionId: "txn-secret",
        customerId: "customer-secret",
        accountId: "account-secret",
        highestSeverityEvidence: [{
          ...availableSummary().highestSeverityEvidence[0],
          sourceEventId: "event-secret",
          correlationId: "correlation-secret"
        }]
      }
    });

    await findSection();
    expect(screen.queryByText("alert-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("txn-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("customer-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("account-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("event-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("correlation-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderJsonInspectorTest", async () => {
    renderSection({ summary: availableSummary() });

    await findSection();
    expect(screen.queryByText("JsonInspector")).not.toBeInTheDocument();
    expect(document.querySelector("pre")).toBeNull();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderEvidenceDrilldownLinksTest", async () => {
    renderSection({ summary: availableSummary() });

    await findSection();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("FraudCaseDetailEvidenceSummaryIsNotMountedWithoutCaseIdTest", async () => {
    const getFraudCaseEvidenceSummary = vi.fn();

    render(
      <FraudCaseEvidenceSummarySection
        caseId=""
        getFraudCaseEvidenceSummary={getFraudCaseEvidenceSummary}
      />
    );

    await waitFor(() => expect(getFraudCaseEvidenceSummary).not.toHaveBeenCalled());
    expect(screen.getByText("Evidence summary unavailable.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotAddMutationControlsTest", async () => {
    renderSection({ summary: availableSummary() });

    const section = await findSection();
    expect(within(section).queryByRole("button")).not.toBeInTheDocument();
    expect(within(section).queryByRole("textbox")).not.toBeInTheDocument();
  });
});

function renderSection({ summary, error } = {}) {
  const getFraudCaseEvidenceSummary = error
    ? vi.fn().mockRejectedValue(error)
    : vi.fn().mockResolvedValue(summary);
  render(
    <FraudCaseEvidenceSummarySection
      caseId="case-1"
      getFraudCaseEvidenceSummary={getFraudCaseEvidenceSummary}
    />
  );
  return { getFraudCaseEvidenceSummary };
}

function findSection() {
  return screen.findByTestId("fraud-case-evidence-summary-section");
}

function emptySummary() {
  return {
    caseId: "case-1",
    aggregateEvidenceStatus: "UNAVAILABLE",
    topReasonCodes: [],
    highestSeverityEvidence: [],
    evidenceBySource: [],
    evidenceByStatus: [],
    linkedAlertCount: 0,
    evidenceItemCount: 0,
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}

function availableSummary() {
  return {
    caseId: "case-1",
    aggregateEvidenceStatus: "AVAILABLE",
    topReasonCodes: ["HIGH_AMOUNT_ACTIVITY"],
    highestSeverityEvidence: [{
      title: "Model signal",
      description: "Bounded model evidence metadata.",
      reasonCode: "HIGH_AMOUNT_ACTIVITY",
      evidenceType: "MODEL_SIGNAL",
      severity: "HIGH",
      source: "ML_SCORING",
      status: "AVAILABLE"
    }],
    evidenceBySource: [{ source: "ML_SCORING", count: 1 }],
    evidenceByStatus: [{ status: "AVAILABLE", count: 1 }],
    linkedAlertCount: 1,
    evidenceItemCount: 1,
    partial: false,
    legacy: false,
    truncated: false,
    truncationReason: null
  };
}
