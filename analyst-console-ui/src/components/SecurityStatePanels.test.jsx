import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "../api/apiError.js";
import { normalizeSession } from "../auth/session.js";
import { ErrorState } from "./ErrorState.jsx";
import { PermissionNotice } from "./SecurityStatePanels.jsx";

describe("security state panels", () => {
  it("renders a session-required panel for 401 responses", () => {
    render(<ErrorState error={new ApiError({ status: 401, message: "Authentication is required." })} />);

    expect(screen.getByRole("heading", { name: "Session required" })).toBeInTheDocument();
    expect(screen.getByText(/No authenticated analyst session is active/)).toBeInTheDocument();
    expect(screen.getByText(/Select a local demo user and role/)).toBeInTheDocument();
  });

  it("renders an access-denied panel for 403 responses", () => {
    render(<ErrorState error={new ApiError({ status: 403, message: "Insufficient permissions." })} />);

    expect(screen.getByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(screen.getByText(/Your session is active/)).toBeInTheDocument();
    expect(screen.getByText(/role does not include the authority/)).toBeInTheDocument();
  });

  it("renders an inline permission notice for authenticated sessions without authority", () => {
    render(
      <PermissionNotice
        session={normalizeSession({ userId: "analyst-1", roles: ["READ_ONLY_ANALYST"] })}
        authority="alert:decision:submit"
        action="submitting an analyst decision"
      />
    );

    expect(screen.getByText("Insufficient permission")).toBeInTheDocument();
    expect(screen.getByText("This action requires submit analyst decision.")).toBeInTheDocument();
  });

  it("renders an inline permission notice for unauthenticated sessions", () => {
    render(
      <PermissionNotice
        session={normalizeSession({ userId: "", roles: [] })}
        authority="fraud-case:update"
        action="updating a fraud case"
      />
    );

    expect(screen.getByText("Session required")).toBeInTheDocument();
    expect(screen.getByText("Select a local demo user before updating a fraud case.")).toBeInTheDocument();
  });
});
