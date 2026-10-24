import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PolicySecurityPage } from "./PolicySecurityPage.jsx";
import { confirmPolicyAssertion, registerPolicyCredential } from "../governance/policyWebAuthn.js";

vi.mock("../governance/policyWebAuthn.js", () => ({
  confirmPolicyAssertion: vi.fn().mockResolvedValue("signed-assertion"),
  registerPolicyCredential: vi.fn().mockResolvedValue("registration-response")
}));

const own = "oidc:" + "a".repeat(64);
const other = "oidc:" + "b".repeat(64);
function client(authorities) {
  return {
    getPolicySecurityActor: vi.fn().mockResolvedValue({ actorId: own, authorities }),
    listPolicyCredentials: vi.fn().mockResolvedValue([{ credentialId: "key-1", label: "Office key",
      status: "ACTIVE", createdAt: "2026-10-01T00:00:00Z" }]),
    listPolicyCredentialSecurityEvents: vi.fn().mockResolvedValue([]),
    listMyPolicyCredentialGrants: vi.fn().mockResolvedValue([]),
    getPolicyTrustRegistries: vi.fn().mockResolvedValue([{ registry: "ONLINE_ACTORS", status: "READY",
      generation: 1, digestSha256: "a".repeat(64), entries: [] }]),
    listPolicyTrustChanges: vi.fn().mockResolvedValue([{ id: "change-1", requestedBy: own,
      intentSha256: "b".repeat(64), candidateSha256: "b".repeat(64), status: "PENDING_REVIEW",
      intent: { registry: "ONLINE_ACTORS", action: "GRANT", requestedGeneration: 2 } },
    { id: "change-2", requestedBy: other, intentSha256: "c".repeat(64),
      candidateSha256: "c".repeat(64), status: "PENDING_REVIEW",
      intent: { registry: "ONLINE_ACTORS", action: "REVOKE", requestedGeneration: 2 } }]),
    getPolicyTrustCandidate: vi.fn().mockResolvedValue({ requestId: "change-2", requestStatus: "PENDING_REVIEW",
      baseGeneration: 1, baseSha256: "a".repeat(64), candidateGeneration: 2,
      candidateSha256: "c".repeat(64), reviewSummary: "Revoke an exact actor mandate",
      diff: { action: "REVOKE", targetIdentity: other,
        existingEntry: { actorId: other, roles: ["POLICY_APPROVER"], environments: ["PRODUCTION"],
          institutionalScopes: ["CURRENT_INSTITUTION"], intendedUses: ["FRAUD_RISK_SCORING"],
          validFrom: "2025-01-01T00:00:00Z", validUntil: "2027-01-01T00:00:00Z", status: "ACTIVE" },
        proposedEntry: { actorId: other, roles: ["POLICY_APPROVER"], environments: ["PRODUCTION"],
          institutionalScopes: ["CURRENT_INSTITUTION"], intendedUses: ["FRAUD_RISK_SCORING"],
          validFrom: "2025-01-01T00:00:00Z", validUntil: "2027-01-01T00:00:00Z", status: "REVOKED" },
        removedAuthorities: ["POLICY_APPROVER"], addedAuthorities: [] } }),
    previewPolicyTrustChange: vi.fn().mockResolvedValue({ registry: "ONLINE_ACTORS",
      baseGeneration: 1, baseSha256: "a".repeat(64), candidateGeneration: 2,
      candidateSha256: "d".repeat(64), reviewSummary: "Grant an exact OIDC mandate",
      diff: { action: "GRANT", targetIdentity: other, existingEntry: null,
        proposedEntry: { actorId: other, roles: ["POLICY_APPROVER"], environments: ["TEST"],
          institutionalScopes: ["CURRENT_INSTITUTION"], intendedUses: ["FRAUD_RISK_SCORING"],
          validFrom: "2026-01-01T00:00:00Z", validUntil: "2028-01-01T00:00:00Z", status: "ACTIVE" },
        removedAuthorities: [], addedAuthorities: ["POLICY_APPROVER"] } }),
    getPolicyTrustDeployment: vi.fn().mockResolvedValue({ requestId: "change-2", rolloutState: "OBSERVED_PARTIAL",
      requestedGeneration: 2, candidateRegistrySha256: "c".repeat(64), rollbackFloor: 1,
      consistentObservedGeneration: null, consistentObservedRegistrySha256: null,
      observationSetTruncated: false, acknowledgements: [] }),
    startPolicyCredentialRevocation: vi.fn().mockResolvedValue({ challengeId: "credential-revoke:1", optionsJson: "{}" }),
    finishPolicyCredentialRevocation: vi.fn().mockResolvedValue(null),
    startPolicyTrustReview: vi.fn().mockResolvedValue({ challengeId: "trust-review:1", optionsJson: "{}" }),
    finishPolicyTrustReview: vi.fn().mockResolvedValue({ id: "change-2", status: "PENDING_OPERATOR_ACTION" })
  };
}

