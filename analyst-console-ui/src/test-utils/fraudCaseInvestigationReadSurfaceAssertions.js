import { within } from "@testing-library/react";
import { expect } from "vitest";
import {
  maliciousInvestigationRawIdentifiers,
  maliciousInvestigationRawPayloadFields,
  maliciousInvestigationVerdictProofText,
  maliciousInvestigationWorkflowLabels
} from "./fraudCaseInvestigationReadSurfaceFixtures.js";

const ALLOWED_NEGATED_HELPER_TEXT = [
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
    "As" + "sign case",
    "Cl" + "aim case",
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
  const text = stripAllowedNegatedHelperText(String(section.textContent ?? "").toLowerCase());

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

function sectionTextAndMarkupLower(section) {
  return sectionTextAndMarkup(section).toLowerCase();
}

function expectSectionTextExcludes(section, values) {
  const text = stripAllowedNegatedHelperText(sectionTextAndMarkupLower(section));
  for (const value of values) {
    expect(text).not.toMatch(forbiddenPhrasePattern(value));
  }
}

function stripAllowedNegatedHelperText(text) {
  let sanitized = text;
  for (const allowedPhrase of ALLOWED_NEGATED_HELPER_TEXT) {
    sanitized = sanitized.replaceAll(allowedPhrase, "");
  }
  return sanitized;
}

function forbiddenPhrasePattern(value) {
  return new RegExp(`\\b${escapeRegex(String(value).toLowerCase())}\\b`);
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
