import { render, screen } from "@testing-library/react";
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
});
