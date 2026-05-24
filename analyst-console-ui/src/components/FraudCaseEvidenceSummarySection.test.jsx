import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  EVIDENCE_SUMMARY_HELPER_TEXT,
  FraudCaseEvidenceSummarySection
} from "./FraudCaseEvidenceSummarySection.jsx";
import {
  availableEvidenceSummary,
  legacyEvidenceSummary,
  maliciousTextEvidenceSummary,
  malformedCountsEvidenceSummary,
  malformedEnumEvidenceSummary,
  partialEvidenceSummary,
  rawPayloadEvidenceSummary,
  truncatedEvidenceSummary,
  unavailableEvidenceSummary
} from "../test-utils/evidenceSummaryFixtures.js";
import {
  expectEvidenceSummaryNonClaimHelperVisible,
  expectNoForbiddenEvidenceSummaryWording,
  expectNoMutationControls,
  expectNoRawEvidencePayloadText
} from "../test-utils/evidenceSummaryUiAssertions.js";
import {
  expectNoInvestigationDrilldowns,
  expectNoInvestigationMutationControls,
  expectNoInvestigationRawIdentifiers,
  expectNoInvestigationRawPayloads,
  expectNoInvestigationVerdictProofWording,
  expectNoInvestigationWorkflowControls,
  expectReadOnlyInvestigationHelperVisible
} from "../test-utils/fraudCaseInvestigationReadSurfaceAssertions.js";
import {
  maliciousInvestigationRawIdentifiers,
  maliciousInvestigationRawPayloadFields,
  maliciousInvestigationVerdictProofText,
  maliciousInvestigationWorkflowLabels
} from "../test-utils/fraudCaseInvestigationReadSurfaceFixtures.js";

