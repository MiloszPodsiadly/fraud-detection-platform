import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PolicyGovernancePage } from "./PolicyGovernancePage.jsx";
import { confirmPolicyAssertion } from "../governance/policyWebAuthn.js";

vi.mock("../governance/policyWebAuthn.js", () => ({
  confirmPolicyAssertion: vi.fn(), registerPolicyCredential: vi.fn()
}));

const author = "oidc:" + "a".repeat(64);
const reviewer = "oidc:" + "b".repeat(64);
const draft = {
  policyId: "risk-policy", revision: 1, governanceScope: {
    institutionalScope: "CURRENT_INSTITUTION", environment: "PRODUCTION"
  }, intendedUse: "FRAUD_RISK_SCORING", authorIdentity: author, approverIdentity: reviewer,
  approvalReference: "approval-1", effectiveFromInclusive: "2026-11-01T00:00:00Z",
  effectiveToExclusive: "2027-01-01T00:00:00Z", criteria: []
};
const proposal = (status = "SUBMITTED") => ({
  id: "proposal-1", draft, draftSha256: "f".repeat(64), status, version: 1,
  authorActorId: author, approvalEvidenceReference: null
});
function api({ actorId = reviewer, authorities = ["model-policy:read", "model-policy:approve", "model-policy:reject", "model-policy:audit:read"],
  items = [proposal()], publication = { state: "PUBLISHED", signatureStatus: "VERIFIED",
    approvalEvidenceStatus: "UNVERIFIED", verificationStatus: "UNVERIFIED", verificationReason: "EVIDENCE_MISSING" } } = {}) {
  return {
    getPolicyGovernanceActor: vi.fn().mockResolvedValue({ actorId, authorities }),
    listPolicyProposals: vi.fn().mockResolvedValue(items),
    getPolicyHistory: vi.fn().mockResolvedValue([]),
    getPolicyPublication: vi.fn().mockResolvedValue(publication),
    startPolicyAction: vi.fn().mockResolvedValue({ challengeId: "action:op", optionsJson: "{}" }),
    finishPolicyAction: vi.fn().mockResolvedValue({ ...items[0], status: "APPROVED", version: 2 }),
    requestPolicyPublication: vi.fn().mockResolvedValue(items[0]),
    listPolicyCredentials: vi.fn().mockResolvedValue([])
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal("confirm", vi.fn(() => true));
});

describe("Policy Governance workspace", () => {
  it("shows the author editor without inventing reviewer rights from analyst roles", async () => {
    const client = api({ actorId: author, authorities: ["model-policy:read", "model-policy:create", "model-policy:submit"],
      items: [proposal("DRAFT")] });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · DRAFT/ }));
    expect(screen.getByRole("textbox", { name: "Draft JSON" }).value).toContain("intendedApproverActorId");
    expect(screen.getByRole("button", { name: /submit with WebAuthn/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Approve with WebAuthn/ })).not.toBeInTheDocument();
  });

  it("shows the assigned reviewer queue, exact draft and separate trust statuses", async () => {
    const client = api();
    render(<PolicyGovernancePage apiClient={client} enabled />);
    expect(await screen.findByText("Pending approval queue")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /risk-policy · revision 1 · SUBMITTED/ }));
    expect(screen.getByText(/immutable draft SHA-256/)).toBeInTheDocument();
    expect(await screen.findByText("Issuer signature: VERIFIED")).toBeInTheDocument();
    expect(screen.getByText("Independent approval evidence: UNVERIFIED")).toBeInTheDocument();
    expect(screen.getByText(/Full policy authorization: UNVERIFIED/)).toBeInTheDocument();
    expect(screen.getByText(/Runtime deployment authorization: separate control/)).toBeInTheDocument();
  });

  it("disables self-review even if review authorities are present", async () => {
    const client = api({ actorId: author, items: [{ ...proposal(), draft: { ...draft, approverIdentity: author } }] });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · SUBMITTED/ }));
    expect(screen.getByText("You authored this policy and cannot review it.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve with WebAuthn" })).not.toBeInTheDocument();
  });

  it("handles cancellation without committing a decision", async () => {
    const client = api();
    confirmPolicyAssertion.mockRejectedValue(Object.assign(new Error("Cancelled"), { name: "NotAllowedError" }));
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · SUBMITTED/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Rationale" }), { target: { value: "Reviewed thresholds" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve with WebAuthn" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("WebAuthn was cancelled");
    expect(client.finishPolicyAction).not.toHaveBeenCalled();
  });

  it("still reports backend denial when a visible reviewer control is rejected", async () => {
    const client = api();
    client.startPolicyAction.mockRejectedValue({ status: 403 });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · SUBMITTED/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Rationale" }), { target: { value: "Insufficient evidence" } });
    fireEvent.click(screen.getByRole("button", { name: "Reject with WebAuthn" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("backend denied");
    expect(client.finishPolicyAction).not.toHaveBeenCalled();
  });

  it("keeps publication unavailable separate from a rejected policy", async () => {
    const client = api({ items: [proposal("PUBLICATION_PENDING")], publication: {
      state: "RETRYABLE", failureCode: "WORKER_UNAVAILABLE", verificationStatus: "UNAVAILABLE",
      signatureStatus: null, approvalEvidenceStatus: null
    } });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · PUBLICATION_PENDING/ }));
    expect(await screen.findByText(/Job: RETRYABLE/)).toBeInTheDocument();
    expect(screen.getByText("Full policy authorization: UNAVAILABLE")).toBeInTheDocument();
    expect(screen.queryByText("Status: REJECTED")).not.toBeInTheDocument();
  });

  it("prevents duplicate clicks and reports a backend conflict without claiming approval", async () => {
    const client = api();
    let release;
    client.startPolicyAction.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · SUBMITTED/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Rationale" }), { target: { value: "Independent review" } });
    const approve = screen.getByRole("button", { name: "Approve with WebAuthn" });
    fireEvent.click(approve);
    fireEvent.click(approve);
    expect(client.startPolicyAction).toHaveBeenCalledTimes(1);
    release({ challengeId: "action:op", optionsJson: "{}" });
    confirmPolicyAssertion.mockResolvedValue("{}");
    client.finishPolicyAction.mockRejectedValue({ status: 409 });
    expect(await screen.findByRole("alert")).toHaveTextContent("The policy changed");
    expect(screen.queryByText(/APPROVED committed/)).not.toBeInTheDocument();
  });

  it("clears actor-specific selection on a session boundary remount", async () => {
    const first = api();
    const rendered = render(<PolicyGovernancePage key="reviewer" apiClient={first} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · SUBMITTED/ }));
    expect(screen.getByText(/Policy detail/)).toBeInTheDocument();
    const second = api({ actorId: author, authorities: ["model-policy:read"] });
    rendered.rerender(<PolicyGovernancePage key="author" apiClient={second} enabled />);
    await waitFor(() => expect(second.listPolicyProposals).toHaveBeenCalled());
    expect(screen.queryByText(/Policy detail/)).not.toBeInTheDocument();
  });
});
