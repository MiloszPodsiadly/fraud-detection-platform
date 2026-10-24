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
  authorActorId: author, approvalEvidenceReference: null, exactThresholds: {}
});
function api({ actorId = reviewer, authorities = ["model-policy:read", "model-policy:approve", "model-policy:reject", "model-policy:audit:read"],
  items = [proposal()], publication = { state: "PUBLISHED", signatureStatus: "VERIFIED",
    approvalEvidenceStatus: "UNVERIFIED", verificationStatus: "UNVERIFIED", verificationReason: "EVIDENCE_MISSING" } } = {}) {
  return {
    getPolicyGovernanceActor: vi.fn().mockResolvedValue({ actorId, authorities }),
    getPolicyPage: vi.fn().mockResolvedValue({ items, nextCursor: null }),
    getPolicySchema: vi.fn().mockResolvedValue([]),
    listPolicyApprovers: vi.fn().mockResolvedValue([]),
    getPolicyOperation: vi.fn().mockRejectedValue({ status: 404 }),
    getPolicyHistoryPage: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    getPolicyPublication: vi.fn().mockResolvedValue(publication),
    getPolicyPublicationRecovery: vi.fn().mockResolvedValue({ publicationRequestId: "job-1",
      state: "BLOCKED", attempts: 5, recoveryCount: 0, retrySafe: true,
      alreadyCommitted: false, attemptHistory: [], recoveryHistory: [] }),
    requestPolicyPublicationRecovery: vi.fn().mockResolvedValue({ publicationRequestId: "job-1",
      state: "RETRYABLE", attempts: 5, recoveryCount: 1, retrySafe: false,
      alreadyCommitted: false, attemptHistory: [], recoveryHistory: [] }),
    startPolicyAction: vi.fn().mockResolvedValue({ challengeId: "action:op", optionsJson: "{}" }),
    finishPolicyAction: vi.fn().mockResolvedValue({ ...items[0], status: "APPROVED", version: 2 }),
    requestPolicyPublication: vi.fn().mockResolvedValue(items[0]),
    listPolicyCredentials: vi.fn().mockResolvedValue([])
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  window.sessionStorage.clear();
  vi.stubGlobal("confirm", vi.fn(() => true));
});

