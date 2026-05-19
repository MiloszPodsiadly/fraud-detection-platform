import { render } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it, vi } from "vitest";
import { SuspiciousTransactionWorkspacePage } from "./SuspiciousTransactionWorkspacePage.jsx";

const pageSource = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "SuspiciousTransactionWorkspacePage.jsx"), "utf8");

describe("SuspiciousTransaction UI no-workflow terms guard", () => {
  it("sourceKeepsOnlyAllowedSafetyDisclaimersForWorkflowTerms", () => {
    expect(forbiddenAffirmativeTerms(extractUserFacingStrings(pageSource))).toEqual([]);
  });

  it("renderedOutputKeepsOnlyAllowedSafetyDisclaimersForWorkflowTerms", () => {
    const { container: listContainer } = render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ items: [suspiciousTransaction()] })}
        canReadSuspiciousTransactions
        onOpenSuspiciousTransaction={vi.fn()}
      />
    );
    const { container: detailContainer } = render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ detail: suspiciousTransaction() })}
        canReadSuspiciousTransactions
        selectedSuspiciousTransactionId="suspicious-1"
        onCloseSuspiciousTransaction={vi.fn()}
      />
    );

    expect(forbiddenAffirmativeTerms(`${listContainer.textContent} ${detailContainer.textContent}`)).toEqual([]);
  });
});

function forbiddenAffirmativeTerms(text) {
  const normalized = ALLOWED_NEGATED_PHRASES.reduce(
    (current, phrase) => current.replaceAll(phrase.toLowerCase(), ""),
    text.toLowerCase()
  );
  return FORBIDDEN_TERMS.filter((term) => normalized.includes(term));
}

function extractUserFacingStrings(source) {
  return Array.from(source.matchAll(/>([^<>{}]+)</g))
    .map((match) => match[1])
    .concat(Array.from(source.matchAll(/"([^"]+)"/g)).map((match) => match[1]))
    .join(" ");
}

function readViewState(overrides = {}) {
  return {
    items: [],
    slice: { content: [], size: 20, hasNext: false, nextCursor: null },
    isLoadingList: false,
    listError: null,
    detail: null,
    isLoadingDetail: false,
    detailError: null,
    refreshList: vi.fn(),
    loadNext: vi.fn(),
    refreshDetail: vi.fn(),
    ...overrides
  };
}

function suspiciousTransaction() {
  return {
    suspiciousTransactionId: "suspicious-1",
    transactionId: "txn-1",
    sourceEventId: "event-1",
    correlationId: "corr-1",
    customerId: "customer-1",
    accountId: "account-1",
    riskScore: 0.94,
    riskLevel: "CRITICAL",
    detectionSource: "SCORING",
    reasonCodes: ["HIGH_AMOUNT_ACTIVITY"],
    evidenceStatus: "AVAILABLE",
    evidenceSnapshotItemCount: 2,
    evidenceProjectionState: "PROJECTED",
    linkedAlertId: "alert-1",
    status: "NEW",
    detectedAt: "2026-05-16T12:00:00Z",
    createdAt: "2026-05-16T12:00:00Z",
    updatedAt: "2026-05-16T12:00:00Z",
    scoreDecisionId: "score-1",
    scoringStrategy: "rules",
    modelName: "fraud-model",
    modelVersion: "2026-05"
  };
}

const ALLOWED_NEGATED_PHRASES = [
  "Not confirmed fraud",
  "Not an analyst decision",
  "Not a final outcome",
  "Not legal proof",
  "No case lifecycle mutation",
  "No analyst workflow"
];

const FORBIDDEN_TERMS = [
  "confirm fraud",
  "confirmed fraud action",
  "dismiss",
  "mark legitimate",
  "decide",
  "decision submit",
  "link case",
  "create case",
  ["as", "sign"].join(""),
  ["cl", "aim"].join(""),
  "close",
  "reopen",
  "resolve",
  "escalate",
  ["ex", "port"].join(""),
  ["bu", "lk action"].join(""),
  "final decision",
  "analyst outcome",
  "case decision",
  "legal proof"
];
