import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PolicySecurityPage } from "./PolicySecurityPage.jsx";
import { confirmPolicyAssertion } from "../governance/policyWebAuthn.js";

vi.mock("../governance/policyWebAuthn.js", () => ({
  confirmPolicyAssertion: vi.fn().mockResolvedValue("signed-assertion"),
  registerPolicyCredential: vi.fn()
}));

const own = "oidc:" + "a".repeat(64);
const other = "oidc:" + "b".repeat(64);
function client(authorities) {
  return {
    getPolicySecurityActor: vi.fn().mockResolvedValue({ actorId: own, authorities }),
    listPolicyCredentials: vi.fn().mockResolvedValue([{ credentialId: "key-1", label: "Office key",
      status: "ACTIVE", createdAt: "2026-10-01T00:00:00Z" }]),
    listPolicyCredentialSecurityEvents: vi.fn().mockResolvedValue([]),
    getPolicyTrustRegistries: vi.fn().mockResolvedValue([{ registry: "ONLINE_ACTORS", status: "READY",
      generation: 1, digestSha256: "a".repeat(64), entries: [] }]),
    listPolicyTrustChanges: vi.fn().mockResolvedValue([{ id: "change-1", requestedBy: own,
      intentSha256: "b".repeat(64), status: "PENDING_REVIEW",
      intent: { registry: "ONLINE_ACTORS", action: "GRANT", requestedGeneration: 2 } },
    { id: "change-2", requestedBy: other, intentSha256: "c".repeat(64), status: "PENDING_REVIEW",
      intent: { registry: "ONLINE_ACTORS", action: "REVOKE", requestedGeneration: 2 } }]),
    getPolicyTrustDeployment: vi.fn().mockResolvedValue({ requestId: "change-2", rolloutState: "PARTIAL_ROLLOUT",
      requestedGeneration: 2, candidateRegistrySha256: "c".repeat(64), rollbackFloor: 1,
      consistentObservedGeneration: null, consistentObservedRegistrySha256: null,
      observationSetTruncated: false, acknowledgements: [] }),
    startPolicyCredentialRevocation: vi.fn().mockResolvedValue({ challengeId: "credential-revoke:1", optionsJson: "{}" }),
    finishPolicyCredentialRevocation: vi.fn().mockResolvedValue(null),
    startPolicyTrustReview: vi.fn().mockResolvedValue({ challengeId: "trust-review:1", optionsJson: "{}" }),
    finishPolicyTrustReview: vi.fn().mockResolvedValue({ id: "change-2", status: "PENDING_OPERATOR_ACTION" })
  };
}

beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal("confirm", vi.fn(() => true)); });

describe("Security workspace", () => {
  it("revokes the actor's credential only after passkey confirmation", async () => {
    const apiClient = client(["model-policy:credential:enroll"]);
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: "Revoke with passkey" }));
    await waitFor(() => expect(apiClient.finishPolicyCredentialRevocation).toHaveBeenCalledWith("key-1",
      expect.objectContaining({ challengeId: "credential-revoke:1", responseJson: "signed-assertion" })));
    expect(confirmPolicyAssertion).toHaveBeenCalledWith("{}");
  });

  it("hides self-review and calls approved trust pending operator action, not deployed", async () => {
    const apiClient = client(["model-policy:trust:read", "model-policy:trust:approve"]);
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    expect(await screen.findByText(/change-1:/)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Approve with passkey" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Approve with passkey" }));
    expect(await screen.findByRole("status")).toHaveTextContent("PENDING_OPERATOR_ACTION");
    expect(screen.getByText(/cross-instance consistency are UNKNOWN here/)).toBeInTheDocument();
  });

  it("labels trust deployment observations without claiming activation", async () => {
    const apiClient = client(["model-policy:trust:read"]);
    apiClient.getPolicyTrustDeployment.mockResolvedValue({ requestId: "change-2",
      rolloutState: "OBSERVED_CONSISTENT_PENDING_OPERATOR_CONFIRMATION", requestedGeneration: 2,
      candidateRegistrySha256: "c".repeat(64), rollbackFloor: 1, consistentObservedGeneration: 2,
      consistentObservedRegistrySha256: "c".repeat(64), observationSetTruncated: false, acknowledgements: [] });
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    const deploymentButtons = await screen.findAllByRole("button", { name: "Deployment" });
    fireEvent.click(deploymentButtons[1]);
    expect(await screen.findByText(/consistent observed generation 2/)).toBeInTheDocument();
    expect(screen.queryByText(/activated generation/)).not.toBeInTheDocument();
  });
});
