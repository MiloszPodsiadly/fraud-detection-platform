import { within } from "@testing-library/react";
import { expect } from "vitest";
import { EVIDENCE_SUMMARY_HELPER_TEXT } from "../components/FraudCaseEvidenceSummarySection.jsx";

export function expectNoRawEvidencePayloadText(container) {
  const text = container.textContent;
  for (const term of [
    "customer-secret",
    "account-secret",
    "txn-secret",
    "correlation-secret",
    "alert-secret",
    "source-event-secret",
    "raw-model-payload",
    "raw-event-payload",
    "scoreDetails",
    "featureSnapshot",
    "rawPayload",
    "attributes",
    "CONFIRMED_FRAUD"
  ]) {
    expect(text).not.toContain(term);
  }
}

export function expectNoForbiddenEvidenceSummaryWording(container) {
  let text = container.textContent.toLowerCase();
  for (const allowedPhrase of [
    "not confirmed fraud",
    "not an analyst decision",
    "not a final outcome",
    "not legal proof"
  ]) {
    text = text.replaceAll(allowedPhrase, "");
  }

  for (const term of [
    "proof of fraud",
    "fraud confirmed",
    "fraud verdict",
    "final decision",
    "case decision",
    "resolve",
    "confirm",
    "dismiss",
    "close",
    "reopen",
    "as" + "sign",
    "cl" + "aim",
    "submit",
    "approve",
    "reject",
    "escalate",
    "take action"
  ]) {
    expect(text).not.toContain(term);
  }
}

export function expectNoMutationControls(container) {
  expect(within(container).queryByRole("button")).not.toBeInTheDocument();
  expect(within(container).queryByRole("link")).not.toBeInTheDocument();
  expect(within(container).queryByRole("form")).not.toBeInTheDocument();
  expect(within(container).queryByRole("textbox")).not.toBeInTheDocument();
}

export function expectEvidenceSummaryNonClaimHelperVisible(container) {
  expect(within(container).getByText(EVIDENCE_SUMMARY_HELPER_TEXT)).toBeInTheDocument();
}
