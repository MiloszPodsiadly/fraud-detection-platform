import { useEffect, useRef, useState } from "react";
import { confirmPolicyAssertion, registerPolicyCredential } from "../governance/policyWebAuthn.js";

const PREFIX = "model-policy:";
const STATUS = ["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "PUBLICATION_PENDING", "PUBLISHED"];
const createId = () => crypto.randomUUID();
const blankDraft = () => ({
  policyId: "", revision: 1,
  governanceScope: { institutionalScope: "CURRENT_INSTITUTION", environment: "PRODUCTION" },
  intendedUse: "FRAUD_RISK_SCORING", intendedApproverActorId: "",
  approvalReference: "", effectiveFromInclusive: "", effectiveToExclusive: "", criteria: []
});
const editableDraft = (draft) => ({
  policyId: draft.policyId, revision: draft.revision, governanceScope: draft.governanceScope,
  intendedUse: draft.intendedUse, intendedApproverActorId: draft.approverIdentity,
  approvalReference: draft.approvalReference,
  effectiveFromInclusive: draft.effectiveFromInclusive,
  effectiveToExclusive: draft.effectiveToExclusive, criteria: draft.criteria
});
const readableError = (error) => error?.status === 409 ? "The policy changed. Refresh and review the current revision before retrying."
  : error?.status === 401 ? "Your session expired. Sign in again before continuing."
    : error?.status === 403 ? "The backend denied this action for your current identity or scope."
      : error?.name === "NotAllowedError" ? "WebAuthn was cancelled or timed out; no decision was saved."
        : error?.message || "The operation failed.";

export function PolicyGovernancePage({ apiClient, enabled }) {
  const [actor, setActor] = useState(null);
  const [policies, setPolicies] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [publication, setPublication] = useState(null);
  const [history, setHistory] = useState(null);
  const [credentials, setCredentials] = useState(null);
  const [editor, setEditor] = useState("");
  const [rationale, setRationale] = useState("");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const busyRef = useRef(false);
  const feedbackRef = useRef(null);
  const selected = policies.find((policy) => policy.id === selectedId);
  const grants = new Set(actor?.authorities || []);
  const can = (authority) => grants.has(`${PREFIX}${authority}`);
  const ownPolicy = selected?.authorActorId === actor?.actorId;
  const canReview = selected?.status === "SUBMITTED" && !ownPolicy
    && selected?.draft?.approverIdentity === actor?.actorId;

  useEffect(() => {
    if (!enabled || !apiClient) return;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    Promise.all([
      apiClient.getPolicyGovernanceActor({ signal: controller.signal }),
      apiClient.listPolicyProposals({ signal: controller.signal })
    ]).then(([identity, items]) => {
      setActor(identity);
      setPolicies(items);
    }).catch((failure) => {
      if (failure.name !== "AbortError") setError(readableError(failure));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [apiClient, enabled]);

  useEffect(() => {
    setPublication(null);
    setHistory(null);
    setCredentials(null);
    setRationale("");
    setReceipt("");
    if (!enabled || !apiClient || !selectedId || !actor) return;
    const controller = new AbortController();
    if (can("audit:read")) {
      apiClient.getPolicyHistory(selectedId, { signal: controller.signal })
        .then(setHistory).catch((failure) => {
          if (failure.name !== "AbortError") setError(readableError(failure));
        });
    }
    apiClient.getPolicyPublication(selectedId, { signal: controller.signal })
      .then(setPublication).catch((failure) => {
        if (failure.name !== "AbortError") setError(readableError(failure));
      });
    return () => controller.abort();
  }, [apiClient, enabled, selectedId, actor]);

  async function reload(selectId = selectedId) {
    const items = await apiClient.listPolicyProposals();
    setPolicies(items);
    setSelectedId(selectId);
    if (selectId) {
      setPublication(await apiClient.getPolicyPublication(selectId));
      if (can("audit:read")) setHistory(await apiClient.getPolicyHistory(selectId));
    }
  }

  async function act(work) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError("");
    setReceipt("");
    try { await work(); }
    catch (failure) { setError(readableError(failure)); }
    finally {
      busyRef.current = false;
      setBusy(false);
      queueMicrotask(() => feedbackRef.current?.focus());
    }
  }

  function openPolicy(policy) {
    setSelectedId(policy.id);
    setEditor(JSON.stringify(editableDraft(policy.draft), null, 2));
    setError("");
  }

  async function saveDraft() {
    let draft;
    try { draft = JSON.parse(editor); }
    catch { setError("Draft must be valid JSON."); return; }
    await act(async () => {
      const operationId = createId();
      const result = selected?.status === "DRAFT"
        ? await apiClient.updatePolicyDraft(selected.id, { operationId, expectedVersion: selected.version, draft })
        : await apiClient.createPolicyDraft({ operationId, draft });
      await reload(result.id);
      setEditor(JSON.stringify(editableDraft(result.draft), null, 2));
      setReceipt(`Draft saved: ${result.id}, version ${result.version}.`);
    });
  }

  async function confirmAction(action, decision) {
    if (!selected || !window.confirm(`Confirm ${decision || "SUBMITTED"} for exact SHA-256 ${selected.draftSha256}?`)) return;
    await act(async () => {
      const operationId = createId();
      const challenge = await apiClient.startPolicyAction(selected.id, action,
        { operationId, expectedVersion: selected.version, decision,
          rationale: action === "review" ? rationale.trim() : null });
      const responseJson = await confirmPolicyAssertion(challenge.optionsJson);
      const result = await apiClient.finishPolicyAction(selected.id, action,
        { operationId, challengeId: challenge.challengeId, responseJson });
      await reload(result.id);
      setReceipt(`${result.status} committed. Receipt version ${result.version}; approval reference ${result.approvalEvidenceReference || "none"}.`);
    });
  }

  async function enrollCredential() {
    await act(async () => {
      const challenge = await apiClient.startPolicyCredentialRegistration();
      const responseJson = await registerPolicyCredential(challenge.optionsJson);
      const credential = await apiClient.finishPolicyCredentialRegistration({ challengeId: challenge.challengeId, responseJson });
      setCredentials(await apiClient.listPolicyCredentials());
      setReceipt(`Credential ${credential.credentialId} registered.`);
    });
  }

  async function requestPublication() {
    if (!selected || !window.confirm(`Request publication for ${selected.draft.policyId} revision ${selected.draft.revision}?`)) return;
    await act(async () => {
      const result = await apiClient.requestPolicyPublication(selected.id,
        { operationId: createId(), expectedVersion: selected.version });
      await reload(result.id);
      setReceipt(`Publication request committed. State: ${result.status}.`);
    });
  }

  if (!enabled) return <section className="panel"><h2>Policy Governance</h2><p>Policy read authority is required.</p></section>;
  const queue = policies.filter((item) => item.status === "SUBMITTED"
    && item.draft?.approverIdentity === actor?.actorId && item.authorActorId !== actor?.actorId);

  return <section className="panelStack" aria-label="Policy Governance workspace">
    <div className="panel"><div className="panelHeader"><div><p className="eyebrow">Institutional governance</p><h2>Policy Governance</h2></div></div>
      <p>Policy authorization is distinct from model promotion and runtime deployment.</p>
      {loading && <p role="status">Loading policies…</p>}
      <div tabIndex={-1} ref={feedbackRef} aria-live="polite">
        {error && <p role="alert">{error}</p>}{receipt && <p role="status">{receipt}</p>}
      </div>
    </div>
    <div className="panel"><h3>Policies</h3>
      <button type="button" disabled={busy} onClick={() => act(() => reload())}>Refresh</button>
      <ul>{policies.map((policy) => <li key={policy.id}>
        <button type="button" onClick={() => openPolicy(policy)} aria-current={selectedId === policy.id ? "true" : undefined}>
          {policy.draft?.policyId} · revision {policy.draft?.revision} · {policy.status}
        </button>
      </li>)}</ul>
      {!loading && policies.length === 0 && <p>No policies in your authorized scope.</p>}
    </div>
    {can("approve") || can("reject") ? <div className="panel"><h3>Pending approval queue</h3>
      {queue.length === 0 ? <p>No assigned independent reviews.</p> : <ul>{queue.map((item) =>
        <li key={item.id}><button type="button" onClick={() => openPolicy(item)}>{item.draft.policyId} · {item.draftSha256}</button></li>)}</ul>}
    </div> : null}
    {can("create") && <div className="panel"><h3>{selected?.status === "DRAFT" ? "Edit draft" : "New draft"}</h3>
      <p>Review the full criteria and thresholds before saving. Authority identities are set by the backend.</p>
      <button type="button" disabled={busy} onClick={() => {
        setSelectedId(null);
        setEditor(JSON.stringify(blankDraft(), null, 2));
      }}>New draft</button>
      <label htmlFor="policy-draft-json">Draft JSON</label>
      <textarea id="policy-draft-json" rows={16} spellCheck="false" value={editor} onChange={(event) => setEditor(event.target.value)} />
      {selected?.status === "DRAFT" && <details><summary>Compare saved and edited drafts</summary>
        <p>Saved revision</p><pre className="policyGovernanceJson">{JSON.stringify(editableDraft(selected.draft), null, 2)}</pre>
        <p>Current editor</p><pre className="policyGovernanceJson">{editor}</pre></details>}
      <button type="button" disabled={busy || !editor.trim()} onClick={saveDraft}>Save draft</button>
    </div>}
    {selected && <div className="panel"><h3>Policy detail · {selected.draft.policyId}</h3>
      <p>Status: {STATUS.includes(selected.status) ? selected.status : "Unavailable"} · immutable draft SHA-256: <code>{selected.draftSha256}</code></p>
      <p>Author: {selected.authorActorId} · intended approver: {selected.draft.approverIdentity}</p>
      <p>Scope: {selected.draft.governanceScope?.institutionalScope} / {selected.draft.governanceScope?.environment} · use: {selected.draft.intendedUse}</p>
      <p>Effective: {selected.draft.effectiveFromInclusive} – {selected.draft.effectiveToExclusive}</p>
      <details open><summary>Exact {selected.status === "DRAFT" ? "draft" : "submitted revision"} and thresholds</summary>
        <pre className="policyGovernanceJson">{JSON.stringify(selected.draft, null, 2)}</pre></details>
      {selected.status === "DRAFT" && can("submit") && selected.authorActorId === actor?.actorId &&
        <button type="button" disabled={busy} onClick={() => confirmAction("submit", null)}>Review SHA and submit with WebAuthn</button>}
      {selected.status === "SUBMITTED" && <div><h4>Independent review</h4>
        {ownPolicy && <p>You authored this policy and cannot review it.</p>}
        {canReview && <><p>Confirm the complete immutable content above before deciding.</p>
          <label htmlFor="policy-review-rationale">Rationale</label>
          <textarea id="policy-review-rationale" value={rationale} onChange={(event) => setRationale(event.target.value)} />
          <p>The rationale is bound to the WebAuthn challenge and visible in authorized history.</p>
          <button type="button" disabled={busy || !rationale.trim() || !can("approve")} onClick={() => confirmAction("review", "APPROVE")}>Approve with WebAuthn</button>
          <button type="button" disabled={busy || !rationale.trim() || !can("reject")} onClick={() => confirmAction("review", "REJECT")}>Reject with WebAuthn</button></>}
      </div>}
      {selected.status === "APPROVED" && can("publish:request") &&
        <button type="button" disabled={busy} onClick={requestPublication}>Request publication</button>}
      <div><h4>Publication and trust</h4>
        <p>Job: {publication?.state || "Loading"}{publication?.failureCode ? ` · ${publication.failureCode}` : ""}</p>
        <p>Issuer signature: {publication?.signatureStatus || "UNAVAILABLE"}</p>
        <p>Independent approval evidence: {publication?.approvalEvidenceStatus || "UNAVAILABLE"}</p>
        <p>Full policy authorization: {publication?.verificationStatus || "UNAVAILABLE"}{publication?.verificationReason ? ` · ${publication.verificationReason}` : ""}</p>
        <p>Model promotion: separate workflow, not evaluated here. Runtime deployment authorization: separate control, not evaluated here.</p>
      </div>
      {can("audit:read") && <div><h4>Policy and publication history</h4>
        {history === null ? <p>Loading authorized history…</p> : <ol>{history.map((event) =>
          <li key={event.id || event.sequence}>{event.action} · {event.fromStatus} → {event.toStatus} · {event.committedAt}
            {event.rationale && <span> · Rationale: {event.rationale}</span>}</li>)}</ol>}
      </div>}
    </div>}
    {can("credential:enroll") && <div className="panel"><h3>WebAuthn credential</h3>
      <button type="button" disabled={busy} onClick={enrollCredential}>Register credential</button>
      <button type="button" disabled={busy} onClick={() => act(async () => setCredentials(await apiClient.listPolicyCredentials()))}>Show my credentials</button>
      {credentials && <p>{credentials.length} registered credential(s).</p>}
    </div>}
  </section>;
}
