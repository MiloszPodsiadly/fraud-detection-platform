import { useCallback, useEffect, useState } from "react";
import { confirmPolicyAssertion, registerPolicyCredential } from "../governance/policyWebAuthn.js";
import { MODEL_POLICY_AUTHORITIES as POLICY } from "../auth/modelPolicyAuthorities.js";

const blankIntent = {
  registry: "ONLINE_ACTORS", action: "GRANT", targetIdentity: "", keyId: "",
  publicKeySha256: "", publicKeySpkiBase64: "", roles: "POLICY_APPROVER",
  environment: "TEST", intendedUse: "FRAUD_RISK_SCORING",
  requestedGeneration: "", candidateRegistrySha256: "", caseReference: ""
};
const id = () => crypto.randomUUID();
const can = (actor, authority) => actor?.authorities?.includes(authority);
const explain = (error) => error?.message || "The security operation could not be completed.";

export function PolicySecurityPage({ apiClient, enabled }) {
  const [actor, setActor] = useState(null);
  const [credentials, setCredentials] = useState([]);
  const [labels, setLabels] = useState({});
  const [events, setEvents] = useState([]);
  const [registries, setRegistries] = useState([]);
  const [changes, setChanges] = useState([]);
  const [intent, setIntent] = useState(blankIntent);
  const [grantToken, setGrantToken] = useState("");
  const [grant, setGrant] = useState({ targetIssuer: "", targetSubject: "", purpose: "FIRST", caseReference: "" });
  const [approvalId, setApprovalId] = useState("");
  const [lostId, setLostId] = useState("");
  const [compromised, setCompromised] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [audit, setAudit] = useState([]);
  const [deployment, setDeployment] = useState(null);
  const [operations, setOperations] = useState(null);

  const refresh = useCallback(async () => {
    const me = await apiClient.getPolicySecurityActor();
    setActor(me);
    const [devices, decisions, publicRegistries, requests, diagnostics] = await Promise.all([
      can(me, POLICY.CREDENTIAL_ENROLL) ? apiClient.listPolicyCredentials() : [],
      can(me, POLICY.CREDENTIAL_ENROLL) ? apiClient.listPolicyCredentialSecurityEvents() : [],
      can(me, POLICY.TRUST_READ) ? apiClient.getPolicyTrustRegistries() : [],
      can(me, POLICY.TRUST_READ) ? apiClient.listPolicyTrustChanges() : [],
      can(me, POLICY.TRUST_READ) && can(me, POLICY.PRODUCTION)
        ? apiClient.getPolicySecurityOperations() : null
    ]);
    setCredentials(devices);
    setEvents(decisions);
    setRegistries(publicRegistries);
    setChanges(requests);
    setOperations(diagnostics);
  }, [apiClient]);

  useEffect(() => {
    if (!enabled || !apiClient) return;
    let live = true;
    refresh().catch((failure) => { if (live) setError(explain(failure)); });
    return () => { live = false; };
  }, [apiClient, enabled, refresh]);

  async function run(operation) {
    if (busy) return;
    setBusy(true); setError(""); setNotice("");
    try { await operation(); await refresh(); }
    catch (failure) { setError(explain(failure)); }
    finally { setBusy(false); }
  }

  async function revoke(credential) {
    if (!window.confirm(`Revoke credential ${credential.label || credential.credentialId}?`)) return;
    await run(async () => {
      const operationId = id();
      const challenge = await apiClient.startPolicyCredentialRevocation(credential.credentialId, operationId);
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      await apiClient.finishPolicyCredentialRevocation(credential.credentialId,
        { operationId, challengeId: challenge.challengeId, responseJson });
      setNotice("Credential revoked. It can no longer sign new actions.");
    });
  }

  async function enroll() {
    await run(async () => {
      const token = grantToken.trim();
      if (!token) throw new Error("An independently approved enrollment grant is required.");
      const challenge = await apiClient.startPolicyCredentialRegistration(token);
      const responseJson = await registerPolicyCredential(challenge.optionsJson);
      await apiClient.finishPolicyCredentialRegistration({ challengeId: challenge.challengeId, responseJson, grantToken: token });
      setGrantToken(""); setNotice("Passkey enrolled.");
    });
  }

  async function submitChange() {
    await run(async () => {
      const registry = registries.find((entry) => entry.registry === intent.registry);
      if (!registry || registry.status !== "READY") throw new Error("Current public registry is not ready.");
      const candidate = {
        ...intent, targetIdentity: intent.targetIdentity.trim(),
        keyId: intent.registry === "ONLINE_ACTORS" ? null : intent.keyId.trim(),
        publicKeySha256: intent.registry === "ONLINE_ACTORS" ? null : intent.publicKeySha256.trim(),
        publicKeySpkiBase64: intent.registry === "ONLINE_ACTORS" || !["ADD", "ROTATE"].includes(intent.action)
          ? null : intent.publicKeySpkiBase64.trim(),
        roles: intent.roles.split(",").map((role) => role.trim()).filter(Boolean),
        requestedGeneration: Number(intent.requestedGeneration),
        candidateRegistrySha256: intent.candidateRegistrySha256.trim(),
        caseReference: intent.caseReference.trim()
      };
      if (!window.confirm(`Request ${candidate.action} in ${candidate.registry} at generation ${candidate.requestedGeneration}? Approval will NOT activate the registry.`)) return;
      const operationId = id();
      const challenge = await apiClient.startPolicyTrustChange({ operationId, intent: candidate });
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      const result = await apiClient.finishPolicyTrustChange({ operationId, intent: candidate,
        challengeId: challenge.challengeId, responseJson });
      setNotice(`Request ${result.id} is pending independent review; trust is unchanged.`);
    });
  }

  async function reviewChange(change, decision) {
    await run(async () => {
      if (!window.confirm(`${decision} exact change ${change.id} / ${change.intentSha256}? This does not activate trust.`)) return;
      const operationId = id();
      const challenge = await apiClient.startPolicyTrustReview(change.id, { operationId, decision });
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      const result = await apiClient.finishPolicyTrustReview(change.id,
        { decision, challengeId: challenge.challengeId, responseJson });
      setNotice(`Change ${result.id}: ${result.status}. Deployment acknowledgement is still required.`);
    });
  }

  if (!enabled) return <p>Security workspace access is unavailable.</p>;
  return <section className="policy-security-page">
    <h2>Settings / Security &amp; Operations</h2>
    <p>Institutional actions require a registered passkey and dedicated mandates. Approval is not deployment.</p>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <button type="button" disabled={busy} onClick={() => run(async () => {})}>Refresh security state</button>

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
      <label>Independent enrollment grant <input value={grantToken} onChange={(event) => setGrantToken(event.target.value)} /></label>
      <button type="button" disabled={busy || !grantToken.trim()} onClick={enroll}>Enroll new passkey</button>
      <h4>Lost-device or compromise recovery</h4>
      <p>A separate credential administrator must request and independently approve a short-lived grant.</p>
      <label>Credential ID <input value={lostId} onChange={(event) => setLostId(event.target.value)} /></label>
      <label><input type="checkbox" checked={compromised} onChange={(event) => setCompromised(event.target.checked)} /> Compromised (blocks historical reliance)</label>
      <button type="button" disabled={busy || !lostId || !grantToken.trim()} onClick={() => run(async () => {
        await apiClient.protectedPolicyCredentialRevocation(lostId,
          { grantToken: grantToken.trim(), compromised });
        setGrantToken(""); setLostId(""); setNotice("Protected revocation completed.");
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
        setNotice(`Grant request ${result.requestId} needs a different credential administrator.`);
      })}>Request grant</button>
      <label>Request ID for independent approval <input value={approvalId} onChange={(e) => setApprovalId(e.target.value)} /></label>
      <button type="button" disabled={busy || !approvalId} onClick={() => run(async () => {
        const issued = await apiClient.approvePolicyCredentialGrant(approvalId);
        setNotice(`One-time grant token (copy now): ${issued.token}`);
      })}>Approve grant</button>
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
          {change.status === "PENDING_REVIEW" && can(actor, POLICY.TRUST_APPROVE) && change.requestedBy !== actor.actorId && <>
            <button type="button" disabled={busy} onClick={() => reviewChange(change, "APPROVE")}>Approve with passkey</button>
            <button type="button" disabled={busy} onClick={() => reviewChange(change, "REJECT")}>Reject with passkey</button>
          </>}
        </li>)}</ul>
        {audit.length > 0 && <ol>{audit.map((entry) => <li key={entry.id}>{entry.action} by {entry.actorId} at {entry.occurredAt}</li>)}</ol>}
        {deployment && <div><h4>Deployment of {deployment.requestId}</h4>
          <p>{deployment.rolloutState}; requested generation {deployment.requestedGeneration}; candidate digest {deployment.candidateRegistrySha256}; consistent observed generation {deployment.consistentObservedGeneration ?? "unconfirmed"}; consistent observed digest {deployment.consistentObservedRegistrySha256 || "unconfirmed"}; rollback floor {deployment.rollbackFloor}</p>
          {deployment.observationSetTruncated && <p>Observation list is truncated to the latest 100 records; consistency is not established.</p>}
          <ul>{deployment.acknowledgements.map((ack) => <li key={`${ack.role}:${ack.instanceId}`}>
            {ack.role} / {ack.instanceId}: generation {ack.generation}, digest {ack.digestSha256}, {ack.fresh ? "fresh" : "stale"}
          </li>)}</ul>
        </div>}
      </section></>}

    {can(actor, POLICY.TRUST_REQUEST) && <section className="panel"><h3>Request mandate or key change</h3>
      <p>Submit a scoped proposed registry digest. Approval creates PENDING_OPERATOR_ACTION, not activation.</p>
      <label>Registry <select value={intent.registry} onChange={(e) => setIntent({ ...intent, registry: e.target.value,
        action: e.target.value === "ONLINE_ACTORS" ? "GRANT" : "ADD" })}>
        {["ONLINE_ACTORS", "APPROVAL_KEYS", "ISSUER_KEYS"].map((item) => <option key={item}>{item}</option>)}</select></label>
      <label>Action <select value={intent.action} onChange={(e) => setIntent({ ...intent, action: e.target.value })}>
        {(intent.registry === "ONLINE_ACTORS" ? ["GRANT", "REVOKE"] : ["ADD", "ROTATE", "RETIRE", "REVOKE"])
          .map((item) => <option key={item}>{item}</option>)}</select></label>
      {[["Target identity", "targetIdentity"], ["Roles (comma-separated)", "roles"],
        ["Requested generation", "requestedGeneration"], ["Candidate registry SHA-256", "candidateRegistrySha256"],
        ["Case reference", "caseReference"]].map(([label, field]) => <label key={field}>{label}
        <input value={intent[field]} onChange={(e) => setIntent({ ...intent, [field]: e.target.value })} /></label>)}
      {intent.registry !== "ONLINE_ACTORS" && <>{[["Key ID", "keyId"], ["Public key SHA-256", "publicKeySha256"]].map(([label, field]) =>
        <label key={field}>{label}<input value={intent[field]} onChange={(e) => setIntent({ ...intent, [field]: e.target.value })} /></label>)}
        {["ADD", "ROTATE"].includes(intent.action) && <label>Public Ed25519 SPKI (base64)
          <input value={intent.publicKeySpkiBase64} onChange={(e) => setIntent({ ...intent, publicKeySpkiBase64: e.target.value })} /></label>}</>}
      <label>Environment <select value={intent.environment} onChange={(e) => setIntent({ ...intent, environment: e.target.value })}>
        {(can(actor, POLICY.PRODUCTION) ? ["DEVELOPMENT", "TEST", "PRODUCTION"] : ["DEVELOPMENT", "TEST"]).map((item) => <option key={item}>{item}</option>)}
      </select></label>
      <button type="button" disabled={busy} onClick={submitChange}>Submit with passkey</button>
    </section>}
    <section className="panel"><h3>Operations</h3>
      {operations ? <><p>Blocked publication jobs: {operations.blockedPublicationJobs ?? "unknown"}; signing worker: {operations.signingWorkerAvailability}; Mongo transaction capability: {operations.mongoTransactionReadiness}; OIDC/WebAuthn: {operations.oidcWebAuthnConfiguration}; trust instances: {operations.trustRegistryConsistency}.</p>
        <p>Observations are operational signals, not proof of protected deployment or complete instance inventory.</p></>
        : <p>Diagnostics require production trust-read mandate. Worker, Mongo, OIDC/WebAuthn and cross-instance consistency are UNKNOWN here.</p>}
    </section>
  </section>;
}
