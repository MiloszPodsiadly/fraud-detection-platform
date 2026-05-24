import { render, screen, within } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { FraudCaseEvidenceTimelineSection } from "./FraudCaseEvidenceTimelineSection.jsx";
import {
  formatTimelineTime,
  normalizeTimelineEventType,
  normalizeTimelineEvidenceStatus,
  normalizeTimelineLinkedEntityType,
  normalizeTimelineSource,
  safeTruncationReason,
  toTimelineEvent
} from "./fraudCaseTimelineDisplay.js";
import { timelineEventDescription, timelineEventTitle, timelineStateNotice } from "./fraudCaseTimelineCopy.js";
import {
  availableTimeline,
  emptyLegacyPartialTimeline,
  emptyPartialTimeline,
  emptyTimeline,
  emptyTruncatedTimeline,
  largeTimeline,
  legacyTimeline,
  maliciousTimeline,
  malformedTimeline,
  nullTimestampTimeline,
  partialTimeline,
  truncatedTimeline,
  uppercaseRawIdTimeline
} from "../test-utils/fraudCaseTimelineFixtures.js";
import {
  expectNoRawTimelineIdentifiers,
  expectNoRawTimelinePayloads,
  expectNoTimelineForbiddenWording,
  expectNoTimelineMutationControls,
  expectTimelineNonClaimHelperVisible
} from "../test-utils/fraudCaseTimelineUiAssertions.js";

