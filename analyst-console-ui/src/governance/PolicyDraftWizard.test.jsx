import { describe, expect, it } from "vitest";
import { emptyPolicyDraft, normalizedPolicyDraft, validExactDecimal, validatePolicyDraft } from "./PolicyDraftWizard.jsx";

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
      "FALSE_POSITIVE_RATE: maximumRate needs a plain, nonnegative exact decimal (up to 34 digits and 24 fractional places).");
  });

  it("rejects an approver missing from the authorized scoped directory", () => {
    const draft = { ...emptyPolicyDraft(schema), intendedApproverActorId: "oidc:alien" };
    expect(validatePolicyDraft(draft, schema, [])).toContain(
      "Select a currently eligible independent approver.");
  });

  it("sends exact decimal text to the server without Number conversion", () => {
    const value = "0.12345678901234567890";
    const draft = emptyPolicyDraft(schema);
    draft.criteria[0].thresholds.maximumRate = value;
    expect(normalizedPolicyDraft(draft).criteria[0].thresholds.maximumRate).toBe(value);
    for (const valid of [value, "0", "1", "0.000000000000000000000001", "1234567890123456789012345678901234"])
      expect(validExactDecimal(valid)).toBe(true);
    for (const invalid of ["-0", "-1", "1.1e-2", "1,2", "", "NaN", "Infinity",
      "0.0000000000000000000000001", "12345678901234567890123456789012345"])
      expect(validExactDecimal(invalid)).toBe(false);
  });
});
