import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ModelPromotionEmergencyPanel } from "./ModelPromotionEmergencyPanel.jsx";
import { useOptionalWorkspaceRuntime } from "../workspace/useWorkspaceRuntime.js";

vi.mock("../workspace/useWorkspaceRuntime.js", () => ({ useOptionalWorkspaceRuntime: vi.fn() }));

const proposal = { proposalId: "retirement-1", requestedTargetState: "RETIRED" };
const state = { governanceScope: { environment: "PRODUCTION" } };

describe("ModelPromotionEmergencyPanel", () => {
  beforeEach(() => vi.clearAllMocks());

  it("requests a dedicated exact-intent grant instead of treating rationale as authorization", async () => {
    const apiClient = {
      requestEmergencyModelRetirement: vi.fn().mockResolvedValue({
        authorizationId: "grant-1", status: "REQUESTED", expiresAt: "2026-10-23T12:05:00Z"
      })
    };
    vi.mocked(useOptionalWorkspaceRuntime).mockReturnValue({ apiClient });
    render(<ModelPromotionEmergencyPanel workflow={{ load: vi.fn() }} state={state}
      pending={[proposal]} canRequest canAuthorize={false} />);
    fireEvent.change(screen.getByLabelText("Pending RETIRED proposal"), { target: { value: "retirement-1" } });
    fireEvent.change(screen.getByLabelText("Incident / case ID"), { target: { value: "case-12345" } });
    fireEvent.change(screen.getByLabelText("Incident evidence reference"),
      { target: { value: "incident-ledger:case-12345" } });
    fireEvent.change(screen.getByLabelText("Specific emergency reason"),
      { target: { value: "An independently confirmed incident requires immediate retirement" } });
    fireEvent.click(screen.getByRole("button", { name: "Request independent emergency authorization" }));
    await waitFor(() => expect(apiClient.requestEmergencyModelRetirement).toHaveBeenCalledTimes(1));
    expect(apiClient.requestEmergencyModelRetirement.mock.calls[0][0]).toBe("retirement-1");
    expect(apiClient.requestEmergencyModelRetirement.mock.calls[0][1]).toMatchObject({
      incidentId: "case-12345", incidentEvidenceReference: "incident-ledger:case-12345"
    });
    expect(await screen.findByText(/Authorization ID:/)).toHaveTextContent("grant-1");
  });

  it("makes an independent authorizer inspect the exact intent without receiving a token", async () => {
    const apiClient = {
      inspectEmergencyModelRetirement: vi.fn().mockResolvedValue({
        authorizationId: "grant-1", proposalId: "retirement-1", proposalSha256: "a".repeat(64),
        artifactIdentity: { modelName: "fraud-model", modelVersion: "v1", modelArtifactSha256: "b".repeat(64) },
        governanceScope: { institutionalScope: "bank", environment: "PRODUCTION" },
        currentState: "CHAMPION", currentRevision: 4, requiredOutcome: "RETIRED",
        incidentId: "case-12345", incidentEvidenceReference: "incident-ledger:case-12345",
        reason: "An independently confirmed incident requires immediate retirement",
        requesterActorId: "oidc:requester", expiresAt: "2026-10-23T12:05:00Z",
        intentSha256: "c".repeat(64), status: "REQUESTED"
      }),
      authorizeEmergencyModelRetirement: vi.fn().mockResolvedValue({
        authorizationId: "grant-1", status: "AUTHORIZED", expiresAt: "2026-10-23T12:05:00Z"
      })
    };
    vi.mocked(useOptionalWorkspaceRuntime).mockReturnValue({ apiClient });
    render(<ModelPromotionEmergencyPanel workflow={{ load: vi.fn() }} state={null}
      pending={[]} canRequest={false} canAuthorize />);
    fireEvent.change(screen.getByLabelText("Emergency authorization ID"), { target: { value: "grant-1" } });
    expect(screen.queryByRole("button", { name: "Authorize exact emergency grant" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Inspect exact authorization" }));
    expect(await screen.findByText("incident-ledger:case-12345", { exact: false })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Independent incident-evidence review rationale"),
      { target: { value: "I checked the incident record and exact model identity" } });
    fireEvent.click(screen.getByRole("button", { name: "Authorize exact emergency grant" }));
    await waitFor(() => expect(apiClient.authorizeEmergencyModelRetirement).toHaveBeenCalledWith(
      "grant-1", expect.objectContaining({ intentSha256: "c".repeat(64) })));
    expect(await screen.findByText(/Authorization recorded/)).toBeInTheDocument();
    expect(screen.queryByText(/one-time-secret/)).not.toBeInTheDocument();
  });

  it("claims the capability in the requester session and immediately retires the bound model", async () => {
    const apiClient = {
      claimEmergencyModelRetirement: vi.fn().mockResolvedValue({ authorizationId: "grant-1", token: "one-time-secret" }),
      consumeEmergencyModelRetirement: vi.fn().mockResolvedValue({})
    };
    const workflow = { load: vi.fn().mockResolvedValue() };
    vi.mocked(useOptionalWorkspaceRuntime).mockReturnValue({ apiClient });
    render(<ModelPromotionEmergencyPanel workflow={workflow} state={state}
      pending={[proposal]} canRequest canAuthorize={false} />);
    fireEvent.change(screen.getByLabelText("Pending RETIRED proposal"), { target: { value: "retirement-1" } });
    fireEvent.change(screen.getByLabelText("Approved authorization ID"), { target: { value: "grant-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Claim and retire exact model" }));
    await waitFor(() => expect(apiClient.consumeEmergencyModelRetirement).toHaveBeenCalledWith(
      "retirement-1", { authorizationId: "grant-1", token: "one-time-secret" }));
    expect(apiClient.claimEmergencyModelRetirement).toHaveBeenCalledWith("grant-1");
  });

  it("lets an incident operator find and revoke bounded active grants", async () => {
    const apiClient = {
      findAffectedEmergencyModelRetirements: vi.fn().mockResolvedValue({
        grants: [{ authorizationId: "grant-1", status: "CLAIMED" }], hasMore: false
      }),
      revokeAffectedEmergencyModelRetirements: vi.fn().mockResolvedValue({
        grants: [{ authorizationId: "grant-1", status: "REVOKED" }], hasMore: false
      })
    };
    vi.mocked(useOptionalWorkspaceRuntime).mockReturnValue({ apiClient });
    window.confirm = vi.fn().mockReturnValue(true);
    render(<ModelPromotionEmergencyPanel workflow={{ load: vi.fn() }} state={null}
      pending={[]} canRequest={false} canAuthorize />);
    fireEvent.change(screen.getByLabelText("Affected incident ID"),
      { target: { value: "case-compromise-123" } });
    fireEvent.click(screen.getByRole("button", { name: "Find active emergency grants" }));
    expect(await screen.findByText(/grant-1: CLAIMED/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revoke listed grants" }));
    await waitFor(() => expect(apiClient.revokeAffectedEmergencyModelRetirements).toHaveBeenCalledWith({
      actorId: null, incidentId: "case-compromise-123"
    }));
  });

  it("shows incident and committed proof to a dedicated auditor without a grant token", async () => {
    const apiClient = {
      auditEmergencyModelRetirement: vi.fn().mockResolvedValue({
        proofStatus: "INDEPENDENT_AUTHORIZATION_AND_RETIREMENT_COMMITTED",
        incidentId: "case-12345", incidentEvidenceReference: "incident-ledger:case-12345",
        reason: "An independently confirmed incident requires immediate retirement",
        authorizationRationale: "I checked the independent incident record",
        requesterActorId: "oidc:requester", authorizedByActorId: "oidc:authorizer",
        status: "CONSUMED"
      })
    };
    vi.mocked(useOptionalWorkspaceRuntime).mockReturnValue({ apiClient });
    render(<ModelPromotionEmergencyPanel workflow={{ load: vi.fn() }} state={null}
      pending={[]} canRequest={false} canAuthorize={false} canAudit />);
    fireEvent.change(screen.getByLabelText("Authorization ID"), { target: { value: "grant-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Verify recorded emergency decision" }));
    await waitFor(() => expect(apiClient.auditEmergencyModelRetirement).toHaveBeenCalledWith("grant-1"));
    expect(await screen.findByText("INDEPENDENT_AUTHORIZATION_AND_RETIREMENT_COMMITTED"))
      .toBeInTheDocument();
    expect(screen.getByText(/incident-ledger:case-12345/)).toBeInTheDocument();
    expect(screen.queryByText(/One-time token/)).not.toBeInTheDocument();
  });
});
