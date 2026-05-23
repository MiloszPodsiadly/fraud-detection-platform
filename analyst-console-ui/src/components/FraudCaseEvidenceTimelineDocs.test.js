import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

describe("Fraud Case Evidence Timeline UI docs", () => {
  it("DocsMentionTimelineUiIsNotAuditTrailTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("not an audit trail");
    expect(docs).toContain("Do not reinterpret backend chronology as audit or lifecycle history.");
  });

  it("DocsMentionTimelineUiDoesNotRenderEventKeyTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("`eventKey` is used only as a React key");
    expect(docs).toContain("It must not be rendered in visible text, tooltip, aria-label, title, URL, copy text, persistent ID, or test ID.");
  });

  it("DocsMentionTimelineUiDoesNotClaimLinkTimeTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("Do not render:");
    expect(docs).toContain("linked at");
    expect(docs).toMatch(/It is not proof of the time when an\s+alert was linked to the fraud case\./);
  });

  it("DocsMentionTimelineUiNoRawIdentifiersTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("no raw identifier rendering");
    expect(docs).toContain("No raw identifiers rendering.");
  });

  it("DocsMentionTimelineUiNoDrilldownsTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("no alert drilldown");
    expect(docs).toContain("no evidence drilldown");
    expect(docs).toContain("No JSON inspector.");
  });

  it("DocsMentionTimelineUiUsesBehaviorContractLanguageTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("Status: current FDP-77 UI behavior contract.");
    expect(docs).not.toContain("Status: current product UI source of truth.");
  });

  it("DocsMentionTimelineUiFieldSpecificAllowlistsTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("field-specific allowlists");
    expect(docs).toContain("event-type allowlist matches the FDP-76 backend enum");
    expect(docs).toContain("LINKED_ALERT_CONTEXT");
    expect(docs).toContain("Uppercase raw-ID-shaped values");
  });

  it("DocsMentionTimelineUiEmptyStateFlagsAndRenderCapTest", () => {
    const docs = timelineDocs();

    expect(docs).toContain("Empty timelines still display any backend state");
    expect(docs).toContain("defensive render cap of 100 events");
    expect(docs).toContain("Timeline display capped. Only the bounded timeline window is shown.");
  });
});

function timelineDocs() {
  return readFileSync(join(process.cwd(), "../docs/product/fraud_case_evidence_timeline_ui.md"), "utf8");
}