describe("FraudCaseEvidenceTimelineSection", () => {
  it("FraudCaseEvidenceTimelineSectionRendersRequiredNonClaimHelperTextTest", async () => {
    renderSection({ timeline: availableTimeline() });

    const section = await findSection();

    expectTimelineNonClaimHelperVisible(section);
  });

  it("FraudCaseEvidenceTimelineSectionRendersEventsTest", async () => {
    renderSection({ timeline: availableTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Fraud case created")).toBeInTheDocument();
    expect(within(section).getByText("Linked alert context")).toBeInTheDocument();
    expect(within(section).getByText("Alert evidence snapshot available")).toBeInTheDocument();
    expect(within(section).getByText("Read-only linked alert context derived from existing alert read data.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionUsesBackendLinkedAlertContextEventTypeTest", async () => {
    expect(normalizeTimelineEventType("LINKED_ALERT_CONTEXT")).toBe("LINKED_ALERT_CONTEXT");
    expect(timelineEventTitle("LINKED_ALERT_CONTEXT")).toBe("Linked alert context");
    expect(timelineEventDescription("LINKED_ALERT_CONTEXT")).toBe("Read-only linked alert context derived from existing alert read data.");
    expect(normalizeTimelineEventType("FRAUD_ALERT_LINKED")).toBe("UNKNOWN");

    renderSection({
      timeline: {
        ...emptyTimeline(),
        events: [{
          eventKey: "linked-alert-context",
          eventType: "LINKED_ALERT_CONTEXT",
          occurredAt: "2026-05-23T10:00:00Z",
          approximateTime: true,
          source: "ALERT_SERVICE",
          evidenceStatus: "AVAILABLE",
          linkedEntityType: "FRAUD_ALERT",
          title: "raw backend title should not render",
          description: "raw backend description should not render"
        }]
      }
    });

    const section = await findSection();
    const text = section.textContent;

    expect(within(section).getByText("Linked alert context")).toBeInTheDocument();
    expect(within(section).getByText("Read-only linked alert context derived from existing alert read data.")).toBeInTheDocument();
    expect(within(section).getByText("LINKED_ALERT_CONTEXT")).toBeInTheDocument();
    expect(text).not.toContain("UNKNOWN");
    expect(text).not.toContain("raw backend title should not render");
    expect(text).not.toContain("raw backend description should not render");
    expect(text).not.toContain("FRAUD_ALERT_LINKED");
    expect(text.toLowerCase()).not.toContain("linked at");
  });

  it("FraudCaseEvidenceTimelineSectionRendersEventsInBackendResponseOrderTest", async () => {
    renderSection({ timeline: availableTimeline() });

    const section = await findSection();
    const titles = within(section).getAllByRole("listitem").map((item) => item.querySelector("strong")?.textContent);

    expect(titles).toEqual([
      "Fraud case created",
      "Linked alert context",
      "Alert evidence snapshot available"
    ]);
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotClientSortEventsTest", async () => {
    renderSection({ timeline: availableTimeline() });

    const section = await findSection();
    const text = section.textContent;

    expect(text.indexOf("Linked alert context")).toBeLessThan(text.indexOf("Alert evidence snapshot available"));
  });

  it("FraudCaseEvidenceTimelineSectionRendersApproximateTimeSafelyTest", async () => {
    renderSection({ timeline: availableTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Approximate time")).toBeInTheDocument();
    expect(section.textContent).not.toContain("Invalid Date");
    expect(section.textContent).not.toContain("undefined");
    expect(section.textContent).not.toContain("NaN");
  });

  it("FraudCaseEvidenceTimelineSectionRendersNullOccurredAtAsTimeUnavailableTest", async () => {
    renderSection({ timeline: nullTimestampTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Time unavailable")).toBeInTheDocument();
    expect(within(section).getByText("Approximate time")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderGeneratedAtAsOccurredAtTest", async () => {
    renderSection({ timeline: nullTimestampTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Time unavailable")).toBeInTheDocument();
    expect(section.textContent).not.toContain("10:00:00Z");
  });

  it("FraudCaseEvidenceTimelineSectionRendersPartialLegacyTruncatedStatesTest", async () => {
    renderSection({ timeline: truncatedTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Partial timeline. Some linked evidence context is incomplete or unavailable.")).toBeInTheDocument();
    expect(within(section).getByText("Truncated timeline. Only the first bounded set of evidence timeline events was included.")).toBeInTheDocument();
    expect(within(section).getByText("TIMELINE_EVENT_LIMIT_EXCEEDED")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersLegacyStateTest", async () => {
    renderSection({ timeline: legacyTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Legacy context. This case may not have structured evidence timeline data.")).toBeInTheDocument();
    expect(within(section).getByText("Legacy case context")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersSafeEmptyStateTest", async () => {
    renderSection({ timeline: emptyTimeline() });

    expect(await screen.findByText("No evidence timeline events are available for this case.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersPartialNoticeWhenEventsEmptyTest", async () => {
    renderSection({ timeline: emptyPartialTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Partial timeline. Some linked evidence context is incomplete or unavailable.")).toBeInTheDocument();
    expect(within(section).getByText("No evidence timeline events are available for this case.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersTruncatedNoticeWhenEventsEmptyTest", async () => {
    renderSection({ timeline: emptyTruncatedTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Truncated timeline. Only the first bounded set of evidence timeline events was included.")).toBeInTheDocument();
    expect(within(section).getByText("No evidence timeline events are available for this case.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersSafeTruncationReasonWhenEventsEmptyTest", async () => {
    renderSection({ timeline: emptyTruncatedTimeline() });

    expect(within(await findSection()).getByText("TIMELINE_EVENT_LIMIT_EXCEEDED")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersLegacyAndPartialWhenEventsEmptyTest", async () => {
    renderSection({ timeline: emptyLegacyPartialTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Legacy context. This case may not have structured evidence timeline data.")).toBeInTheDocument();
    expect(within(section).getByText("Partial timeline. Some linked evidence context is incomplete or unavailable.")).toBeInTheDocument();
    expect(within(section).getByText("No evidence timeline events are available for this case.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersSafeStateForNullTimelineResponseTest", async () => {
    renderSection({ timeline: null });

    const section = await findSection();

    expect(within(section).getByText("Evidence timeline")).toBeInTheDocument();
    expect(within(section).getByText("No evidence timeline events are available for this case.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersSafeUnavailableStateTest", async () => {
    renderSection({ timeline: null, error: true });

    expect(await screen.findByText("Evidence timeline unavailable.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionRendersSafeErrorStateTest", async () => {
    render(
      <FraudCaseEvidenceTimelineSection
        caseId="case-1"
        getFraudCaseEvidenceTimeline={vi.fn().mockRejectedValue(new Error("raw backend timeline failure"))}
      />
    );

    expect(await screen.findByText("Evidence timeline unavailable.")).toBeInTheDocument();
    expect(screen.queryByText("raw backend timeline failure")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderEventKeyTest", async () => {
    renderSection({ timeline: maliciousTimeline() });

    const section = await findSection();

    expect(section.textContent).not.toContain("SECRET_EVENT_KEY_SHOULD_NOT_RENDER");
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderEventKeyInAriaLabelTitleOrUrlTest", async () => {
    renderSection({ timeline: maliciousTimeline() });

    const section = await findSection();

    expect(section.innerHTML).not.toContain("SECRET_EVENT_KEY_SHOULD_NOT_RENDER");
    expect(within(section).queryByRole("link")).not.toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderRawIdentifiersTest", async () => {
    renderSection({ timeline: maliciousTimeline() });

    expectNoRawTimelineIdentifiers(await findSection());
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderRawPayloadsTest", async () => {
    renderSection({ timeline: maliciousTimeline() });

    expectNoRawTimelinePayloads(await findSection());
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderRawTitleOrDescriptionTest", async () => {
    renderSection({ timeline: maliciousTimeline() });

    const section = await findSection();

    expect(section.textContent).not.toContain("raw customer");
    expect(section.textContent).not.toContain("raw payload");
    expect(within(section).getByText("Linked alert context")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderJsonInspectorTest", async () => {
    renderSection({ timeline: availableTimeline() });

    expect((await findSection()).textContent).not.toContain("JSON");
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderDrilldownLinksTest", async () => {
    renderSection({ timeline: availableTimeline() });

    const section = await findSection();

    expect(within(section).queryByRole("link")).not.toBeInTheDocument();
    expect(section.textContent).not.toContain("Open alert");
    expect(section.textContent).not.toContain("View evidence");
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderMutationControlsTest", async () => {
    renderSection({ timeline: availableTimeline() });

    expectNoTimelineMutationControls(await findSection());
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderForbiddenWordingTest", async () => {
    renderSection({ timeline: availableTimeline() });

    expectNoTimelineForbiddenWording(await findSection());
  });

  it("FraudCaseEvidenceTimelineSectionHandlesMalformedValuesTest", async () => {
    renderSection({ timeline: malformedTimeline() });

    const section = await findSection();

    expect(within(section).getByText("Timeline event")).toBeInTheDocument();
    expect(within(section).getByText("Time unavailable")).toBeInTheDocument();
    expect(within(section).getByText("Bounded timeline window reached")).toBeInTheDocument();
    expectNoRawTimelineIdentifiers(section);
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderUppercaseRawIdShapedEventTypeTest", async () => {
    renderSection({ timeline: uppercaseRawIdTimeline() });

    const section = await findSection();

    expect(section.textContent).not.toContain("CORRELATION_ID_ABC123");
    expect(section.innerHTML).not.toContain("CORRELATION_ID_ABC123");
    expect(within(section).getByText("Timeline event")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderUppercaseRawIdShapedSourceTest", async () => {
    renderSection({ timeline: uppercaseRawIdTimeline() });

    const section = await findSection();

    expect(section.textContent).not.toContain("SOURCE_EVENT_20260523_ABC");
    expect(section.innerHTML).not.toContain("SOURCE_EVENT_20260523_ABC");
    expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderUppercaseRawIdShapedEvidenceStatusTest", async () => {
    renderSection({ timeline: uppercaseRawIdTimeline() });

    const section = await findSection();

    expect(section.textContent).not.toContain("CUSTOMER_123456");
    expect(section.innerHTML).not.toContain("CUSTOMER_123456");
    expect(within(section).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotRenderUppercaseRawIdShapedLinkedEntityTypeTest", async () => {
    renderSection({ timeline: uppercaseRawIdTimeline() });

    const section = await findSection();

    expect(section.textContent).not.toContain("FRAUD_ALERT:ALERT_123");
    expect(section.innerHTML).not.toContain("FRAUD_ALERT:ALERT_123");
    expect(section.textContent).not.toContain("SAFE_KEY_SHOULD_NOT_RENDER");
    expect(section.textContent).not.toContain("raw title should not render");
    expect(section.textContent).not.toContain("raw description should not render");
    expect(within(section).getByText("Read-only timeline context derived from available read data.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionCapsLargeEventArrayTest", async () => {
    renderSection({ timeline: largeTimeline() });

    const section = await findSection();

    expect(within(section).getAllByRole("listitem")).toHaveLength(100);
    expect(within(section).getByText("Timeline display capped. Only the bounded timeline window is shown.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionLargeEventCapDoesNotExposeOmittedEventKeyTest", async () => {
    renderSection({ timeline: largeTimeline() });

    const section = await findSection();

    expect(section.textContent).not.toContain("OMITTED_EVENT_KEY_101");
    expect(section.innerHTML).not.toContain("OMITTED_EVENT_KEY_101");
    expect(section.textContent).not.toContain("omitted raw title 101");
  });

  it("FraudCaseEvidenceTimelineSectionLargeEventCapDoesNotClaimCompletenessTest", async () => {
    renderSection({ timeline: largeTimeline() });

    const section = await findSection();
    const text = section.textContent.toLowerCase().replaceAll("not complete case history", "");

    expect(text).not.toContain("complete");
    expect(within(section).getByText("Timeline display capped. Only the bounded timeline window is shown.")).toBeInTheDocument();
  });

  it("FraudCaseEvidenceTimelineSectionImportBoundaryTest", () => {
    const source = sectionSource();

    // This is a governance tripwire, not formal static analysis. Keep it scoped to FraudCaseEvidenceTimelineSection.jsx.
    for (const forbidden of [
      "apiClient",
      "getAlert",
      "AlertDetailsPage",
      "AlertReadOnlyContextPage",
      "getFraudCaseAudit",
      "getFraudCaseEvidenceSummary",
      "getSuspiciousTransaction",
      "updateFraudCase",
      "submitAnalystDecision",
      "closeCase",
      "reopenCase",
      "as" + "signCase",
      "cl" + "aimCase",
      "confirmFraud",
      "resolveCase",
      "JsonInspector",
      "RawPayload",
      "EvidenceDrilldown",
      "TimelineEditor",
      "JSON.stringify",
      "alertId",
      "linkedAlertId",
      "transactionId",
      "customerId",
      "accountId",
      "correlationId",
      "sourceEventId",
      "evidenceId",
      "scoreDecisionId",
      "rawPayload",
      "attributes",
      "scoreDetails",
      "featureSnapshot",
      "finalOutcome",
      "analystDecision",
      "eventKey"
    ]) {
      expect(source).not.toContain(forbidden);
    }
  });

  it("FraudCaseEvidenceTimelineSectionDoesNotDirectlyRenderBackendTitleDescriptionSourceTest", () => {
    const section = sectionSource();
    const display = displaySource();

    for (const forbidden of [
      "item.title",
      "item.description",
      "title: item",
      "description: item",
      "title: event.title",
      "description: event.description"
    ]) {
      expect(section).not.toContain(forbidden);
      expect(display).not.toContain(forbidden);
    }

    expect(display).toContain("title: timelineEventTitle(eventType)");
    expect(display).toContain("description: timelineEventDescription(eventType)");
  });
});

describe("fraudCaseTimelineDisplay", () => {
  it("normalizes only backend allowlisted timeline codes", () => {
    expect(normalizeTimelineEventType("LINKED_ALERT_CONTEXT")).toBe("LINKED_ALERT_CONTEXT");
    expect(normalizeTimelineEventType("FRAUD_ALERT_LINKED")).toBe("UNKNOWN");
    expect(normalizeTimelineEventType("CORRELATION_ID_ABC123")).toBe("UNKNOWN");
    expect(normalizeTimelineSource("FRAUD_SCORING_SERVICE")).toBe("FRAUD_SCORING_SERVICE");
    expect(normalizeTimelineSource("SOURCE_EVENT_20260523_ABC")).toBe("UNKNOWN");
    expect(normalizeTimelineEvidenceStatus("ERROR")).toBe("ERROR");
    expect(normalizeTimelineEvidenceStatus("CUSTOMER_123456")).toBe("UNKNOWN");
    expect(normalizeTimelineLinkedEntityType("FRAUD_ALERT")).toBe("FRAUD_ALERT");
    expect(normalizeTimelineLinkedEntityType("FRAUD_ALERT:ALERT_123")).toBe("UNKNOWN");
  });

  it("formats timeline times safely", () => {
    expect(formatTimelineTime(null, true)).toEqual({ label: "Time unavailable", qualifier: "Approximate time" });
    expect(formatTimelineTime("not-a-date", false)).toEqual({ label: "Time unavailable", qualifier: null });
    expect(formatTimelineTime("2026-05-23T10:00:00Z", true).qualifier).toBe("Approximate time");
  });

  it("does not expose raw response copy from timeline events", () => {
    const event = toTimelineEvent(maliciousTimeline().events[0]);

    expect(event.title).toBe("Linked alert context");
    expect(event.description).toBe("Read-only linked alert context derived from existing alert read data.");
    expect(event.title).not.toContain("raw customer");
    expect(event.description).not.toContain("scoreDetails");
  });

  it("maps bounded copy for known and unknown timeline events", () => {
    expect(timelineEventTitle("LINKED_ALERT_CONTEXT")).toBe("Linked alert context");
    expect(timelineEventDescription("LINKED_ALERT_CONTEXT")).toBe("Read-only linked alert context derived from existing alert read data.");
    expect(timelineEventTitle("ALERT_EVIDENCE_SNAPSHOT_PARTIAL")).toBe("Alert evidence snapshot partial");
    expect(timelineEventDescription("ALERT_EVIDENCE_SNAPSHOT_UNAVAILABLE")).toBe("Structured evidence snapshot was unavailable for this linked alert.");
    expect(timelineEventTitle("customer secret")).toBe("Timeline event");
    expect(timelineStateNotice("error")).toBe("Evidence timeline unavailable.");
  });

  it("renders only the allowed truncation reason", () => {
    expect(safeTruncationReason("TIMELINE_EVENT_LIMIT_EXCEEDED")).toBe("TIMELINE_EVENT_LIMIT_EXCEEDED");
    expect(safeTruncationReason("customer-secret raw reason")).toBe("Bounded timeline window reached");
  });
});

function renderSection({ timeline = availableTimeline(), error = false } = {}) {
  const getFraudCaseEvidenceTimeline = error
    ? vi.fn().mockRejectedValue(new Error("raw backend error"))
    : vi.fn().mockResolvedValue(timeline);

  render(
    <FraudCaseEvidenceTimelineSection
      caseId="case-1"
      getFraudCaseEvidenceTimeline={getFraudCaseEvidenceTimeline}
    />
  );

  return { getFraudCaseEvidenceTimeline };
}

function findSection() {
  return screen.findByTestId("fraud-case-evidence-timeline-section");
}

function sectionSource() {
  return readFileSync(resolve(process.cwd(), "src/components/FraudCaseEvidenceTimelineSection.jsx"), "utf8");
}

function displaySource() {
  return readFileSync(resolve(process.cwd(), "src/components/fraudCaseTimelineDisplay.js"), "utf8");
}
