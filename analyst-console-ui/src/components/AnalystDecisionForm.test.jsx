import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { normalizeSession } from "../auth/session.js";
import { AnalystDecisionForm } from "./AnalystDecisionForm.jsx";

describe("AnalystDecisionForm", () => {
  it("blocks submission controls when the session lacks decision authority", () => {
    render(
      <AnalystDecisionForm
        alertId="alert-1"
        session={normalizeSession({ userId: "readonly-1", roles: ["READ_ONLY_ANALYST"] })}
        canSubmit={false}
        disabled={false}
        onSubmitted={vi.fn()}
      />
    );

    expect(screen.getByText("Insufficient permission")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Submit decision" })).toBeDisabled();
    expect(screen.getByLabelText("Decision")).toBeDisabled();
    expect(screen.getByLabelText("Reason")).toBeDisabled();
  });

  it("enables submission controls when the session has decision authority", () => {
    render(
      <AnalystDecisionForm
        alertId="alert-1"
        session={normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] })}
        canSubmit
        disabled={false}
        onSubmitted={vi.fn()}
      />
    );

    expect(screen.queryByText("Insufficient permission")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Submit decision" })).toBeEnabled();
    expect(screen.getByLabelText("Decision")).toBeEnabled();
    expect(screen.getByLabelText("Reason")).toBeEnabled();
  });

  it("passes an abort signal with the idempotency key when submitting a decision", async () => {
    const onSubmitted = vi.fn();
    const apiClient = {
      submitAnalystDecision: vi.fn().mockResolvedValue({ resultingStatus: "RESOLVED" })
    };
    render(form({ apiClient, onSubmitted }));

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed manually" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit decision" }));

    await waitFor(() => expect(apiClient.submitAnalystDecision).toHaveBeenCalledWith(
      "alert-1",
      expect.objectContaining({ decisionReason: "Reviewed manually" }),
      expect.objectContaining({
        idempotencyKey: expect.stringMatching(/^alert-decision-alert-1-/),
        signal: expect.any(AbortSignal)
      })
    ));
    expect(onSubmitted).toHaveBeenCalledTimes(1);
  });

  it("aborts an in-flight decision submit when alert context changes", async () => {
    const submit = deferred();
    const onSubmitted = vi.fn();
    const apiClient = {
      submitAnalystDecision: vi.fn().mockReturnValue(submit.promise)
    };
    const { rerender } = render(form({ alertId: "alert-old", apiClient, onSubmitted }));

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed manually" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit decision" }));
    await waitFor(() => expect(apiClient.submitAnalystDecision).toHaveBeenCalledTimes(1));
    const signal = apiClient.submitAnalystDecision.mock.calls[0][2].signal;

    rerender(form({ alertId: "alert-new", apiClient, onSubmitted }));
    submit.resolve({ resultingStatus: "RESOLVED" });

    expect(signal.aborted).toBe(true);
    await waitFor(() => expect(onSubmitted).not.toHaveBeenCalled());
    expect(screen.queryByText("Decision saved. Status: RESOLVED")).not.toBeInTheDocument();
  });

  it("ignores AbortError from decision submit without showing a form error", async () => {
    const apiClient = {
      submitAnalystDecision: vi.fn().mockRejectedValue(new DOMException("aborted", "AbortError"))
    };
    render(form({ apiClient }));

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed manually" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit decision" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Submit decision" })).toBeEnabled());
    expect(screen.queryByText("aborted")).not.toBeInTheDocument();
  });

  it("keeps decision success separate from post-save refresh failure", async () => {
    const apiClient = {
      submitAnalystDecision: vi.fn().mockResolvedValue({ resultingStatus: "RESOLVED" })
    };
    render(form({ apiClient, onSubmitted: vi.fn().mockResolvedValue({ status: "failed" }) }));

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Reviewed manually" } });
    fireEvent.click(screen.getByRole("button", { name: "Submit decision" }));

    expect(await screen.findByText("Decision saved. Status: RESOLVED")).toBeInTheDocument();
    expect(screen.getByText("Decision saved. Dashboard refresh failed; retry refresh.")).toBeInTheDocument();
    expect(screen.queryByText("failed")).not.toBeInTheDocument();
  });
});

function form({ alertId = "alert-1", apiClient = { submitAnalystDecision: vi.fn() }, onSubmitted = vi.fn() } = {}) {
  return (
    <AnalystDecisionForm
      alertId={alertId}
      session={normalizeSession({ userId: "analyst-1", roles: ["ANALYST"] })}
      apiClient={apiClient}
      canSubmit
      disabled={false}
      onSubmitted={onSubmitted}
    />
  );
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
