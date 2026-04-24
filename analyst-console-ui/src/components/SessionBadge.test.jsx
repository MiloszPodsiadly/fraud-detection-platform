import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { normalizeSession } from "../auth/session.js";
import { SESSION_STATES } from "../auth/sessionState.js";
import { SessionBadge } from "./SessionBadge.jsx";
import { createOidcAuthProvider } from "../auth/authProvider.js";
import { createInMemoryOidcSessionSource } from "../auth/oidcSessionSource.js";

describe("SessionBadge", () => {
  it("renders the current user, role, and authorities", () => {
    render(
      <SessionBadge
        session={normalizeSession({ userId: "reviewer-1", roles: ["REVIEWER"] })}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        authProvider={undefined}
        onSessionChange={vi.fn()}
      />
    );

    expect(screen.getAllByText("reviewer-1")).toHaveLength(2);
    expect(screen.getByText("REVIEWER access active via local demo session. 6 authorities available.")).toBeInTheDocument();
    expect(screen.getByText("local/dev only")).toBeInTheDocument();
    expect(screen.getByText("Demo auth mode")).toBeInTheDocument();
    expect(screen.getByLabelText("Role")).toHaveValue("REVIEWER");
    expect(screen.getByText("alert:decision:submit")).toBeInTheDocument();
    expect(screen.getByText("fraud-case:update")).toBeInTheDocument();
  });

  it("renders an unauthenticated session state", () => {
    render(
      <SessionBadge
        session={normalizeSession({ userId: "", roles: [] })}
        sessionState={{ status: SESSION_STATES.UNAUTHENTICATED }}
        authProvider={undefined}
        onSessionChange={vi.fn()}
      />
    );

    expect(screen.getByText("Not authenticated")).toBeInTheDocument();
    expect(screen.getByText("Demo auth headers are disabled")).toBeInTheDocument();
    expect(screen.getByText("headers off")).toBeInTheDocument();
    expect(screen.getByText("Demo auth mode")).toBeInTheDocument();
  });

  it("renders a read-only provider boundary for oidc-backed sessions", () => {
    const authProvider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "token-1",
      session: { userId: "oidc-1", roles: ["ANALYST"] }
    }), {
      beginLogin: vi.fn(),
      completeLoginCallback: vi.fn(),
      beginLogout: vi.fn(),
      hasConfiguration: () => true
    });

    render(
      <SessionBadge
        session={normalizeSession({ userId: "oidc-1", roles: ["ANALYST"] })}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        authProvider={authProvider}
        onSessionChange={vi.fn()}
      />
    );

    expect(screen.getByText("OIDC session")).toBeInTheDocument();
    expect(screen.getByText("oidc")).toBeInTheDocument();
    expect(screen.getByText("Provider-backed mode")).toBeInTheDocument();
    expect(screen.getByText(/Login redirect is active/)).toBeInTheDocument();
    expect(screen.getByLabelText("User")).toBeDisabled();
    expect(screen.getByLabelText("Role")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
  });

  it("renders an expired provider-backed session state", () => {
    render(
      <SessionBadge
        session={normalizeSession({ userId: "", roles: [] })}
        sessionState={{ status: SESSION_STATES.EXPIRED }}
        authProvider={createOidcAuthProvider(createInMemoryOidcSessionSource())}
        onSessionChange={vi.fn()}
      />
    );

    expect(screen.getByText("expired")).toBeInTheDocument();
    expect(screen.getByText(/provider session expired/i)).toBeInTheDocument();
  });

  it("prefers lifecycle state over a stale session payload for oidc auth", () => {
    render(
      <SessionBadge
        session={normalizeSession({ userId: "oidc-1", roles: ["ANALYST"] })}
        sessionState={{ status: SESSION_STATES.UNAUTHENTICATED }}
        authProvider={createOidcAuthProvider(createInMemoryOidcSessionSource())}
        onSessionChange={vi.fn()}
      />
    );

    expect(screen.getByText("Not authenticated")).toBeInTheDocument();
    expect(screen.getByText("waiting for oidc")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in with OIDC" })).toBeInTheDocument();
  });

  it("starts OIDC login from the minimal session badge trigger", async () => {
    const beginLogin = vi.fn().mockResolvedValue(undefined);
    const authProvider = createOidcAuthProvider(createInMemoryOidcSessionSource(), {
      beginLogin,
      completeLoginCallback: vi.fn(),
      beginLogout: vi.fn(),
      hasConfiguration: () => true
    });

    render(
      <SessionBadge
        session={normalizeSession({ userId: "", roles: [] })}
        sessionState={{ status: SESSION_STATES.UNAUTHENTICATED }}
        authProvider={authProvider}
        onSessionChange={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Sign in with OIDC" }));

    await waitFor(() => expect(beginLogin).toHaveBeenCalledTimes(1));
  });

  it("clears the local session view before starting oidc logout", async () => {
    const beginLogout = vi.fn().mockResolvedValue(undefined);
    const onSessionChange = vi.fn();
    const authProvider = createOidcAuthProvider(createInMemoryOidcSessionSource({
      accessToken: "token-1",
      session: { userId: "oidc-1", roles: ["ANALYST"] }
    }), {
      beginLogin: vi.fn(),
      completeLoginCallback: vi.fn(),
      beginLogout,
      hasConfiguration: () => true
    });

    render(
      <SessionBadge
        session={normalizeSession({ userId: "oidc-1", roles: ["ANALYST"] })}
        sessionState={{ status: SESSION_STATES.AUTHENTICATED }}
        authProvider={authProvider}
        onSessionChange={onSessionChange}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(beginLogout).toHaveBeenCalledTimes(1));
    expect(onSessionChange).toHaveBeenCalledWith({ userId: "", roles: [], extraAuthorities: [] });
  });
});
