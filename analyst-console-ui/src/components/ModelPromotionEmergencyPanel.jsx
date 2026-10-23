import { useState } from "react";
import { createIdempotencyKey } from "../utils/idempotencyKey.js";
import { useOptionalWorkspaceRuntime } from "../workspace/useWorkspaceRuntime.js";

/** Exceptional OIDC two-person route; normal retirement still uses the signed review button above. */
export function ModelPromotionEmergencyPanel({ workflow, state, pending = [], canRequest, canAuthorize, canAudit }) {
  const client = useOptionalWorkspaceRuntime()?.apiClient;
  const retirementProposals = pending.filter((proposal) => proposal.requestedTargetState === "RETIRED");
  const [proposalId, setProposalId] = useState("");
  const [incidentId, setIncidentId] = useState("");
  const [evidenceReference, setEvidenceReference] = useState("");
  const [reason, setReason] = useState("");
  const [requestCommand, setRequestCommand] = useState(null);
  const [requested, setRequested] = useState(null);
  const [authorizationId, setAuthorizationId] = useState("");
  const [inspected, setInspected] = useState(null);
  const [authorizationRationale, setAuthorizationRationale] = useState("");
  const [issued, setIssued] = useState(null);
  const [grantToken, setGrantToken] = useState("");
  const [auditAuthorizationId, setAuditAuthorizationId] = useState("");
  const [audit, setAudit] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState("");
  const selectedProposal = retirementProposals.find((proposal) => proposal.proposalId === proposalId);
  const canRequestHere = canRequest === true && state?.governanceScope?.environment === "PRODUCTION"
    && retirementProposals.length > 0;

  async function perform(action) {
    if (!client || busy) return;
    setBusy(true); setError(null); setNotice("");
    try { await action(); }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }

  function request() {
    return perform(async () => {
      if (!selectedProposal || !incidentId.trim() || !evidenceReference.trim() || reason.trim().length < 20) {
        throw new Error("Choose a pending production retirement and provide incident evidence and a specific reason.");
      }
      const command = requestCommand || {
        authorizationId: createIdempotencyKey("emergency-retirement-authorization"),
        reviewOperationId: createIdempotencyKey("emergency-retirement-review"),
        approvalId: createIdempotencyKey("emergency-retirement-approval"),
        incidentId: incidentId.trim(), incidentEvidenceReference: evidenceReference.trim(), reason: reason.trim()
      };
      setRequestCommand(command);
      const result = await client.requestEmergencyModelRetirement(selectedProposal.proposalId, command);
      setRequested(result); setAuthorizationId(result.authorizationId); setRequestCommand(null);
      setNotice("Request recorded. A different emergency authorizer must inspect the exact intent and issue a short-lived grant.");
    });
  }

  function inspect() {
    return perform(async () => {
      setIssued(null);
      const result = await client.inspectEmergencyModelRetirement(authorizationId.trim());
      setInspected(result);
      setNotice("Compare the incident evidence, exact artifact SHA-256, production scope and revision before authorizing.");
    });
  }

  function authorize() {
    return perform(async () => {
      if (!inspected || inspected.authorizationId !== authorizationId.trim()
          || inspected.status !== "REQUESTED" || authorizationRationale.trim().length < 20) {
        throw new Error("Inspect a pending request and record an independent evidence-review rationale first.");
      }
      const result = await client.authorizeEmergencyModelRetirement(inspected.authorizationId, {
        intentSha256: inspected.intentSha256,
        incidentEvidenceReference: inspected.incidentEvidenceReference,
        authorizationRationale: authorizationRationale.trim()
      });
      setIssued(result);
      setNotice("Grant issued once. Deliver the token to the requester through a controlled channel before expiry.");
    });
  }

  function revoke() {
    return perform(async () => {
      if (!inspected || inspected.authorizationId !== authorizationId.trim()) {
        throw new Error("Inspect the exact authorization before revoking it.");
      }
      await client.revokeEmergencyModelRetirement(inspected.authorizationId);
      setIssued(null); setInspected(null);
      setNotice("Emergency authorization revoked; it cannot be used for retirement.");
    });
  }

  function retire() {
    return perform(async () => {
      if (!selectedProposal || !authorizationId.trim() || !grantToken.trim()) {
        throw new Error("Select the bound retirement proposal and enter the issued grant ID and token.");
      }
      await client.consumeEmergencyModelRetirement(selectedProposal.proposalId,
        { authorizationId: authorizationId.trim(), token: grantToken.trim() });
      setGrantToken(""); setRequested(null);
      setNotice("Emergency retirement committed. Confirm the authoritative lifecycle and audit record.");
      await workflow.load();
    });
  }

  function loadAudit() {
    return perform(async () => {
      setAudit(null);
      setAudit(await client.auditEmergencyModelRetirement(auditAuthorizationId.trim()));
    });
  }

  if (!canRequestHere && canAuthorize !== true && canAudit !== true) return null;
  return <section className="shadowPerformanceSubpanel" aria-label="Emergency model retirement">
    <h3>Exceptional production retirement</h3>
    <p className="sectionCopy">Use the normal signed review when WebAuthn works. If it is unavailable, a dedicated requester and a different institutional authorizer must approve a five-minute, exact-model grant. An incident ID or rationale alone never bypasses WebAuthn.</p>
    {canRequestHere && <div className="shadowPerformanceStack">
      <label className="filterField"><span>Pending RETIRED proposal</span><select value={proposalId} onChange={(event) => { setProposalId(event.target.value); setRequestCommand(null); }}><option value="">Select proposal</option>{retirementProposals.map((proposal) => <option key={proposal.proposalId} value={proposal.proposalId}>{proposal.proposalId}</option>)}</select></label>
      <label className="filterField"><span>Incident / case ID</span><input value={incidentId} onChange={(event) => { setIncidentId(event.target.value); setRequestCommand(null); }} /></label>
      <label className="filterField"><span>Incident evidence reference</span><input value={evidenceReference} onChange={(event) => { setEvidenceReference(event.target.value); setRequestCommand(null); }} /></label>
      <label className="filterField"><span>Specific emergency reason</span><textarea value={reason} onChange={(event) => { setReason(event.target.value); setRequestCommand(null); }} /></label>
      <button className="secondaryButton" type="button" disabled={busy} onClick={request}>{requestCommand ? "Retry the same request" : "Request independent emergency authorization"}</button>
      {requested && <p role="status">Authorization ID: <code>{requested.authorizationId}</code>; expires {requested.expiresAt}.</p>}
      <label className="filterField"><span>Approved authorization ID</span><input value={authorizationId} onChange={(event) => setAuthorizationId(event.target.value)} /></label>
      <label className="filterField"><span>One-time grant token</span><input type="password" value={grantToken} onChange={(event) => setGrantToken(event.target.value)} autoComplete="off" /></label>
      <button className="secondaryButton" type="button" disabled={busy} onClick={retire}>Consume grant and retire exact model</button>
    </div>}
    {canAuthorize === true && <div className="shadowPerformanceStack">
      {!canRequestHere && <label className="filterField"><span>Emergency authorization ID</span><input value={authorizationId} onChange={(event) => { setAuthorizationId(event.target.value); setInspected(null); }} /></label>}
      <button className="secondaryButton" type="button" disabled={busy || !authorizationId.trim()} onClick={inspect}>Inspect exact authorization</button>
      {inspected && <dl className="shadowPerformanceDefinitionList">
        <div><dt>Exact model</dt><dd>{inspected.artifactIdentity.modelName} / {inspected.artifactIdentity.modelVersion}</dd></div>
        <div><dt>Artifact SHA-256</dt><dd>{inspected.artifactIdentity.modelArtifactSha256}</dd></div>
        <div><dt>Scope / state / revision</dt><dd>{inspected.governanceScope.institutionalScope} / {inspected.governanceScope.environment} / {inspected.currentState} / {inspected.currentRevision}</dd></div>
        <div><dt>Required outcome</dt><dd>{inspected.requiredOutcome}</dd></div>
        <div><dt>Incident and evidence</dt><dd>{inspected.incidentId} / {inspected.incidentEvidenceReference}</dd></div>
        <div><dt>Reason</dt><dd>{inspected.reason}</dd></div>
        <div><dt>Requester</dt><dd>{inspected.requesterActorId}</dd></div>
        <div><dt>Expiry</dt><dd>{inspected.expiresAt}</dd></div>
        <div><dt>Exact intent SHA-256</dt><dd>{inspected.intentSha256}</dd></div>
      </dl>}
      {inspected?.status === "REQUESTED" && <><label className="filterField"><span>Independent incident-evidence review rationale</span><textarea value={authorizationRationale} onChange={(event) => setAuthorizationRationale(event.target.value)} /></label><button className="secondaryButton" type="button" disabled={busy} onClick={authorize}>Authorize exact emergency grant</button><button className="secondaryButton" type="button" disabled={busy} onClick={revoke}>Revoke request</button></>}
      {issued && <p role="status">One-time token (deliver securely): <code>{issued.token}</code>; expires {issued.expiresAt}.</p>}
    </div>}
    {canAudit === true && <div className="shadowPerformanceStack">
      <h4>Emergency retirement audit</h4>
      <label className="filterField"><span>Authorization ID</span><input value={auditAuthorizationId} onChange={(event) => { setAuditAuthorizationId(event.target.value); setAudit(null); }} /></label>
      <button className="secondaryButton" type="button" disabled={busy || !auditAuthorizationId.trim()} onClick={loadAudit}>Verify recorded emergency decision</button>
      {audit && <dl className="shadowPerformanceDefinitionList">
        <div><dt>Proof status</dt><dd>{audit.proofStatus}</dd></div>
        <div><dt>Incident / evidence</dt><dd>{audit.incidentId} / {audit.incidentEvidenceReference}</dd></div>
        <div><dt>Emergency reason</dt><dd>{audit.reason}</dd></div>
        <div><dt>Independent authorization rationale</dt><dd>{audit.authorizationRationale || "Not authorized"}</dd></div>
        <div><dt>Requester / authorizer</dt><dd>{audit.requesterActorId} / {audit.authorizedByActorId || "None"}</dd></div>
        <div><dt>Grant status</dt><dd>{audit.status}</dd></div>
      </dl>}
    </div>}
    {error && <p role="alert">{error.message || "Emergency operation did not complete. Inspect authoritative status before creating another request."}</p>}
    {notice && <p role="status">{notice}</p>}
  </section>;
}
