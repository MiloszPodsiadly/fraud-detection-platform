import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "../api/apiError.js";
import { normalizeSession } from "../auth/session.js";
import { SESSION_STATES } from "../auth/sessionState.js";
import { ErrorState } from "./ErrorState.jsx";
import { PermissionNotice, SessionStatePanel } from "./SecurityStatePanels.jsx";

describe("security state panels", () => {
  it("renders a session-required panel for 401 responses", () => {
    render(<ErrorState error={new ApiError({
      status: 401,
      message: "any backend wording",
      details: ["reason:missing_credentials"]
    })} />);

    expect(screen.getByRole("heading", { name: "Session required" })).toBeInTheDocument();
    expect(screen.getByText(/No analyst session is currently active/)).toBeInTheDocument();
    expect(screen.getByText(/Sign in with the configured provider/)).toBeInTheDocument();
  });

  it("renders an access-denied panel for 403 responses", () => {
    render(<ErrorState error={new ApiError({
      status: 403,
      message: "different backend wording",
      details: ["reason:insufficient_authority"]
    })} />);

    expect(screen.getByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(screen.getByText(/analyst session is active/)).toBeInTheDocument();
    expect(screen.getByText(/required for this view or action/)).toBeInTheDocument();
  });

  it("renders a session-expired panel for explicit session expiry errors", () => {
    render(<ErrorState error={new ApiError({ status: 401, error: "session_expired", message: "Session expired." })} />);

    expect(screen.getByRole("heading", { name: "Session expired" })).toBeInTheDocument();
    expect(screen.getByText(/Sign in again to restore bearer access/)).toBeInTheDocument();
  });

  it("does not depend on backend message text when a security reason is present", () => {
    render(<ErrorState error={new ApiError({
      status: 401,
      message: "totally different auth message",
      details: ["reason:invalid_jwt"]
    })} />);

    expect(screen.getByRole("heading", { name: "Session required" })).toBeInTheDocument();
  });

  it("renders a provider-auth-error panel from session lifecycle state", () => {
    render(<SessionStatePanel sessionState={{ status: SESSION_STATES.AUTH_ERROR }} />);

    expect(screen.getByRole("heading", { name: "Authentication provider error" })).toBeInTheDocument();
    expect(screen.getByText(/local provider setup or sign in flow/i)).toBeInTheDocument();
  });

  it("renders a loading panel from session lifecycle state", () => {
    render(<SessionStatePanel sessionState={{ status: SESSION_STATES.LOADING }} />);

    expect(screen.getByText("Checking analyst session...")).toBeInTheDocument();
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
    expect(screen.getByText("This action requires submit analyst decision in the active analyst session.")).toBeInTheDocument();
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
    expect(screen.getByText("An active analyst session is required before updating a fraud case.")).toBeInTheDocument();
  });
});
