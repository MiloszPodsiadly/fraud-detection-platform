import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { normalizeSession } from "../auth/session.js";
import { SessionBadge } from "./SessionBadge.jsx";

describe("SessionBadge", () => {
  it("renders the current user, role, and authorities", () => {
    render(
      <SessionBadge
        session={normalizeSession({ userId: "reviewer-1", roles: ["REVIEWER"] })}
        onSessionChange={vi.fn()}
      />
    );

    expect(screen.getByText("reviewer-1")).toBeInTheDocument();
    expect(screen.getByText("Authenticated as REVIEWER")).toBeInTheDocument();
    expect(screen.getByText("local/dev only")).toBeInTheDocument();
    expect(screen.getByLabelText("Role")).toHaveValue("REVIEWER");
    expect(screen.getByText("alert:decision:submit")).toBeInTheDocument();
    expect(screen.getByText("fraud-case:update")).toBeInTheDocument();
  });

  it("renders an unauthenticated session state", () => {
    render(
      <SessionBadge
        session={normalizeSession({ userId: "", roles: [] })}
        onSessionChange={vi.fn()}
      />
    );

    expect(screen.getByText("Not authenticated")).toBeInTheDocument();
    expect(screen.getByText("Demo auth headers are disabled")).toBeInTheDocument();
    expect(screen.getByText("headers off")).toBeInTheDocument();
  });
});
