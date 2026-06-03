import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("EngineIntelligenceAnalystUiDisplayDocsTest", () => {
  it("documentsFdp97ReadOnlyDiagnosticUiScope", () => {
    const docs = readFileSync(resolve(process.cwd(), "../docs/architecture/engine_intelligence_analyst_ui_display.md"), "utf8");

    expect(docs).toContain("## Purpose");
    expect(docs).toContain("## Scope");
    expect(docs).toContain("## Non-goals");
    expect(docs).toContain("## API Dependency");
    expect(docs).toContain("## UI Placement");
    expect(docs).toContain("## State Matrix");
    expect(docs).toContain("## Operational Wording");
    expect(docs).toContain("## No Decisioning Wording");
    expect(docs).toContain("## No Raw/Internal Rendering");
    expect(docs).toContain("## No Feedback Workflow");
    expect(docs).toContain("## No Case-Level Aggregation");
    expect(docs).toContain("## Accessibility");
    expect(docs).toContain("## Future FDP-98 Feedback Workflow");
    expect(docs).toContain("FDP-97 displays bounded transaction-level engine intelligence from the FDP-96 API.");
    expect(docs).toContain("FDP-97 is read-only diagnostic UI.");
    expect(docs).toContain("FDP-97 does not add feedback workflow.");
    expect(docs).toContain("FDP-97 does not add analyst actions.");
    expect(docs).toContain("FDP-97 does not add approve/decline/block.");
    expect(docs).toContain("FDP-97 does not add final decisioning.");
    expect(docs).toContain("FDP-97 does not change alert severity.");
    expect(docs).toContain("FDP-97 does not change fraud case status.");
    expect(docs).toContain("FDP-97 does not add case-level aggregation.");
    expect(docs).toContain("UI consumes only `GET /api/v1/transactions/scored/{transactionId}/engine-intelligence`.");
    expect(docs).toContain("TIMEOUT/UNAVAILABLE/DEGRADED are operational statuses, not LOW risk.");
    expect(docs).toContain("UI must not render raw error bodies or internal fields.");
    expect(docs).toContain("UI wording must avoid final/recommended/winning/safe/approve/decline/block.");
    expect(docs).toContain("FDP-98 is the future feedback workflow branch.");
  });
});
