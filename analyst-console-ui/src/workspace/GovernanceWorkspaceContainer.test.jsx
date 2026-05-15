import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { GovernanceWorkspaceContainer } from "./GovernanceWorkspaceContainer.jsx";

describe("GovernanceWorkspaceContainer", () => {
  it("renders only governance review queue wiring", () => {
    render(
      <GovernanceWorkspaceContainer
        advisoryQueue={{ status: "AVAILABLE", count: 0, retention_limit: 200, advisory_events: [] }}
        advisoryQueueRequest={{ severity: "ALL", modelVersion: "", lifecycleStatus: "ALL", limit: 25 }}
        isGovernanceLoading={false}
        governanceError={null}
        governanceAuditHistories={{}}
        session={{ userId: "analyst-1", roles: ["ANALYST"], authorities: [] }}
        canWriteGovernanceAudit={false}
        onAdvisoryQueueRequestChange={vi.fn()}
        onGovernanceRetry={vi.fn()}
        onRecordGovernanceAudit={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Operator review queue" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Alert review queue" })).not.toBeInTheDocument();
  });
});
