import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

describe("FraudCaseInvestigationReadSurfaceDocsContractTest", () => {
  it("DocsMentionInvestigationReadSurfacesAreSectionScopedTest", () => {
    const docs = contractDocs();

    expect(docs).toContain("Evidence Summary");
    expect(docs).toContain("Evidence Timeline");
    expect(docs).toContain("section-scoped");
    expect(docs).toContain("not the entire FraudCaseDetailsPage");
    expect(docs).not.toContain("whole FraudCaseDetailsPage is read-only");
  });

  it("DocsMentionNoBackendChangesTest", () => {
    const docs = contractDocs();

    expect(docs).toContain("no backend changes");
    expect(docs).toContain("no DTO changes");
    expect(docs).toContain("no endpoint changes");
    expect(docs).toContain("no new authority");
  });

  it("DocsMentionNoFullApiClientTest", () => {
    const docs = contractDocs();

    expect(docs).toContain("narrow fetch function");
    expect(docs).toContain("not full apiClient");
    expect(docs).toContain("no new API client methods");
  });

  it("DocsMentionNoRawPayloadsOrIdentifiersTest", () => {
    const docs = contractDocs();

    expect(docs).toContain("avoid raw identifiers");
    expect(docs).toContain("avoid raw payloads");
    expect(docs).toContain("avoid raw backend title/description");
  });

  it("DocsMentionNoWorkflowOrDecisionControlsTest", () => {
    const docs = contractDocs();

    expect(docs).toContain("avoid workflow controls");
    expect(docs).toContain("avoid analyst decision controls");
    expect(docs).toContain("avoid final outcome/proof/verdict wording");
  });

  it("DocsMentionNoCombinedSmartInvestigationPanelTest", () => {
    const docs = contractDocs();

    expect(docs).toContain("no combined smart InvestigationPanel");
  });

  it("DocsDoNotDriftIntoPositiveProofClaimsTest", () => {
    let docs = contractDocs().toLowerCase();
    for (const allowed of [
      "do not mutate, decide, prove",
      "final outcome/proof/verdict wording",
      "proof of fraud",
      "fraud verdict",
      "final outcome",
      "legal proof",
      "audit trail",
      "complete case history"
    ]) {
      docs = docs.replaceAll(allowed, "");
    }

    expect(docs).not.toContain("confirmed fraud");
    expect(docs).not.toContain("legal proof");
    expect(docs).not.toContain("audit trail");
    expect(docs).not.toContain("complete case history");
  });
});

function contractDocs() {
  return readFileSync(join(process.cwd(), "../docs/product/fraud_case_investigation_read_surface_contract.md"), "utf8");
}
