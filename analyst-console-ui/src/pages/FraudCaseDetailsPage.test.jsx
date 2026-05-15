import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FraudCaseDetailsPage } from "./FraudCaseDetailsPage.jsx";

describe("FraudCaseDetailsPage", () => {
  it("ignores stale fraud case response after caseId changes", async () => {
    const firstCase = deferred();
    const secondCase = deferred();
    const apiClient = {
      getFraudCase: vi.fn()
        .mockReturnValueOnce(firstCase.promise)
        .mockReturnValueOnce(secondCase.promise),
      updateFraudCase: vi.fn()
    };
    const { rerender } = render(page({ caseId: "case-old", apiClient }));
    await waitFor(() => expect(apiClient.getFraudCase).toHaveBeenCalledWith("case-old", expect.objectContaining({ signal: expect.any(AbortSignal) })));

    rerender(page({ caseId: "case-new", apiClient }));
    await waitFor(() => expect(apiClient.getFraudCase).toHaveBeenCalledWith("case-new", expect.objectContaining({ signal: expect.any(AbortSignal) })));
    secondCase.resolve(fraudCase("case-new", "New case reason"));
    await screen.findByText("New case reason");
    firstCase.resolve(fraudCase("case-old", "Old case reason"));

    await waitFor(() => expect(screen.queryByText("Old case reason")).not.toBeInTheDocument());
  });

  it("refetches with a new apiClient and ignores the old client response", async () => {
    const firstCase = deferred();
    const apiClientA = {
      getFraudCase: vi.fn().mockReturnValue(firstCase.promise),
      updateFraudCase: vi.fn()
    };
    const apiClientB = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Client B case")),
      updateFraudCase: vi.fn()
    };
    const { rerender } = render(page({ caseId: "case-1", apiClient: apiClientA }));
    await waitFor(() => expect(apiClientA.getFraudCase).toHaveBeenCalledTimes(1));

    rerender(page({ caseId: "case-1", apiClient: apiClientB }));
    await waitFor(() => expect(apiClientB.getFraudCase).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("Client B case")).toBeInTheDocument();
    firstCase.resolve(fraudCase("case-1", "Client A case"));

    await waitFor(() => expect(screen.queryByText("Client A case")).not.toBeInTheDocument());
  });

  it("aborts in-flight detail request on unmount", async () => {
    const pendingCase = deferred();
    const apiClient = {
      getFraudCase: vi.fn().mockReturnValue(pendingCase.promise),
      updateFraudCase: vi.fn()
    };
    const { unmount } = render(page({ caseId: "case-1", apiClient }));
    await waitFor(() => expect(apiClient.getFraudCase).toHaveBeenCalledTimes(1));
    const signal = apiClient.getFraudCase.mock.calls[0][1].signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("does not update state or call onCaseUpdated after submit resolves post-unmount", async () => {
    const update = deferred();
    const onCaseUpdated = vi.fn();
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn().mockReturnValue(update.promise)
    };
    const { unmount } = render(page({ caseId: "case-1", apiClient, onCaseUpdated }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));
    await waitFor(() => expect(apiClient.updateFraudCase).toHaveBeenCalledTimes(1));
    unmount();
    update.resolve({ ...fraudCase("case-1", "Updated case"), status: "CLOSED", decisionReason: "Reviewed" });

    await waitFor(() => expect(onCaseUpdated).not.toHaveBeenCalled());
  });

  it("does not let old submit response overwrite state after caseId changes", async () => {
    const update = deferred();
    const apiClient = {
      getFraudCase: vi.fn()
        .mockResolvedValueOnce(fraudCase("case-old", "Old case"))
        .mockResolvedValueOnce(fraudCase("case-new", "New case")),
      updateFraudCase: vi.fn().mockReturnValue(update.promise)
    };
    const { rerender } = render(page({ caseId: "case-old", apiClient }));
    await screen.findByText("Old case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));
    await waitFor(() => expect(apiClient.updateFraudCase).toHaveBeenCalledTimes(1));
    rerender(page({ caseId: "case-new", apiClient }));
    await screen.findByText("New case");
    update.resolve({ ...fraudCase("case-old", "Updated old case"), status: "CLOSED", decisionReason: "Reviewed" });

    await waitFor(() => expect(screen.queryByText("Updated old case")).not.toBeInTheDocument());
    expect(screen.getByText("New case")).toBeInTheDocument();
  });

  it("shows controlled mutation error when current submit fails", async () => {
    const apiClient = {
      getFraudCase: vi.fn().mockResolvedValue(fraudCase("case-1", "Open case")),
      updateFraudCase: vi.fn().mockRejectedValue(new Error("write failed"))
    };
    render(page({ caseId: "case-1", apiClient }));
    await screen.findByText("Open case");

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save case decision" }));

    expect(await screen.findByText("write failed")).toBeInTheDocument();
  });
});

function page({ caseId, apiClient, onCaseUpdated = vi.fn() }) {
  return (
    <FraudCaseDetailsPage
      caseId={caseId}
      session={session()}
      apiClient={apiClient}
      onBack={vi.fn()}
      onCaseUpdated={onCaseUpdated}
    />
  );
}

function session() {
  return {
    userId: "analyst-1",
    roles: ["FRAUD_OPS_ADMIN"],
    authorities: ["fraud-case:read", "fraud-case:update"]
  };
}

function fraudCase(caseId, reason) {
  return {
    caseId,
    suspicionType: "Grouped transfers",
    reason,
    status: "OPEN",
    totalAmountPln: 1000,
    transactions: [],
    aggregationWindow: "PT10M",
    thresholdPln: 500,
    decisionReason: "",
    decisionTags: ["rapid-transfer"]
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
