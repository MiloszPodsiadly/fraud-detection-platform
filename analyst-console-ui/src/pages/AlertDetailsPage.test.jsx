import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AlertDetailsPage } from "./AlertDetailsPage.jsx";

describe("AlertDetailsPage", () => {
  it("ignores stale alert detail response after alertId changes", async () => {
    const firstAlert = deferred();
    const secondAlert = deferred();
    const apiClient = {
      getAlert: vi.fn()
        .mockReturnValueOnce(firstAlert.promise)
        .mockReturnValueOnce(secondAlert.promise),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("summary-current"))
    };
    const { rerender } = render(page({ alertId: "alert-old", apiClient }));
    await waitFor(() => expect(apiClient.getAlert).toHaveBeenCalledWith("alert-old", expect.objectContaining({ signal: expect.any(AbortSignal) })));

    rerender(page({ alertId: "alert-new", apiClient }));
    await waitFor(() => expect(apiClient.getAlert).toHaveBeenCalledWith("alert-new", expect.objectContaining({ signal: expect.any(AbortSignal) })));
    secondAlert.resolve(alertDetails("alert-new", "New alert reason"));
    await screen.findByText("New alert reason");
    firstAlert.resolve(alertDetails("alert-old", "Old alert reason"));

    await waitFor(() => expect(screen.queryByText("Old alert reason")).not.toBeInTheDocument());
    expect(screen.getByText("New alert reason")).toBeInTheDocument();
  });

  it("ignores stale assistant summary response after alertId changes", async () => {
    const firstSummary = deferred();
    const secondSummary = deferred();
    const apiClient = {
      getAlert: vi.fn()
        .mockResolvedValueOnce(alertDetails("alert-old", "Old alert reason"))
        .mockResolvedValueOnce(alertDetails("alert-new", "New alert reason")),
      getAssistantSummary: vi.fn()
        .mockReturnValueOnce(firstSummary.promise)
        .mockReturnValueOnce(secondSummary.promise)
    };
    const { rerender } = render(page({ alertId: "alert-old", apiClient }));
    await screen.findByText("Old alert reason");

    rerender(page({ alertId: "alert-new", apiClient }));
    await screen.findByText("New alert reason");
    secondSummary.resolve(summary("new summary"));
    await screen.findByText("new summary");
    firstSummary.resolve(summary("old summary"));

    await waitFor(() => expect(screen.queryByText("old summary")).not.toBeInTheDocument());
    expect(screen.getByText("new summary")).toBeInTheDocument();
  });

  it("does not show an error for aborted alert detail request", async () => {
    const apiClient = {
      getAlert: vi.fn().mockRejectedValue(new DOMException("aborted", "AbortError")),
      getAssistantSummary: vi.fn()
    };
    render(page({ alertId: "alert-1", apiClient }));

    await waitFor(() => expect(screen.queryByRole("heading", { name: "Unable to load data" })).not.toBeInTheDocument());
    expect(apiClient.getAssistantSummary).not.toHaveBeenCalled();
  });

  it("refetches with a new apiClient and ignores the old client response", async () => {
    const firstAlert = deferred();
    const apiClientA = {
      getAlert: vi.fn().mockReturnValue(firstAlert.promise),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("summary-a"))
    };
    const apiClientB = {
      getAlert: vi.fn().mockResolvedValue(alertDetails("alert-1", "Client B reason")),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("summary-b"))
    };
    const { rerender } = render(page({ alertId: "alert-1", apiClient: apiClientA }));
    await waitFor(() => expect(apiClientA.getAlert).toHaveBeenCalledTimes(1));

    rerender(page({ alertId: "alert-1", apiClient: apiClientB }));
    await waitFor(() => expect(apiClientB.getAlert).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("Client B reason")).toBeInTheDocument();
    firstAlert.resolve(alertDetails("alert-1", "Client A reason"));

    await waitFor(() => expect(screen.queryByText("Client A reason")).not.toBeInTheDocument());
  });

  it("aborts in-flight detail requests on unmount", async () => {
    const pendingAlert = deferred();
    const apiClient = {
      getAlert: vi.fn().mockReturnValue(pendingAlert.promise),
      getAssistantSummary: vi.fn()
    };
    const { unmount } = render(page({ alertId: "alert-1", apiClient }));
    await waitFor(() => expect(apiClient.getAlert).toHaveBeenCalledTimes(1));
    const signal = apiClient.getAlert.mock.calls[0][1].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("calls the dashboard callback only after a current decision refresh loads", async () => {
    const onDecisionSubmitted = vi.fn();
    const apiClient = {
      getAlert: vi.fn()
        .mockResolvedValueOnce(alertDetails("alert-1", "Open alert"))
        .mockResolvedValueOnce(alertDetails("alert-1", "Refreshed alert")),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("summary-current")),
      submitAnalystDecision: vi.fn().mockResolvedValue({ resultingStatus: "RESOLVED" })
    };
    render(page({ alertId: "alert-1", apiClient, onDecisionSubmitted }));
    await screen.findByText("Open alert");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed manually" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit decision" }));

    expect(await screen.findByText("Refreshed alert")).toBeInTheDocument();
    expect(onDecisionSubmitted).toHaveBeenCalledTimes(1);
  });

  it("does not call the dashboard callback when the post-decision refresh fails", async () => {
    const onDecisionSubmitted = vi.fn();
    const apiClient = {
      getAlert: vi.fn()
        .mockResolvedValueOnce(alertDetails("alert-1", "Open alert"))
        .mockRejectedValueOnce(new Error("refresh failed")),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("summary-current")),
      submitAnalystDecision: vi.fn().mockResolvedValue({ resultingStatus: "RESOLVED" })
    };
    render(page({ alertId: "alert-1", apiClient, onDecisionSubmitted }));
    await screen.findByText("Open alert");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed manually" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit decision" }));

    expect(await screen.findByText("refresh failed")).toBeInTheDocument();
    expect(onDecisionSubmitted).not.toHaveBeenCalled();
  });

  it("does not call the dashboard callback when a post-decision refresh becomes stale", async () => {
    const refresh = deferred();
    const onDecisionSubmitted = vi.fn();
    const apiClient = {
      getAlert: vi.fn()
        .mockResolvedValueOnce(alertDetails("alert-old", "Old alert"))
        .mockReturnValueOnce(refresh.promise)
        .mockResolvedValueOnce(alertDetails("alert-new", "New alert")),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("summary-current")),
      submitAnalystDecision: vi.fn().mockResolvedValue({ resultingStatus: "RESOLVED" })
    };
    const { rerender } = render(page({ alertId: "alert-old", apiClient, onDecisionSubmitted }));
    await screen.findByText("Old alert");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed manually" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit decision" }));
    await waitFor(() => expect(apiClient.getAlert).toHaveBeenCalledTimes(2));
    rerender(page({ alertId: "alert-new", apiClient, onDecisionSubmitted }));
    await screen.findByText("New alert");
    refresh.resolve(alertDetails("alert-old", "Stale refreshed alert"));

    await waitFor(() => expect(screen.queryByText("Stale refreshed alert")).not.toBeInTheDocument());
    expect(onDecisionSubmitted).not.toHaveBeenCalled();
  });

  it("clears old assistant summary while loading a different alert", async () => {
    const nextAlert = deferred();
    const apiClient = {
      getAlert: vi.fn()
        .mockResolvedValueOnce(alertDetails("alert-old", "Old alert"))
        .mockReturnValueOnce(nextAlert.promise),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("old summary"))
    };
    const { rerender } = render(page({ alertId: "alert-old", apiClient }));
    await screen.findByText("old summary");

    rerender(page({ alertId: "alert-new", apiClient }));

    await waitFor(() => expect(screen.queryByText("old summary")).not.toBeInTheDocument());
  });

  it("does not render assistant summary from a stale apiClient context", async () => {
    const staleSummary = deferred();
    const apiClientA = {
      getAlert: vi.fn().mockResolvedValue(alertDetails("alert-1", "Client A alert")),
      getAssistantSummary: vi.fn().mockReturnValue(staleSummary.promise)
    };
    const apiClientB = {
      getAlert: vi.fn().mockResolvedValue(alertDetails("alert-1", "Client B alert")),
      getAssistantSummary: vi.fn().mockResolvedValue(summary("current summary"))
    };
    const { rerender } = render(page({ alertId: "alert-1", apiClient: apiClientA }));
    await screen.findByText("Client A alert");

    rerender(page({ alertId: "alert-1", apiClient: apiClientB }));
    await screen.findByText("Client B alert");
    staleSummary.resolve(summary("stale summary"));

    await waitFor(() => expect(screen.queryByText("stale summary")).not.toBeInTheDocument());
    expect(screen.getByText("current summary")).toBeInTheDocument();
  });

  it("does not show an error for aborted assistant summary request", async () => {
    const apiClient = {
      getAlert: vi.fn().mockResolvedValue(alertDetails("alert-1", "Open alert")),
      getAssistantSummary: vi.fn().mockRejectedValue(new DOMException("aborted", "AbortError"))
    };
    render(page({ alertId: "alert-1", apiClient }));

    await screen.findByText("Open alert");

    await waitFor(() => expect(screen.queryByText("AbortError")).not.toBeInTheDocument());
    expect(screen.queryByText("aborted")).not.toBeInTheDocument();
  });
});

function page({ alertId, apiClient, onDecisionSubmitted = vi.fn() }) {
  return (
    <AlertDetailsPage
      alertId={alertId}
      alertSummary={null}
      session={session()}
      apiClient={apiClient}
      onBack={vi.fn()}
      onDecisionSubmitted={onDecisionSubmitted}
    />
  );
}

function session() {
  return {
    userId: "analyst-1",
    roles: ["ANALYST"],
    authorities: ["alert:read", "assistant-summary:read", "alert:decision:submit"]
  };
}

function alertDetails(alertId, alertReason) {
  return {
    alertId,
    alertReason,
    createdAt: "2026-05-14T10:00:00Z",
    correlationId: "corr-1",
    riskLevel: "HIGH",
    fraudScore: 0.91,
    alertStatus: "OPEN",
    customerId: "customer-1",
    transactionId: "txn-1",
    reasonCodes: [],
    featureSnapshot: {},
    scoreDetails: {}
  };
}

function summary(title) {
  return {
    recommendedNextAction: { title, rationale: "Review manually.", suggestedReviewSteps: [] },
    mainFraudReasons: [],
    supportingEvidence: {},
    customerRecentBehaviorSummary: {}
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}
