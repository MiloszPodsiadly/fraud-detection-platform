import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { boundedEvidenceDescription, boundedEvidenceTitleForType } from "./evidenceSummaryCopy.js";

describe("evidenceSummaryCopy", () => {
  it.each([
    ["MODEL_SIGNAL", "Model signal"],
    ["RULE_MATCH", "Rule evidence"],
    ["PROFILE_DEVIATION", "Profile deviation evidence"],
    ["VELOCITY_CHECK", "Velocity evidence"],
    ["LINK_ANALYSIS", "Linked-entity evidence"],
    ["DEVICE_SIGNAL", "Device evidence"],
    ["LOCATION_SIGNAL", "Location evidence"],
    ["MERCHANT_SIGNAL", "Merchant evidence"],
    ["DIAGNOSTIC", "Diagnostic evidence"],
    ["UNKNOWN_TYPE", "Evidence item"],
    ["customer-secret account-secret txn-secret", "Evidence item"],
    ["raw-model-payload scoreDetails featureSnapshot", "Evidence item"]
  ])("boundedEvidenceTitleForType maps %s", (evidenceType, expected) => {
    expect(boundedEvidenceTitleForType(evidenceType)).toBe(expected);
  });

  it("boundedEvidenceDescription returns static bounded product copy", () => {
    expect(boundedEvidenceDescription("raw title", "raw description")).toBe(
      "Bounded evidence metadata derived from the fraud-case evidence summary."
    );
  });

  it("boundedEvidenceDescription does not include raw terms", () => {
    const description = boundedEvidenceDescription();

    for (const term of ["customer", "account", "transaction", "correlation", "raw payload"]) {
      expect(description).not.toContain(term);
    }
  });

  it("evidenceSummaryCopy source does not reference raw item fields", () => {
    const source = readFileSync(resolve(process.cwd(), "src/components/evidenceSummaryCopy.js"), "utf8");

    expect(source).not.toContain(".title");
    expect(source).not.toContain(".description");
    expect(source).not.toContain("rawPayload");
    expect(source).not.toContain("attributes");
  });
});
