import { useCallback, useEffect, useRef, useState } from "react";
import { confirmPolicyAssertion, registerPolicyCredential } from "../governance/policyWebAuthn.js";
import { MODEL_POLICY_AUTHORITIES as POLICY } from "../auth/modelPolicyAuthorities.js";

const blankIntent = {
  registry: "ONLINE_ACTORS", action: "GRANT", targetIdentity: "", keyId: "",
  publicKeySha256: "", publicKeySpkiBase64: "", roles: "POLICY_APPROVER",
  environment: "TEST", intendedUse: "FRAUD_RISK_SCORING",
  caseReference: "", targetIssuer: "", targetSubject: "", displayName: "",
  validFrom: "", validUntil: "", replacesKeyId: "", retireAt: ""
};
const id = () => crypto.randomUUID();
const can = (actor, authority) => actor?.authorities?.includes(authority);
const explain = (error) => error?.message || "The security operation could not be completed.";
const PENDING_KEY = "policy-security-pending-operation";
const AUDIT_RECOVERY_PENDING_KEY = "policy-audit-recovery-pending-operation";
function rememberSecurityPending(pending) {
  try { sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending)); }
  catch { /* The open view still retains the operation. */ }
}
function storedSecurityPending(actorId) {
  try {
    const value = JSON.parse(sessionStorage.getItem(PENDING_KEY) || "null");
    return value?.actorId === actorId && /^[A-Za-z0-9._:-]{1,128}$/.test(value.operationId)
      && ["credentialRevoke", "grantRevoke", "grantReview", "trustRequest", "trustReview"].includes(value.kind)
      ? value : null;
  } catch { return null; }
}
function forgetSecurityPending(operationId) {
  try {
    if (JSON.parse(sessionStorage.getItem(PENDING_KEY) || "null")?.operationId === operationId)
      sessionStorage.removeItem(PENDING_KEY);
  } catch { /* Browser storage is optional. */ }
}
function storedAuditRecoveryPending(actorId) {
  try {
    const value = JSON.parse(sessionStorage.getItem(AUDIT_RECOVERY_PENDING_KEY) || "null");
    return value?.actorId === actorId
      && /^[A-Za-z0-9._:-]{1,128}$/.test(value.operationId)
      && typeof value.outboxId === "string" && value.outboxId.length <= 256
      && typeof value.eventKind === "string" && typeof value.eventId === "string"
      && typeof value.reason === "string" && value.reason.trim().length > 0 && value.reason.length <= 500
      && ["RECONCILE", "RETRY"].includes(value.action) ? value : null;
  } catch { return null; }
}
function rememberAuditRecoveryPending(pending) {
  try { sessionStorage.setItem(AUDIT_RECOVERY_PENDING_KEY, JSON.stringify(pending)); }
  catch { /* Current view still retains the operation. */ }
}
function forgetAuditRecoveryPending(operationId) {
  try {
    if (JSON.parse(sessionStorage.getItem(AUDIT_RECOVERY_PENDING_KEY) || "null")?.operationId === operationId)
      sessionStorage.removeItem(AUDIT_RECOVERY_PENDING_KEY);
  } catch { /* Browser storage is optional. */ }
}

async function readSecurityReceipt(apiClient, pending) {
  const { operationId, actorId } = pending;
  if (pending.kind === "credentialRevoke")
    return (await apiClient.listPolicyCredentialSecurityEvents()).find((event) =>
      event.id === `credential-revoke:${operationId}` && event.actorId === actorId
        && event.credentialId === pending.credentialId && event.action === "CREDENTIAL_REVOKED");
  if (pending.kind === "grantReview") {
    const receipt = await apiClient.getPolicyCredentialGrantReview(pending.requestId);
    return receipt.reviewOperationId === operationId && receipt.reviewedBy === actorId
      && (pending.decision === "APPROVE" ? receipt.approved : receipt.rejected) ? receipt : null;
  }
  if (pending.kind === "grantRevoke") {
    const receipt = await apiClient.getPolicyCredentialGrantStatus(pending.requestId);
    return receipt.state === "REVOKED" && receipt.revocationOperationId === operationId
      && receipt.revokedBy === actorId ? receipt : null;
  }
  if (pending.kind === "trustRequest")
    return (await apiClient.listPolicyTrustChanges()).find((entry) =>
      entry.id === operationId && entry.requestedBy === actorId
        && entry.candidateSha256 === pending.candidateSha256);
  if (pending.kind === "trustReview")
    return (await apiClient.listPolicyTrustChanges()).find((entry) =>
      entry.id === pending.requestId && entry.reviewedBy === actorId
        && entry.reviewProofId === `trust-review:${operationId}`
        && entry.status === (pending.decision === "APPROVE" ? "PENDING_OPERATOR_ACTION" : "REJECTED"));
  return null;
}

