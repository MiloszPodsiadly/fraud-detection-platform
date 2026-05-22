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

    expect(screen.getByText("Loading evidence summary...")).toBeInTheDocument();
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

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawTitleOrDescriptionTest", async () => {
    renderSection({ summary: maliciousTextSummary() });

    await findSection();
    expectUnsafeTextNotRendered();
  });

  it("FraudCaseEvidenceSummarySectionTitleDescriptionAreGeneratedBoundedCopyTest", async () => {
    renderSection({ summary: maliciousTextSummary() });

    const section = await findSection();
    expect(within(section).getByText("Model signal")).toBeInTheDocument();
    expect(within(section).getByText("Bounded evidence metadata derived from the fraud-case evidence summary.")).toBeInTheDocument();
    expect(within(section).getAllByText("HIGH_AMOUNT_ACTIVITY").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("MODEL_SIGNAL").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("HIGH").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("ML_SCORING").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("AVAILABLE").length).toBeGreaterThan(0);
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

  it("FraudCaseEvidenceSummarySectionHandlesMalformedSourceCountsTest", async () => {
    renderSection({ summary: malformedCountsSummary() });

    const section = await findSection();
    expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("ML_SCORING").length).toBeGreaterThan(0);
    expect(within(section).getByText("ALERT_SERVICE")).toBeInTheDocument();
    expect(within(section).getAllByText("0").length).toBeGreaterThan(0);
    expect(within(section).queryByText("raw-source-secret")).not.toBeInTheDocument();
    expect(within(section).queryByText("raw-customer-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionHandlesMalformedStatusCountsTest", async () => {
    renderSection({ summary: malformedCountsSummary() });

    const section = await findSection();
    expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("AVAILABLE").length).toBeGreaterThan(0);
    expect(within(section).getByText("PARTIAL")).toBeInTheDocument();
    expect(within(section).queryByText("raw-status-secret")).not.toBeInTheDocument();
    expect(within(section).queryByText("raw-status-with-customer-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionMalformedCountsDoNotBreakCaseDetailTest", async () => {
    render(
      <div>
        <h1>Case detail remains visible</h1>
        <FraudCaseEvidenceSummarySection
          caseId="case-1"
          getFraudCaseEvidenceSummary={vi.fn().mockResolvedValue(malformedCountsSummary())}
        />
      </div>
    );

    expect(screen.getByText("Case detail remains visible")).toBeInTheDocument();
    expect(await screen.findByTestId("fraud-case-evidence-summary-section")).toBeInTheDocument();
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThan(0);
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawReasonCodeTextTest", async () => {
    renderSection({ summary: malformedEnumSummary() });

    const section = await findSection();
    expect(within(section).getAllByText("HIGH_AMOUNT_ACTIVITY").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    expectUnsafeEnumTextNotRendered();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawSourceOrStatusTextTest", async () => {
    renderSection({ summary: malformedEnumSummary() });

    const section = await findSection();
    expect(within(section).getAllByText("ML_SCORING").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("AVAILABLE").length).toBeGreaterThan(0);
    expectUnsafeEnumTextNotRendered();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderMalformedTruncationReasonTest", async () => {
    renderSection({ summary: malformedEnumSummary() });

    const section = await findSection();
    expect(within(section).getByText("Bounded summary limit reached")).toBeInTheDocument();
    expect(within(section).queryByText("customer-secret raw truncation reason")).not.toBeInTheDocument();
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

  it("FraudCaseEvidenceSummarySectionDoesNotContainForbiddenWorkflowOrVerdictWordingTest", async () => {
    renderSection({ summary: availableSummary() });

    const section = await findSection();
    let sectionText = section.textContent.toLowerCase();
    for (const allowedPhrase of [
      "not confirmed fraud",
      "not an analyst decision",
      "not a final outcome",
      "not legal proof"
    ]) {
      sectionText = sectionText.replaceAll(allowedPhrase, "");
    }

    for (const term of forbiddenSectionTerms()) {
      expect(sectionText).not.toContain(term);
    }
    expect(within(section).getByText(EVIDENCE_SUMMARY_HELPER_TEXT)).toBeInTheDocument();
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

function maliciousTextSummary() {
  return {
    ...availableSummary(),
    highestSeverityEvidence: [{
      title: "customer-secret account-secret txn-secret correlation-secret alert-secret raw-model-payload scoreDetails featureSnapshot CONFIRMED_FRAUD",
      description: "source-event-secret raw-event-payload legal proof final outcome analyst decision proof of fraud",
      reasonCode: "HIGH_AMOUNT_ACTIVITY",
      evidenceType: "MODEL_SIGNAL",
      severity: "HIGH",
      source: "ML_SCORING",
      status: "AVAILABLE"
    }]
  };
}

function malformedCountsSummary() {
  return {
    ...availableSummary(),
    evidenceBySource: [
      null,
      "raw-source-secret",
      {},
      { source: "ML_SCORING", count: 1 },
      { source: "ALERT_SERVICE", count: "2" },
      { source: "raw-customer-secret", count: "not-a-number" }
    ],
    evidenceByStatus: [
      null,
      "raw-status-secret",
      {},
      { status: "AVAILABLE", count: 1 },
      { status: "PARTIAL", count: "2" },
      { status: "raw-status-with-customer-secret", count: "not-a-number" }
    ]
  };
}

function malformedEnumSummary() {
  return {
    ...availableSummary(),
    topReasonCodes: [
      "HIGH_AMOUNT_ACTIVITY",
      "customer-secret account-secret txn-secret",
      "raw-model-payload-scoreDetails-featureSnapshot-too-long-too-long-too-long-too-long"
    ],
    highestSeverityEvidence: [{
      reasonCode: "customer-secret reason",
      evidenceType: "MODEL_SIGNAL",
      severity: "HIGH",
      source: "ML_SCORING",
      status: "AVAILABLE",
      title: "unsafe title should not render",
      description: "unsafe description should not render"
    }],
    evidenceBySource: [
      { source: "ML_SCORING", count: 1 },
      { source: "customer-secret source", count: 1 }
    ],
    evidenceByStatus: [
      { status: "AVAILABLE", count: 1 },
      { status: "raw status with customer-secret", count: 1 }
    ],
    truncated: true,
    truncationReason: "customer-secret raw truncation reason"
  };
}

function expectUnsafeTextNotRendered() {
  let safeSectionText = screen.getByTestId("fraud-case-evidence-summary-section").textContent.toLowerCase();
  for (const allowedPhrase of [
    "not confirmed fraud",
    "not an analyst decision",
    "not a final outcome",
    "not legal proof"
  ]) {
    safeSectionText = safeSectionText.replaceAll(allowedPhrase, "");
  }

  for (const unsafeText of [
    "customer-secret",
    "account-secret",
    "txn-secret",
    "correlation-secret",
    "alert-secret",
    "raw-model-payload",
    "raw-event-payload",
    "scoreDetails",
    "featureSnapshot",
    "source-event-secret",
    "CONFIRMED_FRAUD",
    "legal proof",
    "final outcome",
    "analyst decision",
    "proof of fraud"
  ]) {
    expect(safeSectionText).not.toContain(unsafeText.toLowerCase());
  }
}

function expectUnsafeEnumTextNotRendered() {
  for (const unsafeText of [
    "customer-secret",
    "account-secret",
    "txn-secret",
    "raw-model-payload",
    "scoreDetails",
    "featureSnapshot",
    "unsafe title should not render",
    "unsafe description should not render",
    "raw truncation reason"
  ]) {
    expect(screen.queryByText(unsafeText, { exact: false })).not.toBeInTheDocument();
  }
}

function forbiddenSectionTerms() {
  return [
    "proof of fraud",
    "fraud confirmed",
    "confirmed fraud",
    "fraud verdict",
    "final decision",
    "final outcome",
    "analyst decision",
    "legal proof",
    "case decision",
    "resolve",
    "confirm",
    "dismiss",
    "close",
    "reopen",
    "as" + "sign",
    "cl" + "aim",
    "submit",
    "take action",
    "approve",
    "reject",
    "escalate"
  ];
}
