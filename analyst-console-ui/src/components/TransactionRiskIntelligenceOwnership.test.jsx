import { act, render, screen, waitFor } from "@testing-library/react";
import { useLayoutEffect, useRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { TransactionRiskIntelligencePanel } from "./TransactionRiskIntelligencePanel.jsx";
import { availableDetail } from "../transactions/transactionRiskIntelligenceFixtures.js";
import { WorkspaceRuntimeContext } from "../workspace/useWorkspaceRuntime.js";

vi.mock("../transactions/useFraudFeedback.js", () => ({
  useFraudFeedback: () => ({
    feedback: null,
    isLoading: false,
    error: null,
    canReadFeedback: true,
    canCreateFeedback: true,
    submitState: "idle",
    submitError: null,
    submit: vi.fn()
  })
}));

describe("TransactionRiskIntelligencePanel ownership", () => {
  it("hides merchant A before passive effects when transaction B is selected and makes one request per transaction", async () => {
    const committedFrames = [];
    const first = deferred();
    const second = deferred();
    const apiClient = {
      getScoredTransactionDetail: vi.fn()
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise)
    };
    const { rerender } = render(
      <CommitProbe frames={committedFrames}>
        <TransactionRiskIntelligencePanel transactionId="txn-a" apiClient={apiClient} />
      </CommitProbe>
    );
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));
    await act(async () => first.resolve(availableDetail({
      transactionId: "txn-a",
      merchantInfo: { merchantName: "Merchant A" }
    })));
    expect(await screen.findByText("Merchant A")).toBeInTheDocument();

    committedFrames.length = 0;
    rerender(
      <CommitProbe frames={committedFrames}>
        <TransactionRiskIntelligencePanel transactionId="txn-b" apiClient={apiClient} />
      </CommitProbe>
    );

    expect(screen.queryByText("Merchant A")).not.toBeInTheDocument();
    expectMerchantAbsentFromEveryCommit(committedFrames, "Merchant A");
    expect(screen.getByText("Loading transaction risk intelligence...")).toBeInTheDocument();
    expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(2);
    await act(async () => second.resolve(availableDetail({
      transactionId: "txn-b",
      merchantInfo: { merchantName: "Merchant B" }
    })));
    expect(await screen.findByText("Merchant B")).toBeInTheDocument();
    expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(2);
  });

  it.each(["apiClient", "session", "authProvider"])("hides the previous merchant before passive effects when runtime %s changes", async (changedIdentity) => {
    const committedFrames = [];
    const nextRequest = deferred();
    const oldApiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(availableDetail({
        transactionId: "txn-same",
        merchantInfo: { merchantName: "Old session merchant" }
      }))
    };
    const nextApiClient = {
      getScoredTransactionDetail: vi.fn().mockReturnValue(nextRequest.promise)
    };
    const oldRuntime = {
      session: { userId: "analyst-old", issuer: "issuer-old" },
      authProvider: { kind: "oidc" },
      apiClient: oldApiClient
    };
    const { rerender } = render(
      <CommitProbe frames={committedFrames}>
        <WorkspaceRuntimeContext.Provider value={oldRuntime}>
          <TransactionRiskIntelligencePanel transactionId="txn-same" />
        </WorkspaceRuntimeContext.Provider>
      </CommitProbe>
    );
    expect(await screen.findByText("Old session merchant")).toBeInTheDocument();

    const nextRuntime = { ...oldRuntime };
    if (changedIdentity === "apiClient") {
      nextRuntime.apiClient = nextApiClient;
    } else {
      oldApiClient.getScoredTransactionDetail.mockReturnValue(nextRequest.promise);
      nextRuntime[changedIdentity] = changedIdentity === "session"
        ? { ...oldRuntime.session, issuer: "issuer-new" }
        : { kind: "oidc" };
    }
    committedFrames.length = 0;
    rerender(
      <CommitProbe frames={committedFrames}>
        <WorkspaceRuntimeContext.Provider value={nextRuntime}>
          <TransactionRiskIntelligencePanel transactionId="txn-same" />
        </WorkspaceRuntimeContext.Provider>
      </CommitProbe>
    );

    expect(screen.queryByText("Old session merchant")).not.toBeInTheDocument();
    expectMerchantAbsentFromEveryCommit(committedFrames, "Old session merchant");
    expect(nextRuntime.apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(changedIdentity === "apiClient" ? 1 : 2);
    await act(async () => nextRequest.resolve(availableDetail({
      transactionId: "txn-same",
      merchantInfo: { merchantName: "New session merchant" }
    })));
    expect(await screen.findByText("New session merchant")).toBeInTheDocument();
  });

  it("does not expose merchant data from a response for another transaction", async () => {
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(availableDetail({
        transactionId: "txn-wrong",
        merchantInfo: { merchantName: "Wrong transaction merchant" }
      }))
    };

    render(<TransactionRiskIntelligencePanel transactionId="txn-requested" apiClient={apiClient} />);

    expect(await screen.findByText(
      "The transaction risk intelligence response is malformed or missing required safety fields."
    )).toBeInTheDocument();
    expect(screen.queryByText("Wrong transaction merchant")).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
  });

  it.each([
    [400, "This transaction identifier is invalid."],
    [401, "You do not have permission to read this transaction risk intelligence."],
    [403, "You do not have permission to read this transaction risk intelligence."],
    [404, "Scored transaction not found."],
    [500, "Transaction risk intelligence is temporarily unavailable."],
    [503, "Transaction risk intelligence is temporarily unavailable."],
    ["network", "Transaction risk intelligence is temporarily unavailable."]
  ])("does not keep prior merchant data after a %s detail error", async (status, message) => {
    const oldApiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(availableDetail({
        transactionId: "txn-error",
        merchantInfo: { merchantName: "Prior merchant" }
      }))
    };
    const nextApiClient = {
      getScoredTransactionDetail: vi.fn().mockRejectedValue(status === "network"
        ? new Error("sensitive network detail")
        : { status, message: "sensitive server detail" })
    };
    const { rerender } = render(
      <TransactionRiskIntelligencePanel transactionId="txn-error" apiClient={oldApiClient} />
    );
    expect(await screen.findByText("Prior merchant")).toBeInTheDocument();

    rerender(<TransactionRiskIntelligencePanel transactionId="txn-error" apiClient={nextApiClient} />);

    expect(screen.queryByText("Prior merchant")).not.toBeInTheDocument();
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText("sensitive server detail")).not.toBeInTheDocument();
    expect(screen.queryByText("sensitive network detail")).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
  });

  it("hides prior merchant data when the transaction selection is cleared or access is disabled", async () => {
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(availableDetail({
        transactionId: "txn-cleared",
        merchantInfo: { merchantName: "Prior merchant" }
      }))
    };
    const { rerender } = render(
      <TransactionRiskIntelligencePanel transactionId="txn-cleared" apiClient={apiClient} />
    );
    expect(await screen.findByText("Prior merchant")).toBeInTheDocument();

    rerender(<TransactionRiskIntelligencePanel transactionId="" apiClient={apiClient} />);
    expect(screen.queryByText("Prior merchant")).not.toBeInTheDocument();
    expect(screen.getByText("No transaction selected.")).toBeInTheDocument();

    rerender(<TransactionRiskIntelligencePanel transactionId="txn-cleared" apiClient={apiClient} />);
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByText("Prior merchant")).toBeInTheDocument());
    rerender(<TransactionRiskIntelligencePanel transactionId="txn-cleared" enabled={false} apiClient={apiClient} />);

    expect(screen.queryByText("Prior merchant")).not.toBeInTheDocument();
    expect(screen.getByText("Transaction risk intelligence is not available without transaction read permission.")).toBeInTheDocument();
  });
});

function CommitProbe({ frames, children }) {
  const containerRef = useRef(null);
  useLayoutEffect(() => {
    frames.push(containerRef.current.textContent);
  });
  return <div ref={containerRef}>{children}</div>;
}

function expectMerchantAbsentFromEveryCommit(frames, merchantName) {
  expect(frames.length).toBeGreaterThan(0);
  for (const frame of frames) {
    expect(frame).not.toContain(merchantName);
  }
}

function deferred() {
  let resolve;
  const promise = new Promise((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}
