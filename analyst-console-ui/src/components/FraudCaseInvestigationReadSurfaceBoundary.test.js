import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const READ_SURFACE_FILES = [
  "src/components/FraudCaseEvidenceSummarySection.jsx",
  "src/components/FraudCaseEvidenceTimelineSection.jsx"
];

const FORBIDDEN_SOURCE_REFERENCES = [
  "apiClient",
  "getAlert",
  "getSuspiciousTransaction",
  "getFraudCaseAudit",
  "updateFraudCase",
  "submitAnalystDecision",
  "closeCase",
  "reopenCase",
  "assignCase",
  "claimCase",
  "confirmFraud",
  "resolveCase",
  "JsonInspector",
  "RawPayload",
  "EvidenceDrilldown",
  "AlertDrilldown",
  "TimelineEditor",
  "JSON.stringify",
  "Kafka",
  "mutation",
  "workflow"
];

const FORBIDDEN_RAW_RENDER_REFERENCES = [
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
  "item.title",
  "item.description"
];

describe("FraudCase investigation read surface boundaries", () => {
  it("FraudCaseInvestigationReadSurfaceSectionsDoNotImportWorkflowOrDrilldownDependenciesTest", () => {
    for (const file of READ_SURFACE_FILES) {
      const source = readSource(file);

      for (const forbidden of FORBIDDEN_SOURCE_REFERENCES) {
        expect(source, `${file} must not contain ${forbidden}`).not.toContain(forbidden);
      }
    }
  });

  it("FraudCaseInvestigationReadSurfaceSectionsDoNotRenderRawBackendFieldsTest", () => {
    for (const file of READ_SURFACE_FILES) {
      const source = readSource(file);

      for (const forbidden of FORBIDDEN_RAW_RENDER_REFERENCES) {
        expect(source, `${file} must not contain ${forbidden}`).not.toContain(forbidden);
      }
    }
  });

  it("FraudCaseInvestigationReadSurfaceBoundaryGuardsAreSectionScopedTest", () => {
    const thisTest = readSource("src/components/FraudCaseInvestigationReadSurfaceBoundary.test.js");

    expect(thisTest).toContain("FraudCaseEvidenceSummarySection.jsx");
    expect(thisTest).toContain("FraudCaseEvidenceTimelineSection.jsx");
    expect(thisTest).not.toContain("FraudCase" + "DetailsPage.jsx");
  });
});

function readSource(relativePath) {
  return readFileSync(join(process.cwd(), relativePath), "utf8");
}