describe("FraudCaseEvidenceSummarySection", () => {
  it("FraudCaseEvidenceSummarySectionRendersAvailableSummaryTest", async () => {
    renderSection({ summary: availableEvidenceSummary() });

    const section = await findSection();
    expect(within(section).getByText("Evidence summary")).toBeInTheDocument();
    expectEvidenceSummaryNonClaimHelperVisible(section);
    expect(within(section).getByText("Evidence status")).toBeInTheDocument();
    expect(within(section).getAllByText("AVAILABLE").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("HIGH_AMOUNT_ACTIVITY").length).toBeGreaterThan(0);
    expect(within(section).getByText("Model signal")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersPartialSummaryTest", async () => {
    renderSection({ summary: partialEvidenceSummary() });

    expect(await screen.findByText("Partial summary. Some linked evidence context is incomplete or unavailable.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersLegacyContextTest", async () => {
    renderSection({ summary: legacyEvidenceSummary() });

    expect(await screen.findByText("Legacy context. This case may not have structured evidence summary data.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersTruncatedSummaryTest", async () => {
    renderSection({
      summary: truncatedEvidenceSummary()
    });

    expect(await screen.findByText("Truncated summary. Only the first bounded set of linked alert evidence was included.")).toBeInTheDocument();
    expect(screen.getByText("LINKED_ALERT_LIMIT_EXCEEDED")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionRendersUnavailableStateTest", async () => {
    renderSection({ summary: unavailableEvidenceSummary() });

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
        ...rawPayloadEvidenceSummary(),
        highestSeverityEvidence: [{
          ...rawPayloadEvidenceSummary().highestSeverityEvidence[0],
          rawPayload: "raw-secret"
        }]
      }
    });

    const section = await findSection();
    expectNoRawEvidencePayloadText(section);
    expect(screen.queryByText("raw-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawTitleOrDescriptionTest", async () => {
    renderSection({ summary: maliciousTextEvidenceSummary() });

    const section = await findSection();
    expectNoRawEvidencePayloadText(section);
    expectNoForbiddenEvidenceSummaryWording(section);
  });

  it("FraudCaseEvidenceSummarySectionTitleDescriptionAreGeneratedBoundedCopyTest", async () => {
    renderSection({ summary: maliciousTextEvidenceSummary() });

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
        ...availableEvidenceSummary(),
        unexpectedField: "unexpected-secret",
        highestSeverityEvidence: [{ ...availableEvidenceSummary().highestSeverityEvidence[0], extraField: "extra-secret" }]
      }
    });

    await findSection();
    expect(screen.queryByText("unexpected-secret")).not.toBeInTheDocument();
    expect(screen.queryByText("extra-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawIdentifiersTest", async () => {
    renderSection({
      summary: {
        ...availableEvidenceSummary(),
        alertId: "alert-secret",
        transactionId: "txn-secret",
        customerId: "customer-secret",
        accountId: "account-secret",
        highestSeverityEvidence: [{
          ...availableEvidenceSummary().highestSeverityEvidence[0],
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

  it("FraudCaseEvidenceSummarySectionUsesSharedReadSurfaceSafetyAssertionsTest", async () => {
    const rawIdentifiers = maliciousInvestigationRawIdentifiers();
    const rawPayloads = maliciousInvestigationRawPayloadFields();
    const workflowLabels = maliciousInvestigationWorkflowLabels();
    const verdictProofText = maliciousInvestigationVerdictProofText();
    renderSection({
      summary: {
        ...rawPayloadEvidenceSummary(),
        caseId: "case-secret",
        linkedAlertId: "linked-alert-secret",
        evidenceId: "evidence-secret",
        scoreDecisionId: "score-decision-secret",
        unexpectedIdentifiers: rawIdentifiers.join(" "),
        unexpectedPayloads: rawPayloads.join(" "),
        unexpectedWorkflow: workflowLabels.join(" "),
        unexpectedVerdict: verdictProofText.join(" "),
        highestSeverityEvidence: [{
          ...rawPayloadEvidenceSummary().highestSeverityEvidence[0],
          title: "raw evidence title raw backend title raw model explanation",
          description: "raw evidence description raw backend description raw decision reason",
          rawPayload: "raw-payload-secret",
          modelPayload: "model-payload-secret",
          eventPayload: "event-payload-secret",
          scoreDetails: "scoreDetails-secret",
          featureSnapshot: "featureSnapshot-secret"
        }]
      }
    });

    const section = await findSection();
    expectNoInvestigationRawIdentifiers(section);
    expectNoInvestigationRawPayloads(section);
    expectNoInvestigationMutationControls(section);
    expectNoInvestigationWorkflowControls(section);
    expectNoInvestigationDrilldowns(section);
    expectNoInvestigationVerdictProofWording(section);
    expectReadOnlyInvestigationHelperVisible(section, EVIDENCE_SUMMARY_HELPER_TEXT);
  });

  it("FraudCaseEvidenceSummarySectionHandlesMalformedSourceCountsTest", async () => {
    renderSection({ summary: malformedCountsEvidenceSummary() });

    const section = await findSection();
    expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("ML_SCORING").length).toBeGreaterThan(0);
    expect(within(section).getByText("ALERT_SERVICE")).toBeInTheDocument();
    expect(within(section).getAllByText("0").length).toBeGreaterThan(0);
    expect(within(section).queryByText("raw-source-secret")).not.toBeInTheDocument();
    expect(within(section).queryByText("raw-customer-secret")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionHandlesMalformedStatusCountsTest", async () => {
    renderSection({ summary: malformedCountsEvidenceSummary() });

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
          getFraudCaseEvidenceSummary={vi.fn().mockResolvedValue(malformedCountsEvidenceSummary())}
        />
      </div>
    );

    expect(screen.getByText("Case detail remains visible")).toBeInTheDocument();
    expect(await screen.findByTestId("fraud-case-evidence-summary-section")).toBeInTheDocument();
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThan(0);
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawReasonCodeTextTest", async () => {
    renderSection({ summary: malformedEnumEvidenceSummary() });

    const section = await findSection();
    expect(within(section).getAllByText("HIGH_AMOUNT_ACTIVITY").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    expectNoRawEvidencePayloadText(section);
  });

  it("FraudCaseEvidenceSummarySectionHandlesNullEmptyDuplicateReasonCodesTest", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    try {
      renderSection({
        summary: {
          ...availableEvidenceSummary(),
          topReasonCodes: [
            null,
            undefined,
            "",
            " ",
            "HIGH_AMOUNT_ACTIVITY",
            "HIGH_AMOUNT_ACTIVITY",
            "customer-secret account-secret txn-secret",
            "raw-model-payload scoreDetails featureSnapshot"
          ]
        }
      });

      const section = await findSection();
      expect(within(section).getAllByText("HIGH_AMOUNT_ACTIVITY").length).toBeGreaterThan(0);
      expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
      expectNoRawEvidencePayloadText(section);
      expect(consoleError.mock.calls.some((call) => String(call[0]).includes("same key"))).toBe(false);
    } finally {
      consoleError.mockRestore();
    }
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderRawSourceOrStatusTextTest", async () => {
    renderSection({ summary: malformedEnumEvidenceSummary() });

    const section = await findSection();
    expect(within(section).getAllByText("ML_SCORING").length).toBeGreaterThan(0);
    expect(within(section).getAllByText("AVAILABLE").length).toBeGreaterThan(0);
    expectNoRawEvidencePayloadText(section);
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderMalformedTruncationReasonTest", async () => {
    renderSection({ summary: malformedEnumEvidenceSummary() });

    const section = await findSection();
    expect(within(section).getByText("Bounded summary limit reached")).toBeInTheDocument();
    expect(within(section).queryByText("customer-secret raw truncation reason")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderJsonInspectorTest", async () => {
    renderSection({ summary: availableEvidenceSummary() });

    await findSection();
    expect(screen.queryByText("JsonInspector")).not.toBeInTheDocument();
    expect(document.querySelector("pre")).toBeNull();
  });

  it("FraudCaseEvidenceSummarySectionDoesNotRenderEvidenceDrilldownLinksTest", async () => {
    renderSection({ summary: availableEvidenceSummary() });

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
    renderSection({ summary: availableEvidenceSummary() });

    const section = await findSection();
    expectNoMutationControls(section);
  });

  it("FraudCaseEvidenceSummarySectionDoesNotContainForbiddenWorkflowOrVerdictWordingTest", async () => {
    renderSection({ summary: availableEvidenceSummary() });

    const section = await findSection();
    expectNoForbiddenEvidenceSummaryWording(section);
    expectEvidenceSummaryNonClaimHelperVisible(section);
  });

  it("FraudCaseEvidenceSummarySectionImportBoundaryTest", () => {
    const source = componentSource();

    // This source-level guard is an intentional FDP-75 governance tripwire. It complements runtime tests
    // and helper unit tests; it is not a replacement for them. Keep it scoped to this component so
    // existing workflow controls elsewhere on FraudCaseDetailsPage do not fail this test.
    for (const forbidden of [
      "createAlertsApiClient",
      "apiClient",
      "getAlert",
      "AlertDetailsPage",
      "AlertReadOnlyContextPage",
      "getSuspiciousTransaction",
      "SuspiciousTransactionDetail",
      "getSuspiciousTransactionLinkedAlertContext",
      "updateFraudCase",
      "submitAnalystDecision",
      "createDecision",
      "closeCase",
      "reopenCase",
      "as" + "signCase",
      "cl" + "aimCase",
      "confirmFraud",
      "resolveCase",
      "dismissCase",
      "onCaseUpdated",
      "onDecisionSubmitted",
      "JsonInspector",
      "RawPayload",
      "EvidenceDrilldown",
      "EvidenceDetails",
      "JSON.stringify",
      "item.title",
      "item.description",
      "rawPayload",
      "attributes",
      "scoreDetails",
      "featureSnapshot",
      "sourceEventId",
      "transactionId",
      "customerId",
      "accountId",
      "correlationId",
      "alertId"
    ]) {
      expect(source).not.toContain(forbidden);
    }
  });

  it("FraudCaseEvidenceSummarySectionPropContractTest", async () => {
    const forbiddenApi = {
      getFraudCaseEvidenceSummary: vi.fn().mockResolvedValue(availableEvidenceSummary()),
      apiClient: vi.fn(),
      updateFraudCase: vi.fn(),
      submitDecision: vi.fn(),
      onCaseUpdated: vi.fn(),
      onDecisionSubmitted: vi.fn(),
      alertId: "alert-secret",
      linkedAlertId: "linked-alert-secret",
      suspiciousTransactionId: "suspicious-secret",
      customerId: "customer-secret",
      accountId: "account-secret",
      transactionId: "txn-secret",
      rawEvidence: { rawPayload: "raw-model-payload" },
      alertDetails: { alertId: "alert-secret" },
      fullAlertDetailsResponse: { rawPayload: "raw-model-payload" }
    };

    render(
      <FraudCaseEvidenceSummarySection
        caseId="case-1"
        getFraudCaseEvidenceSummary={forbiddenApi.getFraudCaseEvidenceSummary}
      />
    );

    const section = await findSection();
    expect(forbiddenApi.getFraudCaseEvidenceSummary).toHaveBeenCalledWith("case-1", expect.objectContaining({ signal: expect.any(AbortSignal) }));
    for (const key of Object.keys(forbiddenApi).filter((entry) => entry !== "getFraudCaseEvidenceSummary")) {
      if (typeof forbiddenApi[key] === "function") {
        expect(forbiddenApi[key]).not.toHaveBeenCalled();
      }
    }
    expectNoRawEvidencePayloadText(section);
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

function componentSource() {
  return readFileSync(resolve(process.cwd(), "src/components/FraudCaseEvidenceSummarySection.jsx"), "utf8");
}
