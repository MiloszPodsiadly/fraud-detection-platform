import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { GovernanceWorkspaceContainer } from "./GovernanceWorkspaceContainer.jsx";

describe("GovernanceWorkspaceContainer", () => {
  it("renders only governance review queue wiring", () => {
    const { container } = render(
      <GovernanceWorkspaceContainer
        queueState={{
          queue: { status: "AVAILABLE", count: 0, retention_limit: 200, advisory_events: [] },
          request: { severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 },
          isLoading: false,
          error: null,
          auditHistories: {},
          setRequest: vi.fn(),
          refresh: vi.fn()
        }}
        session={{ userId: "analyst-1", roles: ["ANALYST"], authorities: [] }}
        canWriteGovernanceAudit={false}
        onRecordGovernanceAudit={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Operator review queue" })).toBeInTheDocument();
    expect(container.querySelector("[data-workspace-heading]")).toHaveTextContent("Operator review queue");
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
  });
});