describe("Policy Governance workspace", () => {
  it("shows the author editor without inventing reviewer rights from analyst roles", async () => {
    const client = api({ actorId: author, authorities: ["model-policy:read", "model-policy:create", "model-policy:submit"],
      items: [proposal("DRAFT")] });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · DRAFT/ }));
    expect(screen.getByRole("textbox", { name: /Policy ID/ })).toHaveValue("risk-policy");
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

  it("renders the server exact threshold string instead of the lossy JSON number", async () => {
    const item = proposal();
    item.draft = { ...draft, criteria: [{ criterion: "FALSE_POSITIVE_RATE", maximumEvidenceAgeSeconds: 60, thresholds: {
      maximumRate: 0.12345678901234568 } }] };
    item.exactThresholds = { FALSE_POSITIVE_RATE: { maximumRate: "0.12345678901234567890" } };
    const client = api({ items: [item] });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy.*SUBMITTED/ }));
    expect(screen.getByText(/0\.12345678901234567890/)).toBeInTheDocument();
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

  it("offers bounded recovery only with the dedicated mandate", async () => {
    const client = api({ actorId: "oidc:" + "c".repeat(64),
      authorities: ["model-policy:read", "model-policy:publication:recover"],
      items: [proposal("PUBLICATION_PENDING")], publication: {
        requestId: "job-1", state: "BLOCKED", failureCode: "ATTEMPTS_EXHAUSTED",
        verificationStatus: "UNAVAILABLE" } });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy.*revision 1.*PUBLICATION_PENDING/ }));
    expect(await screen.findByText(/Publication is blocked/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Request recovery" }));
    await waitFor(() => expect(client.requestPolicyPublicationRecovery).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/Recovery queued for worker/)).toBeInTheDocument();
  });

  it("shows an uncertain claimed job without presenting it as published", async () => {
    const client = api({ actorId: "oidc:" + "c".repeat(64),
      authorities: ["model-policy:read", "model-policy:publication:recover"],
      items: [proposal("PUBLICATION_PENDING")], publication: {
        requestId: "job-1", state: "CLAIMED", signatureStatus: null,
        approvalEvidenceStatus: null, verificationStatus: "UNAVAILABLE" } });
    client.getPolicyPublicationRecovery.mockResolvedValue({ publicationRequestId: "job-1",
      state: "CLAIMED", attempts: 1, recoveryCount: 0, retrySafe: false,
      alreadyCommitted: false, attemptHistory: [{ id: "job-1:1", attempt: 1,
        outcome: "LEASE_EXPIRED_UNCONFIRMED", failureCode: null }], recoveryHistory: [] });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy.*revision 1.*PUBLICATION_PENDING/ }));
    expect(await screen.findByText(/commit outcome may be uncertain/i)).toBeInTheDocument();
    expect(screen.getByText(/Attempt 1: LEASE_EXPIRED_UNCONFIRMED/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Request recovery" })).toBeDisabled();
    expect(screen.queryByText(/publication commit and job acknowledgement are confirmed/i))
      .not.toBeInTheDocument();
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
    expect(await screen.findByRole("alert")).toHaveTextContent("Outcome uncertain");
    expect(screen.getByRole("button", { name: "Check operation status" })).toBeInTheDocument();
    expect(screen.queryByText(/APPROVED committed/)).not.toBeInTheDocument();
  });

  it("recovers a lost approval response from the durable operation receipt", async () => {
    const client = api();
    confirmPolicyAssertion.mockResolvedValue("{}");
    client.finishPolicyAction.mockRejectedValue(new Error("network response lost"));
    client.getPolicyOperation.mockResolvedValue({ ...proposal("APPROVED"), version: 2 });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy.*SUBMITTED/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Rationale" }),
      { target: { value: "Independent review" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve with WebAuthn" }));
    expect(await screen.findByText(/APPROVED committed/)).toBeInTheDocument();
    expect(client.getPolicyOperation).toHaveBeenCalledWith(
      client.finishPolicyAction.mock.calls[0][2].operationId);
    expect(client.finishPolicyAction).toHaveBeenCalledTimes(1);
  });

  it("does not call a conflicting receipt an approval", async () => {
    const client = api();
    confirmPolicyAssertion.mockResolvedValue("signed-response");
    client.finishPolicyAction.mockRejectedValue(new Error("network response lost"));
    client.getPolicyOperation.mockResolvedValue(proposal("REJECTED"));
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy.*SUBMITTED/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Rationale" }),
      { target: { value: "Independent review" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve with WebAuthn" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("conflicts with the intended action");
    expect(screen.queryByText(/APPROVED committed/)).not.toBeInTheDocument();
    expect(JSON.stringify(window.sessionStorage)).not.toContain("signed-response");
  });

  it("keeps an uncertain command in memory and checks the same ID on retry", async () => {
    const client = api();
    confirmPolicyAssertion.mockResolvedValue("{}");
    client.finishPolicyAction.mockRejectedValue(new Error("network response lost"));
    client.getPolicyOperation.mockRejectedValueOnce({ status: 404 })
      .mockResolvedValue({ ...proposal("APPROVED"), version: 2 });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy.*SUBMITTED/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Rationale" }),
      { target: { value: "Independent review" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve with WebAuthn" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Outcome uncertain");
    const operationId = client.finishPolicyAction.mock.calls[0][2].operationId;
    fireEvent.click(screen.getByRole("button", { name: "Check operation status" }));
    expect(await screen.findByText(/Committed receipt recovered/)).toBeInTheDocument();
    expect(client.getPolicyOperation).toHaveBeenLastCalledWith(operationId);
    expect(client.finishPolicyAction).toHaveBeenCalledTimes(1);
  });

  it("restores a read-only approval check after a browser view remount", async () => {
    const client = api();
    confirmPolicyAssertion.mockResolvedValue("signed-response");
    client.finishPolicyAction.mockRejectedValue(new Error("offline"));
    client.getPolicyOperation.mockRejectedValue({ status: 404 });
    const view = render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy.*SUBMITTED/ }));
    fireEvent.change(screen.getByRole("textbox", { name: "Rationale" }),
      { target: { value: "Independent review" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve with WebAuthn" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Outcome uncertain");
    const operationId = client.finishPolicyAction.mock.calls[0][2].operationId;
    view.unmount();
    render(<PolicyGovernancePage apiClient={client} enabled />);
    expect(await screen.findByRole("button", { name: "Check operation status" })).toBeInTheDocument();
    expect(window.sessionStorage.getItem("policy-governance-pending-operation")).toContain(operationId);
    expect(window.sessionStorage.getItem("policy-governance-pending-operation")).not.toContain("signed-response");
    expect(client.finishPolicyAction).toHaveBeenCalledTimes(1);
  });

  it("clears actor-specific selection on a session boundary remount", async () => {
    const first = api();
    const rendered = render(<PolicyGovernancePage key="reviewer" apiClient={first} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: /risk-policy · revision 1 · SUBMITTED/ }));
    expect(screen.getByText(/Policy detail/)).toBeInTheDocument();
    const second = api({ actorId: author, authorities: ["model-policy:read"] });
    rendered.rerender(<PolicyGovernancePage key="author" apiClient={second} enabled />);
    await waitFor(() => expect(second.getPolicyPage).toHaveBeenCalled());
    expect(screen.queryByText(/Policy detail/)).not.toBeInTheDocument();
  });

  it("loads a bounded next page without losing the first page", async () => {
    const client = api({ items: [proposal("DRAFT")] });
    client.getPolicyPage.mockResolvedValueOnce({ items: [proposal("DRAFT")], nextCursor: "cursor-a" })
      .mockResolvedValueOnce({ items: [{ ...proposal("SUBMITTED"), id: "proposal-2" }], nextCursor: null });
    render(<PolicyGovernancePage apiClient={client} enabled />);
    fireEvent.click(await screen.findByRole("button", { name: "Load more policies" }));
    expect(await screen.findByRole("button", { name: /risk-policy.*SUBMITTED/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /risk-policy.*DRAFT/ })).toBeInTheDocument();
    expect(client.getPolicyPage.mock.calls[1][0]).toMatchObject({ size: 25, cursor: "cursor-a" });
  });
});
