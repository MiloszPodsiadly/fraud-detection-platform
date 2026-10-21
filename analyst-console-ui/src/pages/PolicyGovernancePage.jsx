import { useEffect, useRef, useState } from "react";
import { confirmPolicyAssertion, registerPolicyCredential } from "../governance/policyWebAuthn.js";
import { emptyPolicyDraft, PolicyDraftWizard } from "../governance/PolicyDraftWizard.jsx";
import { MODEL_POLICY_AUTHORITIES as POLICY, MODEL_POLICY_AUTHORITY_PREFIX } from "../auth/modelPolicyAuthorities.js";

const PREFIX = MODEL_POLICY_AUTHORITY_PREFIX;
const STATUS = ["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "PUBLICATION_PENDING", "PUBLISHED"];
const createId = () => crypto.randomUUID();
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
  const [nextCursor, setNextCursor] = useState(null);
  const [search, setSearch] = useState("");
  const [filterStatus, setFilterStatus] = useState("");
  const [schema, setSchema] = useState([]);
  const [approvers, setApprovers] = useState([]);
  const [historyCursor, setHistoryCursor] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [publication, setPublication] = useState(null);
  const [recovery, setRecovery] = useState(null);
  const [history, setHistory] = useState(null);
  const [credentials, setCredentials] = useState(null);
  const [enrollmentGrant, setEnrollmentGrant] = useState("");
  const [editor, setEditor] = useState(null);
  const [pendingMutation, setPendingMutation] = useState(null);
  const pendingMutationRef = useRef(null);
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
      apiClient.getPolicyPage({ size: 25 }, { signal: controller.signal }),
      apiClient.getPolicySchema({ signal: controller.signal })
    ]).then(([identity, page, criterionSchema]) => {
      setActor(identity);
      setPolicies(page.items);
      setNextCursor(page.nextCursor);
      setSchema(criterionSchema);
    }).catch((failure) => {
      if (failure.name !== "AbortError") setError(readableError(failure));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [apiClient, enabled]);

  useEffect(() => {
    setPublication(null);
    setRecovery(null);
    setHistory(null);
    setHistoryCursor(null);
    setCredentials(null);
    setRationale("");
    setReceipt("");
    if (!enabled || !apiClient || !selectedId || !actor) return;
    const controller = new AbortController();
    if (can("audit:read")) {
      apiClient.getPolicyHistoryPage(selectedId, { size: 25 }, { signal: controller.signal })
        .then((page) => { setHistory(page.items); setHistoryCursor(page.nextCursor); }).catch((failure) => {
          if (failure.name !== "AbortError") setError(readableError(failure));
        });
    }
    apiClient.getPolicyPublication(selectedId, { signal: controller.signal })
      .then(setPublication).catch((failure) => {
        if (failure.name !== "AbortError") setError(readableError(failure));
      });
    if (can("publication:recover")) {
      apiClient.getPolicyPublicationRecovery(selectedId, { signal: controller.signal })
        .then(setRecovery).catch((failure) => {
          if (failure.name !== "AbortError") setError(readableError(failure));
        });
    }
    return () => controller.abort();
  }, [apiClient, enabled, selectedId, actor]);

  useEffect(() => {
    setApprovers([]);
    if (!actor?.authorities?.includes(POLICY.DIRECTORY_READ) || !editor || !apiClient) return;
    const controller = new AbortController();
    apiClient.listPolicyApprovers(editor.governanceScope.environment, editor.intendedUse,
      { signal: controller.signal }).then(setApprovers).catch((failure) => {
        if (failure.name !== "AbortError") setError(readableError(failure));
      });
    return () => controller.abort();
  }, [apiClient, actor, editor?.governanceScope?.environment, editor?.intendedUse]);

  async function reload(selectId = selectedId) {
    const page = await apiClient.getPolicyPage({ size: 25, status: filterStatus,
      policyIdPrefix: search.trim() });
    setPolicies(page.items);
    setNextCursor(page.nextCursor);
    setSelectedId(selectId);
    if (selectId) {
      setPublication(await apiClient.getPolicyPublication(selectId));
      if (can("publication:recover")) setRecovery(await apiClient.getPolicyPublicationRecovery(selectId));
      if (can("audit:read")) {
        const timeline = await apiClient.getPolicyHistoryPage(selectId, { size: 25 });
        setHistory(timeline.items);
        setHistoryCursor(timeline.nextCursor);
      }
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

  async function runMutation(operationId, requestFn) {
    try {
      const result = await requestFn();
      pendingMutationRef.current = null;
      setPendingMutation(null);
      return result;
    } catch (failure) {
      if (failure?.status === 401 || failure?.status === 403) throw failure;
      try {
        const receipt = await apiClient.getPolicyOperation(operationId);
        pendingMutationRef.current = null;
        setPendingMutation(null);
        return receipt;
      } catch (lookupFailure) {
        pendingMutationRef.current = { operationId, requestFn };
        setPendingMutation({ operationId });
        throw new Error(`Outcome uncertain for operation ${operationId}. Check durable status or retry this same operation; do not start a new one.`);
      }
    }
  }

  async function retryPendingMutation() {
    const pending = pendingMutationRef.current;
    if (!pending) return;
    await act(async () => {
      if (pending.kind === "recovery") {
        let result = await apiClient.getPolicyPublicationRecovery(pending.proposalId);
        if (!result.recoveryHistory?.some((event) => event.operationId === pending.operationId)) {
          result = await pending.requestFn();
        }
        pendingMutationRef.current = null;
        setPendingMutation(null);
        setRecovery(result);
        setPublication(await apiClient.getPolicyPublication(pending.proposalId));
        setReceipt(`Recovery request confirmed for job ${result.publicationRequestId}; worker outcome remains ${result.state}.`);
        return;
      }
      let result;
      try {
        result = await apiClient.getPolicyOperation(pending.operationId);
        pendingMutationRef.current = null;
        setPendingMutation(null);
      } catch (failure) {
        if (failure?.status !== 404) throw failure;
        result = await runMutation(pending.operationId, pending.requestFn);
      }
      await reload(result.id);
      setReceipt(`Committed receipt recovered: ${result.status}, version ${result.version}.`);
    });
  }

  function openPolicy(policy) {
    setSelectedId(policy.id);
    setEditor(policy.status === "DRAFT" ? editableDraft(policy.draft) : null);
    setError("");
  }

  async function saveDraft(draft) {
    await act(async () => {
      const operationId = createId();
      const result = await runMutation(operationId, () => selected?.status === "DRAFT"
        ? apiClient.updatePolicyDraft(selected.id, { operationId, expectedVersion: selected.version, draft })
        : apiClient.createPolicyDraft({ operationId, draft }));
      await reload(result.id);
      setEditor(editableDraft(result.draft));
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
      const command = { operationId, challengeId: challenge.challengeId, responseJson };
      const result = await runMutation(operationId, () =>
        apiClient.finishPolicyAction(selected.id, action, command));
      await reload(result.id);
      setReceipt(`${result.status} committed. Receipt version ${result.version}; approval reference ${result.approvalEvidenceReference || "none"}.`);
    });
  }

  async function enrollCredential() {
    await act(async () => {
      const grantToken = enrollmentGrant.trim();
      if (!grantToken) throw new Error("An independently approved enrollment grant is required.");
      const challenge = await apiClient.startPolicyCredentialRegistration(grantToken);
      const responseJson = await registerPolicyCredential(challenge.optionsJson);
      const credential = await apiClient.finishPolicyCredentialRegistration({ challengeId: challenge.challengeId, responseJson, grantToken });
      setEnrollmentGrant("");
      setCredentials(await apiClient.listPolicyCredentials());
      setReceipt(`Credential ${credential.credentialId} registered.`);
    });
  }

  async function requestPublication() {
    if (!selected || !window.confirm(`Request publication for ${selected.draft.policyId} revision ${selected.draft.revision}?`)) return;
    await act(async () => {
      const operationId = createId();
      const command = { operationId, expectedVersion: selected.version };
      const result = await runMutation(operationId, () =>
        apiClient.requestPolicyPublication(selected.id, command));
      await reload(result.id);
      setReceipt(`Publication request committed. State: ${result.status}.`);
    });
  }

  async function requestRecovery() {
    if (!selected || !recovery?.retrySafe || !window.confirm(
      `Request one bounded worker recovery for publication ${recovery.publicationRequestId}?`)) return;
    await act(async () => {
      const operationId = createId();
      const requestFn = () => apiClient.requestPolicyPublicationRecovery(selected.id, { operationId });
      let result;
      try {
        result = await requestFn();
      } catch (failure) {
        if (failure?.status === 401 || failure?.status === 403) throw failure;
        try {
          const observed = await apiClient.getPolicyPublicationRecovery(selected.id);
          if (observed.recoveryHistory?.some((event) => event.operationId === operationId)) {
            result = observed;
          }
        } catch { /* Durable outcome remains uncertain. */ }
        if (!result) {
          pendingMutationRef.current = { operationId, requestFn, kind: "recovery", proposalId: selected.id };
          setPendingMutation({ operationId });
          throw new Error(`Outcome uncertain for recovery operation ${operationId}. Check or retry this same request.`);
        }
      }
      setRecovery(result);
      setPublication(await apiClient.getPolicyPublication(selected.id));
      setReceipt(`Recovery queued for worker. Job remains ${result.state}; publication is not yet confirmed.`);
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
        {pendingMutation && <div role="status"><p>Operation {pendingMutation.operationId} has an uncertain outcome. Its original command is retained only in this open browser view.</p>
          <button type="button" disabled={busy} onClick={retryPendingMutation}>Check and retry same operation</button></div>}
      </div>
    </div>
    <div className="panel"><h3>Policies</h3>
      <button type="button" disabled={busy} onClick={() => act(() => reload())}>Refresh</button>
      <label>Search policy ID <input value={search} onChange={(event) => setSearch(event.target.value)} /></label>
      <label>Status <select value={filterStatus} onChange={(event) => setFilterStatus(event.target.value)}>
        <option value="">All states</option>{STATUS.map((item) => <option key={item}>{item}</option>)}</select></label>
      <button type="button" disabled={busy} onClick={() => act(() => reload(null))}>Apply filters</button>
      <ul>{policies.map((policy) => <li key={policy.id}>
        <button type="button" onClick={() => openPolicy(policy)} aria-current={selectedId === policy.id ? "true" : undefined}>
          {policy.draft?.policyId} · revision {policy.draft?.revision} · {policy.status}
        </button>
      </li>)}</ul>
      {nextCursor && <button type="button" disabled={busy} onClick={() => act(async () => {
        const page = await apiClient.getPolicyPage({ size: 25, cursor: nextCursor,
          status: filterStatus, policyIdPrefix: search.trim() });
        setPolicies((current) => [...current, ...page.items]);
        setNextCursor(page.nextCursor);
      })}>Load more policies</button>}
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
        setEditor(emptyPolicyDraft(schema));
      }}>New draft</button>
      {editor && <PolicyDraftWizard key={selectedId || "new"} draft={editor} onChange={setEditor} schema={schema}
        approvers={approvers} savedDraft={selected?.status === "DRAFT" ? editableDraft(selected.draft) : null}
        busy={busy || !!pendingMutation} onSave={saveDraft} />}
    </div>}
    {selected && <div className="panel"><h3>Policy detail · {selected.draft.policyId}</h3>
      <p>Status: {STATUS.includes(selected.status) ? selected.status : "Unavailable"} · immutable draft SHA-256: <code>{selected.draftSha256}</code></p>
      <p>Author: {selected.authorActorId} · intended approver: {selected.draft.approverIdentity}</p>
      <p>Scope: {selected.draft.governanceScope?.institutionalScope} / {selected.draft.governanceScope?.environment} · use: {selected.draft.intendedUse}</p>
      <p>Effective: {selected.draft.effectiveFromInclusive} – {selected.draft.effectiveToExclusive}</p>
      <details open><summary>Exact {selected.status === "DRAFT" ? "draft" : "submitted revision"} and thresholds</summary>
        <pre className="policyGovernanceJson">{JSON.stringify(selected.draft, null, 2)}</pre></details>
      {selected.status === "DRAFT" && can("submit") && selected.authorActorId === actor?.actorId &&
        <button type="button" disabled={busy || !!pendingMutation} onClick={() => confirmAction("submit", null)}>Review SHA and submit with WebAuthn</button>}
      {selected.status === "SUBMITTED" && <div><h4>Independent review</h4>
        {ownPolicy && <p>You authored this policy and cannot review it.</p>}
        {canReview && <><p>Confirm the complete immutable content above before deciding.</p>
          <label htmlFor="policy-review-rationale">Rationale</label>
          <textarea id="policy-review-rationale" value={rationale} onChange={(event) => setRationale(event.target.value)} />
          <p>The rationale is bound to the WebAuthn challenge and visible in authorized history.</p>
          <button type="button" disabled={busy || !!pendingMutation || !rationale.trim() || !can("approve")} onClick={() => confirmAction("review", "APPROVE")}>Approve with WebAuthn</button>
          <button type="button" disabled={busy || !!pendingMutation || !rationale.trim() || !can("reject")} onClick={() => confirmAction("review", "REJECT")}>Reject with WebAuthn</button></>}
      </div>}
      {selected.status === "APPROVED" && can("publish:request") &&
        <button type="button" disabled={busy || !!pendingMutation} onClick={requestPublication}>Request publication</button>}
      <div><h4>Publication and trust</h4>
        {publication?.requestId && <p>Original publication request: <code>{publication.requestId}</code></p>}
        {publication?.state === "BLOCKED" && <p role="alert">Publication is blocked; this job has not produced a confirmed signed policy.</p>}
        {publication?.state === "RETRYABLE" && <p>Publication is awaiting a bounded worker retry.</p>}
        {can("publication:recover") && recovery && <div>
          <p>Recovery: {recovery.retrySafe ? "request allowed" : "request unavailable"}; attempts {recovery.attempts}; recoveries {recovery.recoveryCount}; committed evidence {recovery.alreadyCommitted ? "present" : "not found"}.</p>
          <button type="button" disabled={busy || !!pendingMutation || !recovery.retrySafe} onClick={requestRecovery}>Request recovery</button>
          {recovery.attemptHistory?.length > 0 && <ol>{recovery.attemptHistory.map((attempt) =>
            <li key={attempt.id}>Attempt {attempt.attempt}: {attempt.outcome}; {attempt.failureCode || "no failure code"}</li>)}</ol>}
          {recovery.recoveryHistory?.length > 0 && <ol>{recovery.recoveryHistory.map((event) =>
            <li key={event.operationId}>{event.requestedAt}; {event.previousFailureCode}; attempt {event.previousAttempts}; operator {event.actorId}</li>)}</ol>}
        </div>}
        <p>Job: {publication?.state || "Loading"}{publication?.failureCode ? ` · ${publication.failureCode}` : ""}</p>
        <p>Issuer signature: {publication?.signatureStatus || "UNAVAILABLE"}</p>
        <p>Independent approval evidence: {publication?.approvalEvidenceStatus || "UNAVAILABLE"}</p>
        <p>Full policy authorization: {publication?.verificationStatus || "UNAVAILABLE"}{publication?.verificationReason ? ` · ${publication.verificationReason}` : ""}</p>
        <p>Model promotion: separate workflow, not evaluated here. Runtime deployment authorization: separate control, not evaluated here.</p>
      </div>
      {can("audit:read") && <div><h4>Policy and publication history</h4>
        {historyCursor !== null && <button type="button" disabled={busy} onClick={() => act(async () => {
          const page = await apiClient.getPolicyHistoryPage(selected.id,
            { size: 25, afterVersion: historyCursor });
          setHistory((current) => [...(current || []), ...page.items]);
          setHistoryCursor(page.nextCursor);
        })}>Load more history</button>}
        {history === null ? <p>Loading authorized history…</p> : <ol>{history.map((event) =>
          <li key={event.id || event.sequence}>{event.action} · {event.fromStatus} → {event.toStatus} · {event.committedAt}
            {event.rationale && <span> · Rationale: {event.rationale}</span>}</li>)}</ol>}
      </div>}
    </div>}
    {can("credential:enroll") && <div className="panel"><h3>WebAuthn credential</h3>
      <label>Approved enrollment grant <input type="password" autoComplete="off" value={enrollmentGrant}
        onChange={(event) => setEnrollmentGrant(event.target.value)} /></label>
      <button type="button" disabled={busy || !enrollmentGrant.trim()} onClick={enrollCredential}>Register credential</button>
      <button type="button" disabled={busy} onClick={() => act(async () => setCredentials(await apiClient.listPolicyCredentials()))}>Show my credentials</button>
      {credentials && <p>{credentials.length} registered credential(s).</p>}
    </div>}
  </section>;
}
