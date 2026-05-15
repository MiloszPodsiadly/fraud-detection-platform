import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DetailStateBanner } from "./DetailStateBanner.jsx";

describe("DetailStateBanner", () => {
  it.each([
    ["stale", "alert", "assertive"],
    ["unavailable", "alert", "assertive"],
    ["access-denied", "alert", "assertive"],
    ["degraded", "status", "polite"],
    ["runtime-not-ready", "status", "polite"],
    ["loading", "status", "polite"]
  ])("maps %s to %s/%s", (state, role, liveMode) => {
    render(<DetailStateBanner state={state} message="detail state changed" />);

    const banner = screen.getByRole(role);
    expect(banner).toHaveAttribute("aria-live", liveMode);
  });

  it("falls back safely for unknown states", () => {
    render(<DetailStateBanner state="unexpected" />);

    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByText("Detail state changed.")).toBeInTheDocument();
  });

  it("renders nothing for loaded or empty states", () => {
    const { rerender } = render(<DetailStateBanner state="loaded" />);

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    rerender(<DetailStateBanner state={null} />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
