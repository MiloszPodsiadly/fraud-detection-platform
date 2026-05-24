import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

describe("FraudCaseReadSurfaceCompositionDocsContractTest", () => {
  it("DocsMentionReadSurfaceCompositionCleanupTest", () => {
    const docs = compositionDocs();

    expect(docs).toContain("# FDP-80 - Fraud Case Read Surface Composition Cleanup");
    expect(docs).toContain("frontend-only composition cleanup");
    expect(docs).toContain("Compose read-only investigation context clearly.");
    expect(docs).toContain("Keep read-only sections separate.");
  });

  it("DocsMentionNoSmartInvestigationPanelTest", () => {
    const docs = compositionDocs();

    expect(docs).toContain("no combined smart InvestigationPanel");
    expect(docs).toContain("smart investigation panel");
  });

  it("DocsMentionWorkflowOutsideReadSurfaceTest", () => {
    const docs = compositionDocs();

    expect(docs).toContain("workflow/decision rail remains outside read-only surface");
    expect(docs).toContain("workflow and decision controls outside the read-only investigation context");
    expect(docs).toContain("does not redefine the full page as read-only");
  });

  it("DocsMentionNoNewBehaviorTest", () => {
    const docs = compositionDocs();

    expect(docs).toContain("no new rendered data");
    expect(docs).toContain("no new API calls");
    expect(docs).toContain("no new API client methods");
    expect(docs).toContain("does not add visible fields");
    expect(docs).toContain("product behavior");
  });

  it("DocsMentionNoTabsOrRedesignTest", () => {
    const docs = compositionDocs();

    expect(docs).toContain("no tabs");
    expect(docs).toContain("no accordion/collapse behavior");
    expect(docs).toContain("no redesign");
  });
});

function compositionDocs() {
  return readFileSync(join(process.cwd(), "../docs/product/fraud_case_read_surface_composition.md"), "utf8");
}