export function PolicySecurityPage({ apiClient, enabled }) {
  const [actor, setActor] = useState(null);
  const [credentials, setCredentials] = useState([]);
  const [labels, setLabels] = useState({});
  const [events, setEvents] = useState([]);
  const [registries, setRegistries] = useState([]);
  const [changes, setChanges] = useState([]);
  const [intent, setIntent] = useState(blankIntent);
  const [grantToken, setGrantToken] = useState("");
  const [claimedPurpose, setClaimedPurpose] = useState(null);
  const [myGrants, setMyGrants] = useState([]);
  const [grantReview, setGrantReview] = useState(null);
  const [grant, setGrant] = useState({ targetIssuer: "", targetSubject: "", purpose: "FIRST", caseReference: "" });
  const [approvalId, setApprovalId] = useState("");
  const [grantStatus, setGrantStatus] = useState(null);
  const [revocationReason, setRevocationReason] = useState("");
  const [revocationCase, setRevocationCase] = useState("");
  const [incidentActorId, setIncidentActorId] = useState("");
  const [incidentPage, setIncidentPage] = useState(null);
  const [lostId, setLostId] = useState("");
  const [compromised, setCompromised] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [audit, setAudit] = useState([]);
  const [deployment, setDeployment] = useState(null);
  const [candidatePreview, setCandidatePreview] = useState(null);
  const [previewedIntent, setPreviewedIntent] = useState("");
  const [reviewCandidate, setReviewCandidate] = useState(null);
  const [operations, setOperations] = useState(null);
  const [auditRecovery, setAuditRecovery] = useState([]);
  const [auditRecoveryHistory, setAuditRecoveryHistory] = useState([]);
  const [auditRecoveryReasons, setAuditRecoveryReasons] = useState({});
  const [auditRecoveryPending, setAuditRecoveryPending] = useState(null);
  const [uncertain, setUncertain] = useState(null);

  const refresh = useCallback(async () => {
    const me = await apiClient.getPolicySecurityActor();
    const stored = storedSecurityPending(me.actorId);
    if (stored) setUncertain((current) => current || stored);
    const storedRecovery = storedAuditRecoveryPending(me.actorId);
    setAuditRecoveryPending(storedRecovery);
    setActor(me);
    const [devices, decisions, publicRegistries, requests, diagnostics, approvedGrants, recoveryQueue, recoveryHistory] = await Promise.all([
      can(me, POLICY.CREDENTIAL_ENROLL) ? apiClient.listPolicyCredentials() : [],
      can(me, POLICY.CREDENTIAL_ENROLL) ? apiClient.listPolicyCredentialSecurityEvents() : [],
      can(me, POLICY.TRUST_READ) ? apiClient.getPolicyTrustRegistries() : [],
      can(me, POLICY.TRUST_READ) ? apiClient.listPolicyTrustChanges() : [],
      can(me, POLICY.TRUST_READ) && can(me, POLICY.PRODUCTION)
        ? apiClient.getPolicySecurityOperations() : null,
      can(me, POLICY.CREDENTIAL_ENROLL) ? apiClient.listMyPolicyCredentialGrants() : [],
      can(me, POLICY.AUDIT_RECOVER) && can(me, POLICY.AUDIT_READ) && can(me, POLICY.PRODUCTION)
        ? apiClient.listAuditRecoveryObligations() : [],
      can(me, POLICY.AUDIT_RECOVER) && can(me, POLICY.AUDIT_READ) && can(me, POLICY.PRODUCTION)
        ? apiClient.listAuditRecoveryHistory() : []
    ]);
    setCredentials(devices);
    setEvents(decisions);
    setRegistries(publicRegistries);
    setChanges(requests);
    setOperations(diagnostics);
    setAuditRecovery(recoveryQueue);
    setAuditRecoveryHistory(recoveryHistory);
    setMyGrants(approvedGrants);
  }, [apiClient]);

  useEffect(() => {
    if (!enabled || !apiClient) return;
    let live = true;
    refresh().catch((failure) => { if (live) setError(explain(failure)); });
    return () => { live = false; };
  }, [apiClient, enabled, refresh]);

  useEffect(() => {
    setGrantToken("");
    setClaimedPurpose(null);
  }, [actor?.actorId]);

  async function run(operation) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      if (uncertain) throw new Error(`Resolve operation ${uncertain.operationId} before starting another security mutation.`);
      await operation();
      try { await refresh(); }
      catch { setError("Action response was received, but security state could not be refreshed. Refresh the view before proceeding."); }
    }
    catch (failure) { setError(explain(failure)); }
    finally { busyRef.current = false; setBusy(false); }
  }

  async function submitAuditRecovery(operation) {
    setAuditRecoveryPending(operation);
    rememberAuditRecoveryPending(operation);
    await run(async () => {
      const receipt = await apiClient.recoverAuditObligation(operation.eventKind, operation.eventId, {
        operationId: operation.operationId, reason: operation.reason, action: operation.action
      });
      setAuditRecoveryPending(null);
      forgetAuditRecoveryPending(operation.operationId);
      setNotice(`Audit recovery ${operation.operationId}: ${receipt.classification}; receipt ${receipt.receiptSha256}.`);
      setAuditRecoveryReasons((current) => ({ ...current, [operation.outboxId]: "" }));
    });
  }

  async function settleSigned(operationId, send, receiptSpec, label) {
    const pending = { operationId, actorId: actor?.actorId, label, ...receiptSpec };
    try { return await send(); }
    catch (failure) {
      if (failure?.status && failure.status < 500 && failure.status !== 408 && failure.status !== 429)
        throw failure;
      try {
        const receipt = await readSecurityReceipt(apiClient, pending);
        if (receipt) return receipt;
      } catch { /* The outcome remains uncertain. */ }
      setUncertain(pending);
      rememberSecurityPending(pending);
      throw new Error(`Outcome uncertain for ${label} operation ${operationId}. Check its durable receipt before a new action.`);
    }
  }

  async function checkUncertain() {
    if (!uncertain || busyRef.current) return;
    busyRef.current = true;
    setBusy(true); setError("");
    try {
      if (uncertain.actorId !== actor?.actorId)
        throw new Error("The signed-in actor changed; this receipt must be checked in its original session.");
      const receipt = await readSecurityReceipt(apiClient, uncertain);
      if (!receipt) throw new Error(`Operation ${uncertain.operationId} remains uncertain.`);
      setUncertain(null);
      forgetSecurityPending(uncertain.operationId);
      setNotice(`${uncertain.label} operation ${uncertain.operationId} has a durable receipt.`);
      await refresh();
    } catch (failure) { setError(explain(failure)); }
    finally { busyRef.current = false; setBusy(false); }
  }

  async function revoke(credential) {
    if (!window.confirm(`Revoke credential ${credential.label || credential.credentialId}?`)) return;
    await run(async () => {
      const operationId = id();
      const challenge = await apiClient.startPolicyCredentialRevocation(credential.credentialId, operationId);
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      await settleSigned(operationId,
        () => apiClient.finishPolicyCredentialRevocation(credential.credentialId,
          { operationId, challengeId: challenge.challengeId, responseJson }),
        { kind: "credentialRevoke", credentialId: credential.credentialId },
        "credential revocation");
      setNotice("Credential revoked. It can no longer sign new actions.");
    });
  }

  async function enrollWithToken(token) {
      if (!token) throw new Error("An independently approved enrollment grant is required.");
      const challenge = await apiClient.startPolicyCredentialRegistration(token);
      const responseJson = await registerPolicyCredential(challenge.optionsJson);
      await apiClient.finishPolicyCredentialRegistration({ challengeId: challenge.challengeId, responseJson, grantToken: token });
      setGrantToken(""); setClaimedPurpose(null); setNotice("Passkey enrolled.");
  }

  async function claimGrant(entry) {
    await run(async () => {
      const issued = await apiClient.claimPolicyCredentialGrant(entry.requestId);
      setGrantToken(issued.token);
      setClaimedPurpose(entry.purpose);
      if (["FIRST", "ADDITIONAL", "LOST_DEVICE_RECOVERY", "COMPROMISE_RECOVERY"].includes(entry.purpose)) {
        await enrollWithToken(issued.token);
      } else {
        setNotice("Revocation authorization claimed. Enter the credential ID and complete revocation before expiry.");
      }
    });
  }

  async function reviewGrant(decision) {
    await run(async () => {
      if (!grantReview || grantReview.approved || grantReview.rejected)
        throw new Error("Inspect an unreviewed exact grant first.");
      const fresh = await apiClient.getPolicyCredentialGrantReview(grantReview.requestId);
      if (JSON.stringify(fresh) !== JSON.stringify(grantReview))
        throw new Error("Grant request changed; inspect it again.");
      if (!window.confirm(`${decision} ${fresh.purpose} for ${fresh.targetIssuer} / ${fresh.targetSubject}, case ${fresh.caseReference}?`)) return;
      const intent = { operationId: id(), decision };
      const challenge = await apiClient.startPolicyCredentialGrantReview(fresh.requestId, intent);
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      await settleSigned(intent.operationId,
        () => apiClient.finishPolicyCredentialGrantReview(fresh.requestId,
          { intent, challengeId: challenge.challengeId, responseJson }),
        { kind: "grantReview", requestId: fresh.requestId, decision }, "credential grant review");
      setGrantReview(null);
      setNotice(`${decision} recorded. Only the target can claim the grant.`);
    });
  }

  function preparedIntent() {
    const registry = registries.find((entry) => entry.registry === intent.registry);
    if (!registry || registry.status !== "READY") throw new Error("Current public registry is not ready.");
    return {
      ...intent, targetIdentity: intent.targetIdentity.trim(),
      keyId: intent.registry === "ONLINE_ACTORS" ? null : intent.keyId.trim(),
      publicKeySha256: intent.registry === "ONLINE_ACTORS" ? null : intent.publicKeySha256.trim(),
      publicKeySpkiBase64: intent.registry === "ONLINE_ACTORS" || !["ADD", "ROTATE"].includes(intent.action)
        ? null : intent.publicKeySpkiBase64.trim(),
      roles: intent.roles.split(",").map((role) => role.trim()).filter(Boolean),
      requestedGeneration: Number(registry.generation) + 1,
      candidateRegistrySha256: null,
      caseReference: intent.caseReference.trim(),
      targetIssuer: intent.registry === "ONLINE_ACTORS" ? intent.targetIssuer.trim() || null : null,
      targetSubject: intent.registry === "ONLINE_ACTORS" ? intent.targetSubject.trim() || null : null,
      displayName: intent.registry === "ONLINE_ACTORS" ? intent.displayName.trim() || null : null,
      validFrom: ["ADD", "ROTATE", "GRANT"].includes(intent.action) ? intent.validFrom.trim() : null,
      validUntil: ["ADD", "ROTATE", "GRANT"].includes(intent.action) ? intent.validUntil.trim() : null,
      replacesKeyId: intent.action === "ROTATE" ? intent.replacesKeyId.trim() : null,
      retireAt: ["RETIRE", "ROTATE"].includes(intent.action) ? intent.retireAt.trim() : null
    };
  }

  async function previewChange() {
    await run(async () => {
      const candidate = preparedIntent();
      const preview = await apiClient.previewPolicyTrustChange(candidate);
      setCandidatePreview(preview);
      setPreviewedIntent(JSON.stringify(candidate));
    });
  }

  async function submitChange() {
    await run(async () => {
      const candidate = preparedIntent();
      if (!candidatePreview || previewedIntent !== JSON.stringify(candidate)) {
        throw new Error("Preview the current exact registry diff before signing.");
      }
      if (!window.confirm(`Sign the displayed ${candidate.action} candidate ${candidatePreview.candidateSha256}? Approval will not activate it.`)) return;
      const boundCandidate = { ...candidate, candidateRegistrySha256: candidatePreview.candidateSha256 };
      const operationId = id();
      const challenge = await apiClient.startPolicyTrustChange({ operationId, intent: boundCandidate });
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      const result = await settleSigned(operationId,
        () => apiClient.finishPolicyTrustChange({ operationId, intent: boundCandidate,
          challengeId: challenge.challengeId, responseJson }),
        { kind: "trustRequest", candidateSha256: candidatePreview.candidateSha256 }, "trust change request");
      setNotice(`Request ${result.id} is pending independent review; trust is unchanged.`);
      setCandidatePreview(null); setPreviewedIntent("");
    });
  }

  async function reviewChange(change, decision) {
    await run(async () => {
      if (!reviewCandidate || reviewCandidate.requestId !== change.id
          || reviewCandidate.candidateSha256 !== change.candidateSha256) {
        throw new Error("Inspect the exact stored candidate diff before reviewing.");
      }
      const current = await apiClient.getPolicyTrustCandidate(change.id);
      if (current.candidateSha256 !== reviewCandidate.candidateSha256
          || current.requestStatus !== "PENDING_REVIEW") {
        throw new Error("Trust candidate changed or is no longer pending.");
      }
      if (!window.confirm(`${decision} the displayed exact candidate ${current.candidateSha256}? This does not activate trust.`)) return;
      const operationId = id();
      const challenge = await apiClient.startPolicyTrustReview(change.id, { operationId, decision });
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      const result = await settleSigned(operationId,
        () => apiClient.finishPolicyTrustReview(change.id,
          { decision, challengeId: challenge.challengeId, responseJson }),
        { kind: "trustReview", requestId: change.id, decision },
        "trust change review");
      setNotice(`Change ${result.id}: ${result.status}. Deployment acknowledgement is still required.`);
      setReviewCandidate(null);
    });
  }

  if (!enabled) return <p>Security workspace access is unavailable.</p>;
  return <section className="policy-security-page">
    <h2>Settings / Security &amp; Operations</h2>
    <p>Institutional actions require a registered passkey and dedicated mandates. Approval is not deployment.</p>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {uncertain && <p role="status">Operation {uncertain.operationId} is unresolved. Its passkey assertion is not retained.
      <button type="button" disabled={busy} onClick={checkUncertain}>Check durable security receipt</button></p>}
    <button type="button" disabled={busy} onClick={() => refresh().catch((failure) => setError(explain(failure)))}>Refresh security state</button>

    {can(actor, POLICY.CREDENTIAL_ENROLL) && <section className="panel"><h3>Security &amp; signing devices</h3>
      <ul>{credentials.map((device) => <li key={device.credentialId}>
        <code>{device.credentialId}</code> — {device.label || "Unlabeled passkey"} — {device.status || "ACTIVE"}
        {device.createdAt && <> — registered {new Date(device.createdAt).toLocaleString()}</>}
        <label>Passkey label <input value={labels[device.credentialId] ?? device.label ?? ""}
          onChange={(event) => setLabels({ ...labels, [device.credentialId]: event.target.value })} /></label>
        <button type="button" disabled={busy || !(labels[device.credentialId] ?? device.label ?? "").trim()}
          onClick={() => run(async () => {
            await apiClient.renamePolicyCredential(device.credentialId,
              labels[device.credentialId] ?? device.label);
            setLabels((current) => { const next = { ...current }; delete next[device.credentialId]; return next; });
            setNotice("Passkey label updated.");
          })}>Save label</button>
        {device.status === "ACTIVE" && <button type="button" disabled={busy} onClick={() => revoke(device)}>Revoke with passkey</button>}
      </li>)}</ul>
      <h4>Approved grants for this account</h4>
      <ul>{myGrants.map((entry) => <li key={entry.requestId}>
        {entry.purpose} / {entry.caseReference} / expires {entry.expiresAt}
        <button type="button" disabled={busy || entry.alreadyClaimed || !!grantToken} onClick={() => claimGrant(entry)}>
          {entry.purpose.endsWith("REVOCATION") ? "Claim for revocation" : "Claim and enroll passkey"}
        </button>
      </li>)}</ul>
      {grantToken && !claimedPurpose?.endsWith("REVOCATION") && <button type="button" disabled={busy} onClick={() => run(() => enrollWithToken(grantToken))}>
        Retry passkey enrollment with this claimed grant</button>}
      <h4>Lost-device or compromise recovery</h4>
      <p>A separate credential administrator must request and independently approve a short-lived grant.</p>
      <label>Credential ID <input value={lostId} onChange={(event) => setLostId(event.target.value)} /></label>
      <label><input type="checkbox" checked={compromised} onChange={(event) => setCompromised(event.target.checked)} /> Compromised (blocks historical reliance)</label>
      <button type="button" disabled={busy || !lostId || !grantToken} onClick={() => run(async () => {
        await apiClient.protectedPolicyCredentialRevocation(lostId,
          { grantToken, compromised });
        setGrantToken(""); setClaimedPurpose(null); setLostId(""); setNotice("Protected revocation completed.");
      })}>Revoke with recovery grant</button>
      <h4>Recent security decisions</h4>
      <ul>{events.map((event) => <li key={event.id}>{event.action} — {event.credentialId || "—"} — {event.occurredAt}</li>)}</ul>
    </section>}

    {can(actor, POLICY.CREDENTIAL_ADMIN) && <section className="panel"><h3>Independent credential grants</h3>
      <label>OIDC issuer <input value={grant.targetIssuer} onChange={(e) => setGrant({ ...grant, targetIssuer: e.target.value })} /></label>
      <label>OIDC subject <input value={grant.targetSubject} onChange={(e) => setGrant({ ...grant, targetSubject: e.target.value })} /></label>
      <label>Purpose <select value={grant.purpose} onChange={(e) => setGrant({ ...grant, purpose: e.target.value })}>
        {["FIRST", "ADDITIONAL", "LOST_DEVICE_RECOVERY", "COMPROMISE_RECOVERY", "LOST_DEVICE_REVOCATION", "COMPROMISE_REVOCATION"].map((purpose) => <option key={purpose}>{purpose}</option>)}
      </select></label>
      <label>Case reference <input value={grant.caseReference} onChange={(e) => setGrant({ ...grant, caseReference: e.target.value })} /></label>
      <button type="button" disabled={busy} onClick={() => run(async () => {
        const result = await apiClient.requestPolicyCredentialGrant({ ...grant, operationId: id() });
        setApprovalId(result.requestId);
        setNotice(`Grant request ${result.requestId} needs a different credential administrator.`);
      })}>Request grant</button>
      <label>Request ID for independent approval <input value={approvalId} onChange={(e) => setApprovalId(e.target.value)} /></label>
      <button type="button" disabled={busy || !approvalId} onClick={() => run(async () => {
        setGrantReview(await apiClient.getPolicyCredentialGrantReview(approvalId));
      })}>Inspect grant request</button>
      {grantReview && <div><p>Request {grantReview.requestId}: {grantReview.purpose} for
        {" "}{grantReview.targetIssuer} / {grantReview.targetSubject}; requested by {grantReview.requestedBy};
        case {grantReview.caseReference}; expires {grantReview.expiresAt}.</p>
        <button type="button" disabled={busy || grantReview.approved || grantReview.rejected}
          onClick={() => reviewGrant("APPROVE")}>Approve with passkey</button>
        <button type="button" disabled={busy || grantReview.approved || grantReview.rejected}
          onClick={() => reviewGrant("REJECT")}>Reject with passkey</button>
        {grantReview.purpose === "FIRST" && !grantReview.approved && !grantReview.rejected &&
          <button type="button" disabled={busy} onClick={() => run(async () => {
            if (!window.confirm("Use an exact protected first-install authorization for this request?")) return;
            await apiClient.bootstrapPolicyCredentialGrant(grantReview.requestId);
            setGrantReview(null);
            setNotice("Protected first-install authorization consumed. The target can claim the grant.");
          })}>Approve with protected first-install authority</button>}
      </div>}
      <h4>Grant status and revocation</h4>
      <label>Grant ID <input value={approvalId} onChange={(e) => setApprovalId(e.target.value)} /></label>
      <button type="button" disabled={busy || !approvalId} onClick={() => run(async () => {
        setGrantStatus(await apiClient.getPolicyCredentialGrantStatus(approvalId));
      })}>Inspect status and audit</button>
      {grantStatus && <div>
        <p>{grantStatus.requestId}: {grantStatus.state} / {grantStatus.purpose};
          issuer {grantStatus.targetIssuer}; target {grantStatus.targetSubject}; expires {grantStatus.expiresAt}.</p>
        <p>Requester {grantStatus.requestedBy}; approver {grantStatus.approvedBy || "pending"};
          approval reference {grantStatus.approvalReference || "pending"}; case {grantStatus.caseReference}.</p>
        <p>Enrollment {grantStatus.consumedAt ? `committed at ${grantStatus.consumedAt} / credential ${grantStatus.credentialId}` : "not committed"}.
          {grantStatus.revokedAt && ` Revoked at ${grantStatus.revokedAt} by ${grantStatus.revokedBy}: ${grantStatus.revocationReason} / case ${grantStatus.revocationCaseReference}.`}</p>
        <h5>Grant audit</h5>
        <ol>{(grantStatus.history || []).map((event, index) => <li key={`${event.action}:${index}`}>
          {event.action} by {event.actorId} at {event.occurredAt}
          {event.reviewerActorId && ` / reviewer ${event.reviewerActorId}`}
          {event.reason && ` / ${event.reason}`}
        </li>)}</ol>
        {["REQUESTED", "APPROVED", "CLAIMED"].includes(grantStatus.state) && <>
          <label>Revocation reason <input value={revocationReason} onChange={(e) => setRevocationReason(e.target.value)} /></label>
          <label>Incident or administrative case <input value={revocationCase} onChange={(e) => setRevocationCase(e.target.value)} /></label>
          <button type="button" disabled={busy || !revocationReason.trim() || !revocationCase.trim()} onClick={() => run(async () => {
            const operationId = id();
            const result = await settleSigned(operationId,
              () => apiClient.revokePolicyCredentialGrant(grantStatus.requestId, {
                operationId, targetIssuer: grantStatus.targetIssuer, targetSubject: grantStatus.targetSubject,
                reason: revocationReason, caseReference: revocationCase, kind: "ADMINISTRATIVE"
              }), { kind: "grantRevoke", requestId: grantStatus.requestId }, "grant revocation");
            setGrantStatus(result); setNotice("Grant revoked.");
          })}>Revoke grant</button>
          {can(actor, POLICY.CREDENTIAL_INCIDENT_REVOKE) && <button type="button"
            disabled={busy || !revocationReason.trim() || !revocationCase.trim()} onClick={() => run(async () => {
              const operationId = id();
              const result = await settleSigned(operationId,
                () => apiClient.revokePolicyCredentialGrant(grantStatus.requestId, {
                  operationId, targetIssuer: grantStatus.targetIssuer, targetSubject: grantStatus.targetSubject,
                  reason: revocationReason, caseReference: revocationCase, kind: "SECURITY_INCIDENT"
                }), { kind: "grantRevoke", requestId: grantStatus.requestId }, "incident grant revocation");
              setGrantStatus(result); setNotice("Incident grant revoked.");
            })}>Revoke for security incident</button>}
        </>}
      </div>}
      {can(actor, POLICY.CREDENTIAL_INCIDENT_REVOKE) && <div>
        <h4>Compromised actor grants</h4>
        <label>Exact actor ID <input value={incidentActorId} onChange={(e) => setIncidentActorId(e.target.value)} /></label>
        <button type="button" disabled={busy || !incidentActorId} onClick={() => run(async () => {
          setIncidentPage(await apiClient.findPolicyCredentialIncidentGrants(incidentActorId));
        })}>Find active grants</button>
        <ul>{(incidentPage?.grants || []).map((entry) => <li key={entry.requestId}>
          {entry.requestId}: {entry.state} / {entry.targetSubject} / expires {entry.expiresAt}
          <button type="button" disabled={busy} onClick={() => run(async () => {
            setApprovalId(entry.requestId);
            setGrantStatus(await apiClient.getPolicyCredentialGrantStatus(entry.requestId));
          })}>Inspect and revoke</button>
        </li>)}</ul>
        {incidentPage?.nextAfterId && <button type="button" disabled={busy} onClick={() => run(async () => {
          setIncidentPage(await apiClient.findPolicyCredentialIncidentGrants(incidentActorId, incidentPage.nextAfterId));
        })}>Next page</button>}
      </div>}
    </section>}

    {can(actor, POLICY.TRUST_READ) && <><section className="panel"><h3>Institutional mandates &amp; public trust</h3>
      <p>Registry values below are public metadata only. No signing or root private keys are available here.</p>
      {registries.map((registry) => <div key={registry.registry}>
        <h4>{registry.registry}: {registry.status}</h4>
        <p>Generation {registry.generation ?? "unknown"}; SHA-256 {registry.digestSha256 || "unknown"}; rollback floor {registry.rollbackFloor ?? "unknown"}</p>
        <ul>{(registry.entries || []).map((entry, index) => <li key={`${entry.identity}-${entry.keyId}-${index}`}>
          {entry.identity} {entry.keyId && ` / ${entry.keyId}`} — {entry.status} — {(entry.roles || []).join(", ")} — {entry.publicKeySha256 || ""}
        </li>)}</ul>
      </div>)}
      <p>These are reports from running instances, not proof of fleet activation or a complete instance inventory. Reconcile the deployment inventory separately.</p>
    </section>
      <section className="panel"><h3>Trust change requests</h3>
        <ul>{changes.map((change) => <li key={change.id}>
          {change.id}: {change.intent.registry} {change.intent.action} / {change.status} / generation {change.intent.requestedGeneration}
          <button type="button" disabled={busy} onClick={() => run(async () => { setAudit(await apiClient.getPolicyTrustChangeAudit(change.id)); })}>Audit</button>
          <button type="button" disabled={busy} onClick={() => run(async () => { setDeployment(await apiClient.getPolicyTrustDeployment(change.id)); })}>Deployment</button>
          <button type="button" disabled={busy} onClick={() => run(async () => {
            setReviewCandidate(await apiClient.getPolicyTrustCandidate(change.id));
          })}>Inspect candidate diff</button>
          {change.status === "PENDING_REVIEW" && can(actor, POLICY.TRUST_APPROVE) && change.requestedBy !== actor.actorId && <>
            <button type="button" disabled={busy || reviewCandidate?.requestId !== change.id} onClick={() => reviewChange(change, "APPROVE")}>Approve displayed candidate with passkey</button>
            <button type="button" disabled={busy || reviewCandidate?.requestId !== change.id} onClick={() => reviewChange(change, "REJECT")}>Reject displayed candidate with passkey</button>
          </>}
        </li>)}</ul>
        {reviewCandidate && <TrustCandidateDiff candidate={reviewCandidate} />}
        {audit.length > 0 && <ol>{audit.map((entry) => <li key={entry.id}>{entry.action} by {entry.actorId} at {entry.occurredAt}</li>)}</ol>}
        {deployment && <div><h4>Deployment of {deployment.requestId}</h4>
          <p>Review: {deployment.requestStatus}; rollout: {deployment.rolloutState}; activation: {deployment.activationConfirmed ? "confirmed" : "not confirmed"}.</p>
          <p>Approved candidate: {deployment.registry}, generation {deployment.requestedGeneration}, digest {deployment.candidateRegistrySha256}; rollback floor {deployment.rollbackFloor}.</p>
          <p>File visible to this API instance: generation {deployment.configuredFileGeneration ?? "unknown"}, digest {deployment.configuredFileDigestSha256 || "unknown"}. This file view does not prove a running verifier reloaded it.</p>
          <p>Deployment ID: {deployment.deploymentId || "unknown"}; membership: {deployment.membershipEvidence || "unknown"}; evidence: {deployment.observationEvidence || "unverified heartbeat"}.</p>
          <p>A signed workload report proves possession of its configured reporting key. It does not independently attest the running process or confirm activation.</p>
          <p>consistent observed generation {deployment.consistentObservedGeneration ?? "unconfirmed"}; consistent observed digest {deployment.consistentObservedRegistrySha256 || "unconfirmed"}; mismatch reason: {deployment.mismatchReason || "none"}.</p>
          <p>Expected instances: {(deployment.expectedInstances || []).map((member) => `${member.role}/${member.instanceId}`).join(", ") || "unknown"}.</p>
          <p>Missing instances: {(deployment.missingInstances || []).map((member) => `${member.role}/${member.instanceId}`).join(", ") || "none"}; stale instances: {(deployment.staleInstances || []).map((member) => `${member.role}/${member.instanceId}`).join(", ") || "none"}; mismatched instances: {(deployment.mismatchedInstances || []).map((member) => `${member.role}/${member.instanceId}`).join(", ") || "none"}.</p>
          <p>Operator action: install the approved exact manifest on every expected instance, restart each process, check loaded trust and investigate missing, stale or mismatched reports. Approval alone does not activate trust.</p>
          {deployment.observationSetTruncated && <p>Observation list is truncated to the latest 100 records; consistency is not established.</p>}
          <ul>{(deployment.acknowledgements || []).map((ack, index) => <li key={`${ack.role}:${ack.instanceId}:${ack.processEpoch || index}`}>
            {ack.role} / {ack.instanceId}: generation {ack.generation}, digest {ack.digestSha256}, {ack.fresh ? "fresh" : "stale"}; process {ack.processEpoch || "unbound"}; {ack.reason || "unknown"}
          </li>)}</ul>
        </div>}
      </section></>}

    {can(actor, POLICY.TRUST_REQUEST) && <section className="panel"><h3>Request mandate or key change</h3>
      <p>Preview the complete server-built candidate before signing. Approval creates PENDING_OPERATOR_ACTION, not activation.</p>
      <label>Registry <select value={intent.registry} onChange={(e) => setIntent({ ...intent, registry: e.target.value,
        action: e.target.value === "ONLINE_ACTORS" ? "GRANT" : "ADD" })}>
        {["ONLINE_ACTORS", "APPROVAL_KEYS", "ISSUER_KEYS"].map((item) => <option key={item}>{item}</option>)}</select></label>
      <label>Action <select value={intent.action} onChange={(e) => setIntent({ ...intent, action: e.target.value })}>
        {(intent.registry === "ONLINE_ACTORS" ? ["GRANT", "REVOKE"] : ["ADD", "ROTATE", "RETIRE", "REVOKE"])
          .map((item) => <option key={item}>{item}</option>)}</select></label>
      {[["Target identity", "targetIdentity"], ["Roles (comma-separated)", "roles"],
        ["Case reference", "caseReference"]].map(([label, field]) => <label key={field}>{label}
        <input value={intent[field]} onChange={(e) => setIntent({ ...intent, [field]: e.target.value })} /></label>)}
      {intent.registry === "ONLINE_ACTORS" && intent.action === "GRANT" && <>
        {[["OIDC issuer", "targetIssuer"], ["OIDC subject", "targetSubject"],
          ["Display name", "displayName"]].map(([label, field]) => <label key={field}>{label}
          <input value={intent[field]} onChange={(e) => setIntent({ ...intent, [field]: e.target.value })} /></label>)}
      </>}
      {intent.registry !== "ONLINE_ACTORS" && <>{[["Key ID", "keyId"], ["Public key SHA-256", "publicKeySha256"]].map(([label, field]) =>
        <label key={field}>{label}<input value={intent[field]} onChange={(e) => setIntent({ ...intent, [field]: e.target.value })} /></label>)}
        {["ADD", "ROTATE"].includes(intent.action) && <label>Public Ed25519 SPKI (base64)
          <input value={intent.publicKeySpkiBase64} onChange={(e) => setIntent({ ...intent, publicKeySpkiBase64: e.target.value })} /></label>}</>}
      {intent.action === "ROTATE" && <label>Existing key ID to rotate
        <input value={intent.replacesKeyId} onChange={(e) => setIntent({ ...intent, replacesKeyId: e.target.value })} /></label>}
      {["ADD", "ROTATE", "GRANT"].includes(intent.action) && [["Effective from (UTC)", "validFrom"],
        ["Expires at (UTC)", "validUntil"]].map(([label, field]) => <label key={field}>{label}
        <input value={intent[field]} onChange={(e) => setIntent({ ...intent, [field]: e.target.value })} /></label>)}
      {["RETIRE", "ROTATE"].includes(intent.action) && <label>Old key retirement cutoff (UTC)
        <input value={intent.retireAt} onChange={(e) => setIntent({ ...intent, retireAt: e.target.value })} /></label>}
      <label>Environment <select value={intent.environment} onChange={(e) => setIntent({ ...intent, environment: e.target.value })}>
        {(can(actor, POLICY.PRODUCTION) ? ["DEVELOPMENT", "TEST", "PRODUCTION"] : ["DEVELOPMENT", "TEST"]).map((item) => <option key={item}>{item}</option>)}
      </select></label>
      <button type="button" disabled={busy} onClick={previewChange}>Preview exact registry candidate</button>
      {candidatePreview && <TrustCandidateDiff candidate={{ ...candidatePreview,
        diff: candidatePreview.diff, requestStatus: "PREVIEW" }} />}
      <button type="button" disabled={busy || !candidatePreview} onClick={submitChange}>Sign displayed candidate with passkey</button>
    </section>}
    <section className="panel"><h3>Operations</h3>
      {operations ? <><p>Blocked publication jobs: {operations.blockedPublicationJobs ?? "unknown"}; signing worker: {operations.signingWorkerAvailability}; Mongo transaction capability: {operations.mongoTransactionReadiness}; OIDC/WebAuthn: {operations.oidcWebAuthnConfiguration}; trust instances: {operations.trustRegistryConsistency}.</p>
        <p>Observations are operational signals, not proof of protected deployment or complete instance inventory.</p></>
        : <p>Diagnostics require production trust-read mandate. Worker, Mongo, OIDC/WebAuthn and cross-instance consistency are UNKNOWN here.</p>}
    </section>
    {can(actor, POLICY.AUDIT_RECOVER) && can(actor, POLICY.AUDIT_READ) && can(actor, POLICY.PRODUCTION)
      && <section className="panel"><h3>Exhausted audit obligations</h3>
        <p>Recovery is available only when the immutable commitment verifies. A retry keeps the original event and request identity.</p>
        {auditRecoveryPending && !auditRecovery.some((item) => item.outboxId === auditRecoveryPending.outboxId)
          && <p role="status">Operation {auditRecoveryPending.operationId} may have completed. Replay its exact request to retrieve the durable receipt.
            <button type="button" disabled={busy} onClick={() => submitAuditRecovery(auditRecoveryPending)}>Replay pending recovery</button>
          </p>}
        {auditRecovery.length === 0 ? <p>No permanently blocked audit obligations.</p> : <ul>
          {auditRecovery.map((item) => <li key={item.outboxId}>
            {(() => {
              const pending = auditRecoveryPending?.outboxId === item.outboxId ? auditRecoveryPending : null;
              const reason = pending?.reason ?? auditRecoveryReasons[item.outboxId] ?? "";
              return <>
            <p>{item.eventKind} / {item.eventId} — {item.classification}; attempts {item.attemptCount}; recoveries {item.recoveryCount}.</p>
            <p>Diagnostic: {item.diagnostic || "none"}; commitment {item.commitmentSha256 || "unavailable"}</p>
            {(pending || item.action !== "NONE") && <><label>Recovery reason <input
              disabled={!!pending}
              value={reason}
              onChange={(event) => setAuditRecoveryReasons((current) => ({ ...current, [item.outboxId]: event.target.value }))} /></label>
              <button type="button" disabled={busy || !reason.trim()}
                onClick={() => submitAuditRecovery(pending || { actorId: actor.actorId, outboxId: item.outboxId,
                    eventKind: item.eventKind, eventId: item.eventId, operationId: id(), reason,
                    action: item.action })}>{pending ? `Retry operation ${pending.operationId}`
                  : item.action === "RECONCILE" ? "Reconcile exact append" : "Request bounded retry"}</button>
            </>}
            </>;
            })()}
          </li>)}
        </ul>}
        <h4>Recovery history</h4>
        {auditRecoveryHistory.length === 0 ? <p>No recovery operations recorded.</p> : <ul>
          {auditRecoveryHistory.map((entry) => <li key={entry.operationId}>
            Operation {entry.operationId}, {entry.eventKind}/{entry.eventId}: {entry.result} by {entry.actorId}; {entry.reason}; request {entry.auditRequestId}; receipt {entry.receiptSha256 || "INTEGRITY FAILURE"}.
          </li>)}
        </ul>}
      </section>}
  </section>;
}

