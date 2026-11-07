import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { availableDetail } from "../transactions/transactionRiskIntelligenceFixtures.js";
import { WorkspaceRuntimeProvider } from "../workspace/WorkspaceRuntimeProvider.jsx";
import { TransactionRiskIntelligencePanel } from "./TransactionRiskIntelligencePanel.jsx";

describe("TransactionRiskIntelligencePanel API integration", () => {
  it.each(["correlationId", "riskLevel"])("fails closed when the API returns an invalid %s", async (field) => {
    const rawMarker = "PRIVATE_RAW_DETAIL_CANARY";
    const response = availableDetail({ [field]: { rawMarker } });
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(response)
    };

    render(<TransactionRiskIntelligencePanel transactionId={response.transactionId} apiClient={apiClient} />);

    expect(await screen.findByText(
      "The transaction risk intelligence response is malformed or missing required safety fields."
    )).toBeInTheDocument();
    await waitFor(() => expect(apiClient.getScoredTransactionDetail).toHaveBeenCalledTimes(1));

    expect(screen.queryByText(rawMarker)).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Transaction Summary" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant Investigation" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Engine Intelligence Status" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Analyst Feedback" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });

  it("redacts owner A's uncertain feedback before owner B loads the same transaction", async () => {
    const detail = availableDetail({ transactionId: "txn-shared-owner" });
    const apiClientA = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn().mockRejectedValue({ status: 404 }),
      createFraudFeedback: vi.fn().mockRejectedValue({
        status: 503,
        reasonCode: "FRAUD_FEEDBACK_COMMIT_OUTCOME_UNKNOWN"
      })
    };
    const apiClientB = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn().mockRejectedValue({ status: 404 }),
      createFraudFeedback: vi.fn()
    };
    const authProvider = { kind: "oidc" };
    let session = workspaceSession("analyst-a");
    let apiClient = apiClientA;
    const renderWorkspace = () => (
      <WorkspaceRuntimeProvider session={session} authProvider={authProvider}>
        <TransactionRiskIntelligencePanel
          transactionId={detail.transactionId}
          scoringOccurrenceToken={detail.scoringOccurrenceToken}
          apiClient={apiClient}
        />
      </WorkspaceRuntimeProvider>
    );
    const view = render(renderWorkspace());

    await waitFor(() => expect(screen.getByRole("button", { name: "Record feedback" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Record feedback" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Check recorded feedback" })).toBeInTheDocument());

    session = workspaceSession("analyst-b");
    apiClient = apiClientB;
    view.rerender(renderWorkspace());

    await waitFor(() => expect(apiClientB.getFraudFeedback).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("button", { name: "Record feedback" })).toBeInTheDocument());
    expect(screen.queryByText(/server may have recorded this feedback/i)).not.toBeInTheDocument();
    expect(apiClientA.createFraudFeedback).toHaveBeenCalledTimes(1);
    expect(apiClientB.createFraudFeedback).not.toHaveBeenCalled();
  });
});

function workspaceSession(userId) {
  return {
    userId,
    actorId: `actor-${userId}`,
    issuer: "issuer-1",
    subject: `subject-${userId}`,
    roles: [],
    authorities: ["transaction-monitor:read"]
  };
}
