import { within } from "@testing-library/react";
import { expect } from "vitest";
import {
  maliciousInvestigationRawIdentifiers,
  maliciousInvestigationRawPayloadFields,
  maliciousInvestigationVerdictProofText,
  maliciousInvestigationWorkflowLabels
} from "./fraudCaseInvestigationReadSurfaceFixtures.js";

const ALLOWED_NEGATED_NON_CLAIMS = [
  "not confirmed fraud",
  "not an analyst decision",
  "not a final outcome",
  "not legal proof",
  "not an audit trail",
  "not complete case history"
];

export function expectNoInvestigationRawIdentifiers(section) {
  const rendered = sectionTextAndMarkup(section);
  for (const value of maliciousInvestigationRawIdentifiers()) {
    expect(rendered).not.toContain(value);
  }
}

export function expectNoInvestigationRawPayloads(section) {
  const rendered = sectionTextAndMarkup(section);
  for (const value of maliciousInvestigationRawPayloadFields()) {
    expect(rendered).not.toContain(value);
  }
}

export function expectNoInvestigationMutationControls(section) {
  expect(within(section).queryByRole("button")).not.toBeInTheDocument();
  expect(within(section).queryByRole("link")).not.toBeInTheDocument();
  expect(within(section).queryByRole("form")).not.toBeInTheDocument();
  expect(within(section).queryByRole("textbox")).not.toBeInTheDocument();
  expect(within(section).queryByRole("combobox")).not.toBeInTheDocument();
  expectSectionTextExcludes(section, [
    "Submit",
    "Save",
    "Confirm",
    "Dismiss",
    "Resolve",
    "Close case",
    "Reopen case",
    "Assign case",
    "Claim case",
    "Link case",
    "Create case",
    "Update case",
    "Edit evidence",
    "Create evidence"
  ]);
}

export function expectNoInvestigationWorkflowControls(section) {
  expectSectionTextExcludes(section, [
    ...maliciousInvestigationWorkflowLabels(),
    "Analyst decision",
    "Decision rail",
    "Final outcome",
    "Case decision",
    "Fraud confirmed",
    "False positive",
    "Escalate",
    "Case lifecycle"
  ]);
}

export function expectNoInvestigationDrilldowns(section) {
  expect(within(section).queryByRole("link")).not.toBeInTheDocument();
  expectSectionTextExcludes(section, [
    "View raw",
    "View JSON",
    "Open alert",
    "View alert",
    "View evidence",
    "Evidence drilldown",
    "Alert drilldown",
    "Raw payload",
    "JSON inspector"
  ]);
}

export function expectNoInvestigationVerdictProofWording(section) {
  let text = String(section.textContent ?? "").toLowerCase();
  for (const allowedPhrase of ALLOWED_NEGATED_NON_CLAIMS) {
    text = text.replaceAll(allowedPhrase, "");
  }

  for (const value of maliciousInvestigationVerdictProofText()) {
    expect(text).not.toContain(value.toLowerCase());
  }
}

export function expectReadOnlyInvestigationHelperVisible(section, expectedText) {
  expect(within(section).getByText(expectedText)).toBeInTheDocument();
}

function sectionTextAndMarkup(section) {
  return `${section.textContent ?? ""}\n${section.innerHTML ?? ""}`;
}

function expectSectionTextExcludes(section, values) {
  const text = String(section.textContent ?? "");
  for (const value of values) {
    expect(text).not.toContain(value);
  }
}