function TrustCandidateDiff({ candidate }) {
  const diff = candidate.diff || {};
  const before = diff.existingEntry;
  const after = diff.proposedEntry;
  const describe = (entry) => entry ? <>
    <p>Identity {entry.identity || entry.issuerIdentity || entry.actorId}; key {entry.keyId || "OIDC actor"};
      fingerprint {entry.publicKeySha256 || "none"}; status {entry.status}</p>
    <p>Roles {(entry.roles || (candidate.registry === "ISSUER_KEYS" ? ["POLICY_ISSUER"] : [])).join(", ")}; environments {(entry.environments || []).join(", ")};
      scopes {(entry.institutionalScopes || []).join(", ")}; uses {(entry.intendedUses || []).join(", ")}</p>
    <p>Effective {entry.validFrom}; expires {entry.validUntil}</p>
  </> : <p>None</p>;
  return <section className="panel" aria-label="Exact trust candidate diff">
    <h4>{candidate.reviewSummary || `${diff.action} ${diff.targetIdentity}`}</h4>
    <p>Base generation {candidate.baseGeneration}; base SHA-256 {candidate.baseSha256}</p>
    <p>Candidate generation {candidate.candidateGeneration}; exact SHA-256 {candidate.candidateSha256}</p>
    <h5>Existing entry</h5>{describe(before)}
    {diff.updatedExistingEntry && <><h5>Existing entry after change</h5>{describe(diff.updatedExistingEntry)}</>}
    <h5>Proposed entry</h5>{describe(after)}
    <p>Authorities removed: {(diff.removedAuthorities || []).join(", ") || "none"};
      authorities added: {(diff.addedAuthorities || []).join(", ") || "none"}.</p>
    <p>Unexpected differences: none in the server-built single-action candidate.</p>
  </section>;
}
