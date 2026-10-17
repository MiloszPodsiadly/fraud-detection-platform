import { useEffect, useRef, useState } from "react";
import { LoadingPanel } from "./LoadingPanel.jsx";

const EMPTY = Object.freeze({ modelName: "", modelVersion: "", featureContractVersion: "", modelArtifactSha256: "", fromInclusive: "", toInclusive: "", environment: "TEST", intendedUse: "FRAUD_RISK_SCORING" });

export function ModelPromotionPanel({ workflow, modelCard, canRead, canRegister, canPropose, canApprove, canReject, canRetire }) {
  const [selection, setSelection] = useState(EMPTY);
  const manual = useRef(false);
  const previousCard = useRef(null);
  const subject = modelCard?.subject;
  const window = modelCard?.evaluationSummary?.evaluationWindow;
  useEffect(() => {
    if (!subject) {
      if (!manual.current && previousCard.current) {
        previousCard.current = null;
        workflow.clear();
        setSelection(EMPTY);
      }
      return;
    }
    if (manual.current) return;
    const next = { ...EMPTY, modelName: subject.modelName || "", modelVersion: subject.modelVersion || "", featureContractVersion: subject.featureContractVersion || "", modelArtifactSha256: subject.modelArtifactSha256 || "", fromInclusive: window?.fromInclusive || "", toInclusive: window?.toInclusive || "" };
    if (JSON.stringify(previousCard.current) !== JSON.stringify(next)) {
      previousCard.current = next;
      setSelection(next);
    }
  }, [subject, window]);
  useEffect(() => { if (canRead === false) workflow.clear(); }, [canRead, workflow.clear]);
  function update(field, value) { manual.current = true; workflow.clear(); setSelection((current) => ({ ...current, [field]: value })); }
  function load(event) { event.preventDefault(); workflow.load(Object.fromEntries(Object.entries(selection).map(([key, value]) => [key, typeof value === "string" ? value.trim() : value]))); }
  const pending = workflow.state?.proposals?.filter((proposal) => proposal.reviewStatus === "PENDING") || [];
  return <section className="panel modelCardPanel" id="model-promotion">
    <div className="panelHeader"><div><p className="eyebrow">Authoritative workflow</p><h2>Model Promotion</h2></div></div>
    <p className="sectionCopy">Governance evidence, reviewability, approval and deployment are separate controls. A CHAMPION state never confirms runtime activation.</p>
    {canRead === false && <State title="Access denied" message="Required permission: model-promotion:read." />}
    {canRead !== false && <form className="filterGrid" onSubmit={load}>
      <Field label="Model name" value={selection.modelName} onChange={(value) => update("modelName", value)} />
      <Field label="Model version" value={selection.modelVersion} onChange={(value) => update("modelVersion", value)} />
      <Field label="Feature contract" value={selection.featureContractVersion} onChange={(value) => update("featureContractVersion", value)} />
      <Field label="Artifact SHA-256" value={selection.modelArtifactSha256} onChange={(value) => update("modelArtifactSha256", value)} pattern="[0-9a-f]{64}" />
      <Field label="Evidence window from (UTC)" value={selection.fromInclusive} onChange={(value) => update("fromInclusive", value)} />
      <Field label="Evidence window to (UTC)" value={selection.toInclusive} onChange={(value) => update("toInclusive", value)} />
      <label className="filterField"><span>Governance environment</span><select value={selection.environment} onChange={(event) => update("environment", event.target.value)}><option>DEVELOPMENT</option><option>TEST</option><option>PRODUCTION</option></select></label>
      <label className="filterField"><span>Intended use</span><input value="FRAUD_RISK_SCORING" readOnly /></label>
      <div className="filterActions"><button className="primaryButton" type="submit" disabled={workflow.isLoading}>Load authoritative governance</button></div>
    </form>}
    {workflow.isLoading && <LoadingPanel label="Loading authoritative model-promotion governance..." />}
    {workflow.stateError && workflow.stateError.status !== 404 && <State title="Governance state unavailable" message={message(workflow.stateError)} />}
    {workflow.stateError?.status === 404 && <State title="Candidate not registered" message="No authoritative lifecycle state exists for this exact model and scope. Assessment diagnostics may still be available." />}
    {workflow.assessmentError && <State title="Assessment unavailable" message={message(workflow.assessmentError)} />}
    {workflow.mutationError && <State title="Command not committed" message={message(workflow.mutationError)} />}
    {workflow.state && <StateDetails state={workflow.state} />}
    {workflow.assessment && <AssessmentDetails assessment={workflow.assessment} />}
    {canRead !== false && <Actions workflow={workflow} state={workflow.state} pending={pending} canRegister={canRegister} canPropose={canPropose} canApprove={canApprove} canReject={canReject} canRetire={canRetire} />}
  </section>;
}