beforeEach(() => { vi.clearAllMocks(); window.sessionStorage.clear(); vi.stubGlobal("confirm", vi.fn(() => true)); });

describe("Security workspace", () => {
  it("revokes the actor's credential only after passkey confirmation", async () => {
    const apiClient = client(["model-policy:credential:enroll"]);
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: "Revoke with passkey" }));
    await waitFor(() => expect(apiClient.finishPolicyCredentialRevocation).toHaveBeenCalledWith("key-1",
      expect.objectContaining({ challengeId: "credential-revoke:1", responseJson: "signed-assertion" })));
    expect(confirmPolicyAssertion).toHaveBeenCalledWith("{}");
  });

  it("checks a durable revocation event after an offline response without reusing the assertion", async () => {
    const apiClient = client(["model-policy:credential:enroll"]);
    apiClient.finishPolicyCredentialRevocation.mockRejectedValue(new Error("offline"));
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: "Revoke with passkey" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Outcome uncertain");
    const operationId = apiClient.finishPolicyCredentialRevocation.mock.calls[0][1].operationId;
    expect(window.sessionStorage.getItem("policy-security-pending-operation")).toContain(operationId);
    expect(window.sessionStorage.getItem("policy-security-pending-operation")).not.toContain("signed-assertion");
    apiClient.listPolicyCredentialSecurityEvents.mockResolvedValue([
      { id: `credential-revoke:${operationId}`, actorId: own, credentialId: "key-1", action: "CREDENTIAL_REVOKED" }
    ]);
    fireEvent.click(screen.getByRole("button", { name: "Check durable security receipt" }));
    expect(await screen.findByText(/has a durable receipt/)).toBeInTheDocument();
    expect(apiClient.finishPolicyCredentialRevocation).toHaveBeenCalledTimes(1);
    expect(window.sessionStorage.getItem("policy-security-pending-operation")).toBeNull();
    expect(JSON.stringify(window.localStorage)).not.toContain("signed-assertion");
  });

  it("restores a read-only security receipt check after refresh", async () => {
    window.sessionStorage.setItem("policy-security-pending-operation", JSON.stringify({
      kind: "credentialRevoke", operationId: "revoke-op", actorId: own,
      credentialId: "key-1", label: "credential revocation"
    }));
    const apiClient = client(["model-policy:credential:enroll"]);
    apiClient.listPolicyCredentialSecurityEvents.mockResolvedValue([{
      id: "credential-revoke:revoke-op", actorId: own, credentialId: "key-1",
      action: "CREDENTIAL_REVOKED"
    }]);
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: "Check durable security receipt" }));
    expect(await screen.findByText(/has a durable receipt/)).toBeInTheDocument();
    expect(apiClient.finishPolicyCredentialRevocation).not.toHaveBeenCalled();
  });

  it("lets the authenticated target claim and enroll without displaying the secret", async () => {
    const apiClient = client(["model-policy:credential:enroll"]);
    apiClient.listMyPolicyCredentialGrants.mockResolvedValue([{ requestId: "grant-1", purpose: "FIRST",
      caseReference: "case-1", expiresAt: "2026-11-01T10:10:00Z", alreadyClaimed: false }]);
    apiClient.claimPolicyCredentialGrant = vi.fn().mockResolvedValue({ token: "grant-1.secret", expiresAt: "2026-11-01T10:10:00Z" });
    apiClient.startPolicyCredentialRegistration = vi.fn().mockResolvedValue({ challengeId: "registration:1", optionsJson: "{}" });
    apiClient.finishPolicyCredentialRegistration = vi.fn().mockResolvedValue({ credentialId: "new-key" });
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: "Claim and enroll passkey" }));
    await waitFor(() => expect(apiClient.finishPolicyCredentialRegistration).toHaveBeenCalledWith(
      expect.objectContaining({ grantToken: "grant-1.secret", responseJson: "registration-response" })));
    expect(registerPolicyCredential).toHaveBeenCalledWith("{}");
    expect(screen.queryByText(/grant-1.secret/)).not.toBeInTheDocument();
  });

  it("requires inspection and a passkey for an independent grant decision", async () => {
    const apiClient = client(["model-policy:credential:admin"]);
    apiClient.getPolicyCredentialGrantReview = vi.fn().mockResolvedValue({ requestId: "grant-2",
      purpose: "ADDITIONAL", targetIssuer: "https://issuer.example", targetSubject: "operator",
      requestedBy: other, caseReference: "case-2", expiresAt: "2026-11-01T11:00:00Z",
      approved: false, rejected: false });
    apiClient.startPolicyCredentialGrantReview = vi.fn().mockResolvedValue({
      challengeId: "credential-grant-review:1", optionsJson: "{}" });
    apiClient.finishPolicyCredentialGrantReview = vi.fn().mockResolvedValue({
      requestId: "grant-2", decision: "APPROVE" });
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    fireEvent.change(await screen.findByLabelText("Request ID for independent approval"),
      { target: { value: "grant-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Inspect grant request" }));
    fireEvent.click(await screen.findByRole("button", { name: "Approve with passkey" }));
    await waitFor(() => expect(apiClient.finishPolicyCredentialGrantReview).toHaveBeenCalledWith(
      "grant-2", expect.objectContaining({ challengeId: "credential-grant-review:1",
        responseJson: "signed-assertion" })));
    expect(confirmPolicyAssertion).toHaveBeenCalledWith("{}");
    expect(screen.queryByText(/One-time grant token/)).not.toBeInTheDocument();
  });

  it("shows exact grant status and revokes a claimed capability with an auditable intent", async () => {
    const apiClient = client(["model-policy:credential:admin", "model-policy:credential:incident-revoke"]);
    const status = { requestId: "grant-3", state: "CLAIMED", purpose: "FIRST",
      targetIssuer: "https://issuer.example", targetSubject: "target", requestedBy: other,
      approvedBy: own, approvalReference: "grant-review:proof-3", caseReference: "case-3",
      expiresAt: "2026-11-01T11:00:00Z", consumedAt: null,
      history: [{ action: "GRANT_CLAIMED", actorId: other, occurredAt: "2026-11-01T10:00:00Z" }] };
    apiClient.getPolicyCredentialGrantStatus = vi.fn().mockResolvedValue(status);
    apiClient.revokePolicyCredentialGrant = vi.fn().mockImplementation(async (_, command) => ({
      ...status, state: "REVOKED", revokedBy: own, revocationOperationId: command.operationId
    }));
    apiClient.findPolicyCredentialIncidentGrants = vi.fn().mockResolvedValue({
      grants: [status], nextAfterId: null
    });
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    fireEvent.change(await screen.findByLabelText("Exact actor ID"), { target: { value: other } });
    fireEvent.click(screen.getByRole("button", { name: "Find active grants" }));
    await waitFor(() => expect(apiClient.findPolicyCredentialIncidentGrants).toHaveBeenCalledWith(other));
    fireEvent.change(await screen.findByLabelText("Grant ID"), { target: { value: "grant-3" } });
    fireEvent.click(screen.getByRole("button", { name: "Inspect status and audit" }));
    expect(await screen.findByText(/approval reference grant-review:proof-3/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Revocation reason"), { target: { value: "Compromised actor" } });
    fireEvent.change(screen.getByLabelText("Incident or administrative case"),
      { target: { value: "incident-3" } });
    fireEvent.click(screen.getByRole("button", { name: "Revoke for security incident" }));
    await waitFor(() => expect(apiClient.revokePolicyCredentialGrant).toHaveBeenCalledWith("grant-3",
      expect.objectContaining({ targetIssuer: "https://issuer.example", targetSubject: "target",
        reason: "Compromised actor", caseReference: "incident-3", kind: "SECURITY_INCIDENT" })));
    expect(screen.queryByText(/grant-3.secret/)).not.toBeInTheDocument();
  });

  it("hides self-review and calls approved trust pending operator action, not deployed", async () => {
    const apiClient = client(["model-policy:trust:read", "model-policy:trust:approve"]);
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    expect(await screen.findByText(/change-1:/)).toBeInTheDocument();
    const approve = screen.getByRole("button", { name: "Approve displayed candidate with passkey" });
    expect(approve).toBeDisabled();
    fireEvent.click(screen.getAllByRole("button", { name: "Inspect candidate diff" })[1]);
    expect(await screen.findByText("Revoke an exact actor mandate")).toBeInTheDocument();
    expect(approve).toBeEnabled();
    fireEvent.click(approve);
    expect(await screen.findByRole("status")).toHaveTextContent("PENDING_OPERATOR_ACTION");
    expect(screen.getByText(/cross-instance consistency are UNKNOWN here/)).toBeInTheDocument();
  });

  it("labels trust deployment observations without claiming activation", async () => {
    const apiClient = client(["model-policy:trust:read"]);
    apiClient.getPolicyTrustDeployment.mockResolvedValue({ requestId: "change-2",
      requestStatus: "PENDING_OPERATOR_ACTION", registry: "ONLINE_ACTORS",
      rolloutState: "OBSERVED_PARTIAL", requestedGeneration: 2,
      candidateRegistrySha256: "c".repeat(64), rollbackFloor: 1, consistentObservedGeneration: null,
      consistentObservedRegistrySha256: null, observationSetTruncated: false,
      deploymentId: "rollout-7", membershipEvidence: "DECLARED_STATIC_MEMBERSHIP",
      observationEvidence: "UNAUTHENTICATED_RUNTIME_HEARTBEAT", activationConfirmed: false,
      expectedInstances: [{ role: "ALERT_VERIFIER", instanceId: "alert-a" },
        { role: "PUBLISHER", instanceId: "publisher-a" }],
      missingInstances: [{ role: "ALERT_VERIFIER", instanceId: "alert-a" }],
      staleInstances: [], mismatchedInstances: [], mismatchReason: "EXPECTED_INSTANCES_NOT_ALL_MATCHING",
      acknowledgements: [] });
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    const deploymentButtons = await screen.findAllByRole("button", { name: "Deployment" });
    fireEvent.click(deploymentButtons[1]);
    expect(await screen.findByText(/consistent observed generation unconfirmed/)).toBeInTheDocument();
    expect(screen.getByText(/Missing instances: ALERT_VERIFIER\/alert-a/)).toBeInTheDocument();
    expect(screen.getByText(/activation: not confirmed/)).toBeInTheDocument();
    expect(screen.getByText(/UNAUTHENTICATED_RUNTIME_HEARTBEAT/)).toBeInTheDocument();
    expect(screen.queryByText(/activated generation/)).not.toBeInTheDocument();
  });

  it("requires a server-built candidate preview before the maker can sign", async () => {
    const apiClient = client(["model-policy:trust:read", "model-policy:trust:request"]);
    apiClient.startPolicyTrustChange = vi.fn().mockResolvedValue({ challengeId: "trust-request:1", optionsJson: "{}" });
    apiClient.finishPolicyTrustChange = vi.fn().mockResolvedValue({ id: "trust-request:1", status: "PENDING_REVIEW" });
    render(<PolicySecurityPage apiClient={apiClient} enabled />);
    const sign = await screen.findByRole("button", { name: "Sign displayed candidate with passkey" });
    expect(sign).toBeDisabled();
    expect(screen.queryByLabelText("Candidate registry SHA-256")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Preview exact registry candidate" }));
    expect(await screen.findByText("Grant an exact OIDC mandate")).toBeInTheDocument();
    expect(apiClient.previewPolicyTrustChange).toHaveBeenCalledWith(expect.objectContaining({
      requestedGeneration: 2, candidateRegistrySha256: null
    }));
    expect(sign).toBeEnabled();
    fireEvent.click(sign);
    await waitFor(() => expect(apiClient.startPolicyTrustChange).toHaveBeenCalledWith(
      expect.objectContaining({ intent: expect.objectContaining({
        candidateRegistrySha256: "d".repeat(64)
      }) })));
  });
});
