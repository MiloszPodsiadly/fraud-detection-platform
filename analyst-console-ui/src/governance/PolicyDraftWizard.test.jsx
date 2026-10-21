import { describe, expect, it } from "vitest";
import { emptyPolicyDraft, validatePolicyDraft } from "./PolicyDraftWizard.jsx";

const schema = [{ criterion: "FALSE_POSITIVE_RATE", requiredThresholdKeys: ["maximumRate"],
  evidenceSource: "exact-evidence", metricSemantics: "false positives / negatives",
  populationDefinition: "labeled records", denominatorDefinition: "negative labels",
  nonWaivable: false }];

describe("institutional policy draft wizard", () => {
  it("starts with backend criterion structure but invents no metric, PASS result or approval", () => {
    const draft = emptyPolicyDraft(schema);
    expect(draft.criteria).toHaveLength(1);
    expect(draft.criteria[0].thresholds).toEqual({ maximumRate: "" });
    expect(draft.criteria[0].maximumEvidenceAgeSeconds).toBe("");
    expect(draft.intendedApproverActorId).toBe("");
    expect(draft.approvalReference).toBe("");
    expect(validatePolicyDraft(draft, schema, [])).toContain(
      "FALSE_POSITIVE_RATE: maximumRate needs a numeric policy limit.");
  });

  it("rejects an approver missing from the authorized scoped directory", () => {
    const draft = { ...emptyPolicyDraft(schema), intendedApproverActorId: "oidc:alien" };
    expect(validatePolicyDraft(draft, schema, [])).toContain(
      "Select a currently eligible independent approver.");
  });
});