function Actions({ workflow, state, pending, canRegister, canPropose, canApprove, canReject, canRetire }) {
  const [target, setTarget] = useState("");
  const [rationale, setRationale] = useState("");
  useEffect(() => { setTarget(state?.allowedTargetStates?.find((value) => value !== "RETIRED") || ""); }, [state]);
  return <div className="shadowPerformanceStack"><section className="shadowPerformanceSubpanel"><h3>Authorized actions</h3>
    {!state && canRegister === true && <button className="primaryButton" type="button" disabled={workflow.isMutating} onClick={() => workflow.register()}>Register candidate</button>}
    {(state || pending.length > 0) && <label className="filterField"><span>Operator rationale</span><textarea value={rationale} onChange={(event) => setRationale(event.target.value)} required /></label>}
    {state && canPropose === true && target && <><label className="filterField"><span>Requested lifecycle state</span><select value={target} onChange={(event) => setTarget(event.target.value)}>{state.allowedTargetStates.filter((value) => value !== "RETIRED").map((value) => <option key={value}>{value}</option>)}</select></label><button className="primaryButton" type="button" disabled={workflow.isMutating || !rationale.trim()} onClick={() => workflow.propose(target, rationale)}>Submit independent-review proposal</button></>}
    {state?.allowedTargetStates?.includes("RETIRED") && canRetire === true && <button className="secondaryButton" type="button" disabled={workflow.isMutating || !rationale.trim()} onClick={() => workflow.retire(rationale)}>Propose retirement</button>}
    {pending.map((proposal) => <div key={proposal.proposalId}><p>Pending proposal: {proposal.requestedTargetState}</p>{canApprove === true && <button className="primaryButton" type="button" disabled={workflow.isMutating || !rationale.trim()} onClick={() => workflow.review(proposal.proposalId, "APPROVED", rationale)}>Independently approve</button>}{canReject === true && <button className="secondaryButton" type="button" disabled={workflow.isMutating || !rationale.trim()} onClick={() => workflow.review(proposal.proposalId, "REJECTED", rationale)}>Reject proposal</button>}</div>)}
    <p className="sectionCopy">Buttons are UX affordances only. The server revalidates principal, environment scope, evidence, policy and reviewer independence immediately before commit.</p>
  </section></div>;
}

function StateDetails({ state }) { return <div className="shadowPerformanceGrid"><Section title="Authoritative lifecycle" rows={[["Model", state.artifactIdentity.modelName], ["Version", state.artifactIdentity.modelVersion], ["Feature contract", state.artifactIdentity.featureContractVersion], ["Artifact SHA-256", state.artifactIdentity.modelArtifactSha256], ["Scope", `${state.governanceScope.institutionalScope} / ${state.governanceScope.environment}`], ["Intended use", state.intendedUse], ["Current lifecycle state", state.lifecycleState], ["Expected/current revision", state.currentRevision], ["Deployment status", state.deploymentStatus]]} /><List title="Decision history" items={state.decisionHistory.map((decision) => `${decision.committedAt}: ${decision.eventType} → ${decision.resultingState} (revision ${decision.resultingRevision})`)} /><List title="Proposal and approval history" items={state.proposals.map((proposal) => `${proposal.submittedAt}: ${proposal.requestedTargetState} — ${proposal.reviewStatus}${proposal.reviewOutcome ? ` (${proposal.reviewOutcome})` : ""}`)} /></div>; }
function AssessmentDetails({ assessment }) { return <div className="shadowPerformanceGrid"><Section title="Evidence-bound assessment" rows={[["Assessment timestamp", assessment.assessmentCreatedAt], ["Status", assessment.assessmentStatus], ["Policy", `${assessment.appliedPolicy.policyId || "UNAVAILABLE"} / ${assessment.appliedPolicy.canonicalSha256 || "UNAVAILABLE"}`], ["Evidence bundle SHA-256", assessment.sourceEvidence.evidenceBundleSha256], ["Model Card manifest", assessment.sourceEvidence.modelCardManifestSha256], ["Shadow Performance manifest", assessment.sourceEvidence.shadowPerformanceManifestSha256], ["Human reviewable", yesNo(assessment.humanReviewable)], ["Formal recommendation eligible", yesNo(assessment.formalRecommendationEligible)], ["Governance approved", yesNo(assessment.governanceApproved)], ["Deployment authorized", yesNo(assessment.deploymentAuthorized)], ["Deployment status", assessment.deploymentStatus]]} /><List title="Criterion-by-criterion outcome" items={assessment.criteria.map((criterion) => `${criterion.criterion}: ${criterion.outcome} — ${criterion.reason}; source ${criterion.evidenceSource}; verification ${criterion.requiredVerificationLevel}`)} /><List title="Blockers" items={assessment.blockers} /><List title="Limitations and missing operational evidence" items={assessment.limitations} /></div>; }
function Field({ label, value, onChange, pattern }) { return <label className="filterField"><span>{label}</span><input value={value} onChange={(event) => onChange(event.target.value)} required pattern={pattern} autoComplete="off" /></label>; }
function Section({ title, rows }) { return <section className="shadowPerformanceSubpanel"><h3>{title}</h3><dl className="shadowPerformanceDefinitionList">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{String(value)}</dd></div>)}</dl></section>; }
function List({ title, items }) { return <section className="shadowPerformanceSubpanel"><h3>{title}</h3>{items.length ? <ul className="shadowPerformanceList">{items.map((item) => <li key={item}>{item}</li>)}</ul> : <p className="sectionCopy">None recorded.</p>}</section>; }
function State({ title, message }) { return <div className="statePanel warningPanel" role="status"><h3>{title}</h3><p>{message}</p></div>; }
function yesNo(value) { return value ? "YES" : "NO"; }
function message(error) { return error?.message || "The authoritative model-promotion API could not complete this request."; }
