import { within } from "@testing-library/react";
import { expect } from "vitest";
import { EVIDENCE_TIMELINE_HELPER_TEXT } from "../components/FraudCaseEvidenceTimelineSection.jsx";

export function expectNoRawTimelineIdentifiers(section) {
  const text = section.textContent;
  for (const term of [
    "SECRET_EVENT_KEY_SHOULD_NOT_RENDER",
    "alert-secret",
    "linked-alert-secret",
    "txn-secret",
    "customer-secret",
    "account-secret",
    "correlation-secret",
    "source-event-secret",
    "evidence-secret",
    "score-decision-secret"
  ]) {
    expect(text).not.toContain(term);
  }
}

export function expectNoRawTimelinePayloads(section) {
  const text = section.textContent;
  for (const term of [
    "payload-secret",
    "scoreDetails",
    "featureSnapshot",
    "rawPayload",
    "raw customer",
    "raw payload",
    "CONFIRMED_FRAUD",
    "analystDecision"
  ]) {
    expect(text).not.toContain(term);
  }
}

export function expectNoTimelineForbiddenWording(section) {
  let text = section.textContent.toLowerCase();
  for (const allowedPhrase of [
    "not an audit trail",
    "not complete case history",
    "not confirmed fraud",
    "not an analyst decision",
    "not a final outcome",
    "not legal proof"
  ]) {
    text = text.replaceAll(allowedPhrase, "");
  }

  for (const term of [
    "audit trail",
    "audit history",
    "legal record",
    "complete history",
    "complete case history",
    "lifecycle history",
    "case activity",
    "linked at",
    "alert linked at",
    "official time",
    "recorded at",
    "confirmed fraud",
    "fraud verdict",
    "proof of fraud",
    "legal proof",
    "final decision",
    "final outcome",
    "analyst decision",
    "case decision",
    "resolved",
    "closed",
    "as" + "signed",
    "cl" + "aimed"
  ]) {
    expect(text).not.toContain(term);
  }
}

export function expectNoTimelineMutationControls(section) {
  expect(within(section).queryByRole("button")).not.toBeInTheDocument();
  expect(within(section).queryByRole("link")).not.toBeInTheDocument();
  expect(within(section).queryByRole("form")).not.toBeInTheDocument();
  expect(within(section).queryByRole("textbox")).not.toBeInTheDocument();
}

export function expectTimelineNonClaimHelperVisible(section) {
  expect(within(section).getByText(EVIDENCE_TIMELINE_HELPER_TEXT)).toBeInTheDocument();
}
