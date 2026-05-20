import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SuspiciousTransactionWorkspacePage } from "./SuspiciousTransactionWorkspacePage.jsx";

describe("SuspiciousTransactionWorkspacePage", () => {
  it("renders system-signal semantics and cursor navigation without totals", () => {
    const onOpenSuspiciousTransaction = vi.fn();
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({
          items: [suspiciousTransaction()],
          slice: { content: [suspiciousTransaction()], size: 20, hasNext: true, nextCursor: "cursor-2" }
        })}
        canReadSuspiciousTransactions
        onOpenSuspiciousTransaction={onOpenSuspiciousTransaction}
      />
    );

    expect(screen.getByText(/System-detected suspicious signal/i)).toBeInTheDocument();
    expect(screen.getByText(/Not confirmed fraud/i)).toBeInTheDocument();
    expect(screen.getByText(/Not an analyst decision/i)).toBeInTheDocument();
    expect(screen.getByText(/Not a final outcome/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Load next" })).toBeInTheDocument();
    expect(screen.queryByText(/total pages/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Page 1/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/total suspicious transactions/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/showing .* of/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "View" }));

    expect(onOpenSuspiciousTransaction).toHaveBeenCalledWith("suspicious-1");
  });

  it("suspiciousTransactionListDoesNotUseSummaryAsPaginationTotal", () => {
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({
          items: [suspiciousTransaction()],
          slice: { content: [suspiciousTransaction()], size: 20, hasNext: false, nextCursor: null }
        })}
        canReadSuspiciousTransactions
        onOpenSuspiciousTransaction={vi.fn()}
      />
    );

    expect(screen.getByText("Loaded signals in this view: 1")).toBeInTheDocument();
    expect(screen.queryByText(/totalSuspiciousTransactions/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/total pages/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/last page/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /go to page/i })).not.toBeInTheDocument();
  });

  it("suspiciousTransactionListUsesOnlyCursorForLoadNext", () => {
    const loadNext = vi.fn();
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({
          items: [suspiciousTransaction()],
          slice: { content: [suspiciousTransaction()], size: 20, hasNext: true, nextCursor: "cursor-2" },
          loadNext
        })}
        canReadSuspiciousTransactions
        onOpenSuspiciousTransaction={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Load next" }));

    expect(loadNext).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/page number/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/offset/i)).not.toBeInTheDocument();
  });

  it("renders detail as read-only metadata without workflow actions", () => {
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ detail: suspiciousTransaction() })}
        canReadSuspiciousTransactions
        selectedSuspiciousTransactionId="suspicious-1"
        onCloseSuspiciousTransaction={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Suspicious transaction signal detail" })).toBeInTheDocument();
    expect(screen.getByText("Evidence metadata")).toBeInTheDocument();
    expect(screen.getByText("Linked alert ID")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /confirm/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /dismiss/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /submit/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/legal proof/i)).not.toBeInTheDocument();
  });

  it("linkedAlertIdIsRenderedAsReferenceOnly", () => {
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ detail: suspiciousTransaction({ linkedAlertId: "alert-reference-1" }) })}
        canReadSuspiciousTransactions
        selectedSuspiciousTransactionId="suspicious-1"
        onCloseSuspiciousTransaction={vi.fn()}
      />
    );

    expect(screen.getByText("Linked alert ID")).toBeInTheDocument();
    expect(screen.getByText("alert-reference-1")).toBeInTheDocument();
    expect(screen.getByText("Reference view")).toBeInTheDocument();
  });

  it("linkedAlertIdDoesNotRenderLinkCaseButton", () => {
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ detail: suspiciousTransaction() })}
        canReadSuspiciousTransactions
        selectedSuspiciousTransactionId="suspicious-1"
        onCloseSuspiciousTransaction={vi.fn()}
      />
    );

    expect(screen.queryByRole("button", { name: /link case/i })).not.toBeInTheDocument();
  });

  it("linkedAlertIdDoesNotRenderCreateCaseButton", () => {
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ detail: suspiciousTransaction() })}
        canReadSuspiciousTransactions
        selectedSuspiciousTransactionId="suspicious-1"
        onCloseSuspiciousTransaction={vi.fn()}
      />
    );

    expect(screen.queryByRole("button", { name: /create case/i })).not.toBeInTheDocument();
  });

  it("linkedAlertIdDoesNotRenderOpenWorkflowAction", () => {
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ detail: suspiciousTransaction() })}
        canReadSuspiciousTransactions
        selectedSuspiciousTransactionId="suspicious-1"
        onCloseSuspiciousTransaction={vi.fn()}
      />
    );

    expect(screen.queryByRole("button", { name: /open workflow/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/case lifecycle mutation/i)).toBeInTheDocument();
  });

  it("linkedAlertIdDoesNotTriggerMutationClientCall", () => {
    const mutationClientCall = vi.fn();
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState({ detail: suspiciousTransaction() })}
        canReadSuspiciousTransactions
        selectedSuspiciousTransactionId="suspicious-1"
        onCloseSuspiciousTransaction={vi.fn()}
      />
    );

    expect(screen.getByText("alert-1")).toBeInTheDocument();
    expect(mutationClientCall).not.toHaveBeenCalled();
  });

  it("shows an access denied panel when the frontend authority hint is false", () => {
    render(
      <SuspiciousTransactionWorkspacePage
        readViewState={readViewState()}
        canReadSuspiciousTransactions={false}
      />
    );

    expect(screen.getByRole("heading", { name: "Access denied" })).toBeInTheDocument();
  });
});

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

function suspiciousTransaction(overrides = {}) {
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
    modelVersion: "2026-05",
    ...overrides
  };
}
