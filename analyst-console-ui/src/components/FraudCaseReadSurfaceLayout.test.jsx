import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FraudCaseReadSurfaceLayout } from "./FraudCaseReadSurfaceLayout.jsx";

describe("FraudCaseReadSurfaceLayout", () => {
  it("FraudCaseReadSurfaceLayoutRendersSeparateChildSectionsTest", () => {
    render(
      <FraudCaseReadSurfaceLayout>
        <section data-testid="summary-child"><h2>Evidence summary</h2></section>
        <section data-testid="timeline-child"><h2>Evidence timeline</h2></section>
      </FraudCaseReadSurfaceLayout>
    );

    const layout = screen.getByTestId("fraud-case-read-surface-layout");

    expect(within(layout).getByTestId("summary-child")).toBeInTheDocument();
    expect(within(layout).getByTestId("timeline-child")).toBeInTheDocument();
    expect(within(layout).getByTestId("summary-child")).not.toBe(within(layout).getByTestId("timeline-child"));
  });

  it("FraudCaseReadSurfaceLayoutAccessibilityLabelIsBoundedAndNotVisibleProductContentTest", () => {
    render(
      <FraudCaseReadSurfaceLayout>
        <section><h2>Evidence summary</h2></section>
      </FraudCaseReadSurfaceLayout>
    );

    const layout = screen.getByLabelText("Read-only investigation context");

    expect(layout).toBe(screen.getByTestId("fraud-case-read-surface-layout"));
    expect(layout.getAttribute("aria-label")).toBe("Read-only investigation context");
    expect(layout.textContent).not.toContain("Read-only investigation context");
    expect(within(layout).queryByRole("button")).not.toBeInTheDocument();
    expect(within(layout).queryByRole("link")).not.toBeInTheDocument();
    expect(within(layout).queryByRole("form")).not.toBeInTheDocument();
    expect(within(layout).queryByRole("tab")).not.toBeInTheDocument();
  });

  it("FraudCaseReadSurfaceLayoutDoesNotFetchTest", () => {
    const source = readLayoutSource();

    expect(source).not.toContain("fetch");
    expect(source).not.toContain("useEffect");
    expect(source).not.toContain("useState");
  });

  it("FraudCaseReadSurfaceLayoutDoesNotImportApiClientTest", () => {
    const source = readLayoutSource();

    for (const forbidden of [
      "apiClient",
      "getFraudCaseEvidenceSummary",
      "getFraudCaseEvidenceTimeline",
      "getAlert",
      "getSuspiciousTransaction"
    ]) {
      expect(source).not.toContain(forbidden);
    }
  });

  it("FraudCaseReadSurfaceLayoutIsPresentationalOnlyTest", () => {
    const source = readLayoutSource();

    for (const forbidden of [
      "apiClient",
      "getFraudCaseEvidenceSummary",
      "getFraudCaseEvidenceTimeline",
      "getAlert",
      "getSuspiciousTransaction",
      "updateFraudCase",
      "submitAnalystDecision",
      "closeCase",
      "reopenCase",
      "as" + "signCase",
      "cl" + "aimCase",
      "workflow",
      "mutation",
      "decision",
      "finalOutcome",
      "JsonInspector",
      "RawPayload",
      "EvidenceDrilldown",
      "AlertDrilldown",
      "fetch",
      "useEffect",
      "useState",
      "<but" + "ton",
      "<a ",
      "<form",
      "tablist",
      "role=\"tab\"",
      "raw payload",
      "raw identifiers"
    ]) {
      expect(source).not.toContain(forbidden);
    }
  });
});

function readLayoutSource() {
  return readFileSync(resolve(process.cwd(), "src/components/FraudCaseReadSurfaceLayout.jsx"), "utf8");
}
