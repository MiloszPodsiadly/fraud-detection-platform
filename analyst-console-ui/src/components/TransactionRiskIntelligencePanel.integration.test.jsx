import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AUTHORITIES, normalizeSession } from "../auth/session.js";
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

  it("lets READ_ONLY_ANALYST view recorded feedback without a submission form", async () => {
    const detail = availableDetail();
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn().mockResolvedValue(recordedFeedback(detail.transactionId)),
      createFraudFeedback: vi.fn()
    };

    renderWorkspace(detail, workspaceSession("readonly-analyst", "READ_ONLY_ANALYST"), apiClient);

    expect(await screen.findByRole("heading", { name: "Feedback recorded" })).toBeInTheDocument();
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(1);
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
    expect(screen.queryByRole("form")).not.toBeInTheDocument();
  });

  it("does not offer submission to READ_ONLY_ANALYST when feedback is not recorded yet", async () => {
    const detail = availableDetail();
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn().mockRejectedValue({ status: 404 }),
      createFraudFeedback: vi.fn()
    };

    renderWorkspace(detail, workspaceSession("readonly-empty", "READ_ONLY_ANALYST"), apiClient);

    expect(await screen.findByText("Analyst feedback is not available in this runtime.")).toBeInTheDocument();
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(1);
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });

  it.each(["ANALYST", "REVIEWER"])("lets %s read and submit feedback when preconditions pass", async (role) => {
    const detail = availableDetail();
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn().mockRejectedValue({ status: 404 }),
      createFraudFeedback: vi.fn().mockResolvedValue(recordedFeedback(detail.transactionId))
    };

    renderWorkspace(detail, workspaceSession(`user-${role.toLowerCase()}`, role), apiClient);

    await waitFor(() => expect(screen.getByRole("button", { name: "Record feedback" })).toBeInTheDocument());
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Record feedback" }));

    expect(await screen.findByRole("heading", { name: "Feedback recorded" })).toBeInTheDocument();
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
  });

  it("does not request feedback for a session with only transaction monitor read", async () => {
    const detail = availableDetail();
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn(),
      createFraudFeedback: vi.fn()
    };
    const session = normalizeSession({
      userId: "monitor-only",
      roles: [],
      extraAuthorities: [AUTHORITIES.TRANSACTION_MONITOR_READ]
    });

    renderWorkspace(detail, session, apiClient);

    expect(await screen.findByText("Analyst feedback is not available in this runtime.")).toBeInTheDocument();
    expect(apiClient.getFraudFeedback).not.toHaveBeenCalled();
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });

  it.each([
    ["unknown authority state", { userId: "unknown-user", roles: [], authorities: [] }],
    ["unauthenticated session", {
      userId: "",
      roles: ["ANALYST"],
      authorities: [AUTHORITIES.FRAUD_FEEDBACK_READ, AUTHORITIES.FRAUD_FEEDBACK_WRITE]
    }]
  ])("fails closed for an %s", async (_description, session) => {
    const detail = availableDetail();
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn(),
      createFraudFeedback: vi.fn()
    };

    renderWorkspace(detail, session, apiClient);

    expect(await screen.findByText("Analyst feedback is not available in this runtime.")).toBeInTheDocument();
    expect(apiClient.getFraudFeedback).not.toHaveBeenCalled();
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });

  it("redacts a pending GET response as soon as fraud feedback read permission is revoked", async () => {
    const detail = availableDetail();
    const pendingFeedback = deferred();
    let session = workspaceSession("read-revoked-user", "ANALYST");
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn().mockReturnValue(pendingFeedback.promise),
      createFraudFeedback: vi.fn()
    };
    const view = renderWorkspace(detail, session, apiClient);

    await waitFor(() => expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(1));
    session = normalizeSession({
      userId: "read-revoked-user",
      roles: [],
      extraAuthorities: [AUTHORITIES.TRANSACTION_MONITOR_READ]
    });
    view.rerender(renderWorkspaceElement(detail, session, apiClient));

    expect(await screen.findByText("Analyst feedback is not available in this runtime.")).toBeInTheDocument();
    await act(async () => {
      pendingFeedback.resolve(recordedFeedback(detail.transactionId));
      await pendingFeedback.promise;
    });

    expect(screen.queryByRole("heading", { name: "Feedback recorded" })).not.toBeInTheDocument();
    expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(1);
    expect(apiClient.createFraudFeedback).not.toHaveBeenCalled();
  });

  it("keeps an in-flight POST uncertain after write permission is revoked", async () => {
    const detail = availableDetail();
    const pendingPost = deferred();
    let session = workspaceSession("write-revoked-user", "ANALYST");
    const apiClient = {
      getScoredTransactionDetail: vi.fn().mockResolvedValue(detail),
      getFraudFeedback: vi.fn()
        .mockRejectedValueOnce({ status: 404 })
        .mockRejectedValueOnce({ status: 404 }),
      createFraudFeedback: vi.fn().mockReturnValue(pendingPost.promise)
    };
    const view = renderWorkspace(detail, session, apiClient);

    await waitFor(() => expect(screen.getByRole("button", { name: "Record feedback" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Record feedback" }));
    await waitFor(() => expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1));

    session = workspaceSession("write-revoked-user", "READ_ONLY_ANALYST");
    view.rerender(renderWorkspaceElement(detail, session, apiClient));

    expect(await screen.findByRole("button", { name: "Check recorded feedback" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
    await act(async () => {
      pendingPost.resolve(recordedFeedback(detail.transactionId, { feedbackId: "late-post-response" }));
      await pendingPost.promise;
    });
    expect(screen.queryByRole("heading", { name: "Feedback recorded" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Check recorded feedback" }));
    await waitFor(() => expect(apiClient.getFraudFeedback).toHaveBeenCalledTimes(2));
    expect(await screen.findByText(/earlier write outcome is still unknown/i)).toBeInTheDocument();
    expect(apiClient.createFraudFeedback).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "Record feedback" })).not.toBeInTheDocument();
  });
});

function workspaceSession(userId, role = "ANALYST") {
  return normalizeSession({
    userId,
    actorId: `actor-${userId}`,
    issuer: "issuer-1",
    subject: `subject-${userId}`,
    roles: [role]
  });
}

function renderWorkspace(detail, session, apiClient) {
  return render(renderWorkspaceElement(detail, session, apiClient));
}

function renderWorkspaceElement(detail, session, apiClient) {
  return (
    <WorkspaceRuntimeProvider session={session} authProvider={{ kind: "oidc" }}>
      <TransactionRiskIntelligencePanel
        transactionId={detail.transactionId}
        scoringOccurrenceToken={detail.scoringOccurrenceToken}
        apiClient={apiClient}
      />
    </WorkspaceRuntimeProvider>
  );
}

function recordedFeedback(transactionId, overrides = {}) {
  return {
    feedbackId: "feedback-1",
    transactionId,
    feedbackLabel: "CONFIRMED_FRAUD",
    analystDecision: "MARKED_FRAUD",
    labelSource: "ANALYST_REVIEW",
    feedbackStatus: "RECORDED",
    createdAt: "2026-11-07T10:00:00Z",
    createdBy: "analyst-1",
    decisionReasonCodes: ["ANALYST_CONFIRMED_FRAUD"],
    notesPresent: false,
    scoringOccurrenceRelation: "CURRENT",
    ...overrides
  };
}

function deferred() {
  let resolve;
  const promise = new Promise((complete) => { resolve = complete; });
  return { promise, resolve };
}
