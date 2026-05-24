import { describe, expect, it } from "vitest";
import {
  expectNoInvestigationRawIdentifiers,
  expectNoInvestigationVerdictProofWording,
  expectNoInvestigationWorkflowControls
} from "./fraudCaseInvestigationReadSurfaceAssertions.js";

describe("fraudCaseInvestigationReadSurfaceAssertions", () => {
  it("rejects lowercase workflow labels", () => {
    expect(() => expectNoInvestigationWorkflowControls(container("close case"))).toThrow();
    expect(() => expectNoInvestigationWorkflowControls(container("submit decision"))).toThrow();
    expect(() => expectNoInvestigationWorkflowControls(container("confirm fraud"))).toThrow();
  });

  it("rejects mixed-case workflow labels", () => {
    expect(() => expectNoInvestigationWorkflowControls(container("cLoSe CaSe"))).toThrow();
    expect(() => expectNoInvestigationWorkflowControls(container("sUbMiT dEcIsIoN"))).toThrow();
  });

  it("rejects uppercase raw-ID-shaped values", () => {
    expect(() => expectNoInvestigationRawIdentifiers(container("CUSTOMER_123456"))).toThrow();
    expect(() => expectNoInvestigationRawIdentifiers(container("CORRELATION_ID_ABC123"))).toThrow();
    expect(() => expectNoInvestigationRawIdentifiers(container("SOURCE_EVENT_20260523_ABC"))).toThrow();
  });

  it("allows negated helper wording", () => {
    expect(() => expectNoInvestigationVerdictProofWording(container([
      "not confirmed fraud",
      "not an analyst decision",
      "not a final outcome",
      "not legal proof"
    ].join(" ")))).not.toThrow();
  });

  it("rejects positive proof and verdict wording", () => {
    expect(() => expectNoInvestigationVerdictProofWording(container("confirmed fraud"))).toThrow();
    expect(() => expectNoInvestigationVerdictProofWording(container("final outcome"))).toThrow();
    expect(() => expectNoInvestigationVerdictProofWording(container("proof of fraud"))).toThrow();
  });
});

function container(html) {
  const element = document.createElement("section");
  element.innerHTML = html;
  return element;
}
