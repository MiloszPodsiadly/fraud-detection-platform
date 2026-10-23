import { useState } from "react";

const ENVIRONMENTS = ["DEVELOPMENT", "TEST", "PRODUCTION"];
const USES = ["FRAUD_RISK_SCORING"];
const EXACT_DECIMAL = /^(0|[1-9][0-9]*)(\.[0-9]+)?$/;

export function validExactDecimal(value) {
  if (typeof value !== "string" || value.length > 60 || !EXACT_DECIMAL.test(value)) return false;
  const [integer, fraction = ""] = value.split(".");
  return fraction.length <= 24 && (integer + fraction).replace(/^0+/, "").length <= 34;
}

function changedFields(saved, current, path = "") {
  if (saved === undefined) return [];
  if (saved && current && typeof saved === "object" && typeof current === "object") {
    return [...new Set([...Object.keys(saved), ...Object.keys(current)])].sort().flatMap((key) =>
      changedFields(saved[key], current[key], path ? `${path}.${key}` : key));
  }
  return String(saved ?? "") === String(current ?? "") ? [] : [path];
}

export function emptyPolicyDraft(schema = []) {
  return {
    policyId: "", revision: 1,
    governanceScope: { institutionalScope: "CURRENT_INSTITUTION", environment: "TEST" },
    intendedUse: "FRAUD_RISK_SCORING", intendedApproverActorId: "",
    approvalReference: "", effectiveFromInclusive: "", effectiveToExclusive: "",
    criteria: schema.map((item) => ({
      criterion: item.criterion, applicability: "MANDATORY",
      thresholds: Object.fromEntries(item.requiredThresholdKeys.map((key) => [key, ""])),
      maximumEvidenceAgeSeconds: "", requiredVerificationLevel: "VERIFIED",
      evidenceSource: item.evidenceSource, metricSemantics: item.metricSemantics,
      populationDefinition: item.populationDefinition,
      denominatorDefinition: item.denominatorDefinition, applicabilityJustification: null
    }))
  };
}

export function validatePolicyDraft(draft, schema, approvers) {
  const errors = [];
  if (!draft.policyId?.trim()) errors.push("Policy ID is required.");
  if (!Number.isSafeInteger(Number(draft.revision)) || Number(draft.revision) < 1)
    errors.push("Revision must be a positive exact integer.");
  if (!draft.intendedApproverActorId || !approvers.some((item) => item.actorId === draft.intendedApproverActorId)) {
    errors.push("Select a currently eligible independent approver.");
  }
  if (!draft.approvalReference?.trim()) errors.push("Approval reference is required.");
  if (!draft.effectiveFromInclusive || !draft.effectiveToExclusive
      || !Number.isFinite(Date.parse(draft.effectiveFromInclusive))
      || !Number.isFinite(Date.parse(draft.effectiveToExclusive))
      || Date.parse(draft.effectiveFromInclusive) >= Date.parse(draft.effectiveToExclusive)) {
    errors.push("Enter an effective UTC start earlier than the end.");
  }
  if (!schema?.length || draft.criteria?.length !== schema.length) {
    errors.push("Complete the server-provided criterion schema before saving.");
    return errors;
  }
  for (const item of schema) {
    const rule = draft.criteria.find((candidate) => candidate.criterion === item.criterion);
    if (!rule) { errors.push(`${item.criterion}: missing criterion.`); continue; }
    if (rule.applicability === "NOT_APPLICABLE_AUTHORIZED") {
      if (item.nonWaivable) errors.push(`${item.criterion}: cannot be marked not applicable.`);
      if (!rule.applicabilityJustification?.trim()) errors.push(`${item.criterion}: justification is required.`);
    } else {
      for (const key of item.requiredThresholdKeys) {
        const raw = rule.thresholds?.[key];
        if (!validExactDecimal(raw)) {
          errors.push(`${item.criterion}: ${key} needs a plain, nonnegative exact decimal (up to 34 digits and 24 fractional places).`);
        }
      }
    }
    if (!Number.isSafeInteger(Number(rule.maximumEvidenceAgeSeconds))
        || Number(rule.maximumEvidenceAgeSeconds) <= 0) {
      errors.push(`${item.criterion}: maximum evidence age must be a positive exact number of seconds.`);
    }
  }
  return errors;
}

export function normalizedPolicyDraft(draft) {
  return {
    ...draft, revision: Number(draft.revision),
    criteria: draft.criteria.map((rule) => ({ ...rule,
      maximumEvidenceAgeSeconds: Number(rule.maximumEvidenceAgeSeconds),
      thresholds: Object.fromEntries(Object.entries(rule.thresholds || {})
        .map(([key, value]) => [key, value]))
    }))
  };
}

export function PolicyDraftWizard({ draft, onChange, schema, approvers, savedDraft, busy, onSave }) {
  const [errors, setErrors] = useState([]);
  const set = (field, value) => onChange({ ...draft, [field]: value });
  const setRule = (criterion, patch) => onChange({ ...draft, criteria: draft.criteria.map((rule) =>
    rule.criterion === criterion ? { ...rule, ...patch } : rule) });
  const submit = () => {
    const found = validatePolicyDraft(draft, schema, approvers);
    setErrors(found);
    if (found.length === 0) onSave(normalizedPolicyDraft(draft));
  };
  return <div className="policyDraftWizard">
    {errors.length > 0 && <div role="alert" aria-label="Policy draft validation errors">
      <p>Review these fields before saving:</p><ul>{errors.map((error) => <li key={error}>{error}</li>)}</ul></div>}
    <fieldset><legend>Policy identity and scope</legend>
      <label>Policy ID <input required value={draft.policyId} onChange={(event) => set("policyId", event.target.value)}
        aria-describedby="policy-id-help" /></label>
      <p id="policy-id-help">Stable identifier; a new immutable revision uses a higher revision number.</p>
      <label>Revision <input required type="number" min="1" step="1" value={draft.revision}
        onChange={(event) => set("revision", event.target.value)} /></label>
      <p>Institutional scope: {draft.governanceScope.institutionalScope}</p>
      <label>Environment <select value={draft.governanceScope.environment} onChange={(event) =>
        set("governanceScope", { ...draft.governanceScope, environment: event.target.value })}>
        {ENVIRONMENTS.map((value) => <option key={value}>{value}</option>)}</select></label>
      <label>Intended use <select value={draft.intendedUse} onChange={(event) => set("intendedUse", event.target.value)}>
        {USES.map((value) => <option key={value}>{value}</option>)}</select></label>
      <label>Effective from (UTC ISO-8601) <input required value={draft.effectiveFromInclusive}
        placeholder="2027-01-01T00:00:00Z" onChange={(event) => set("effectiveFromInclusive", event.target.value)} /></label>
      <label>Effective to (UTC ISO-8601) <input required value={draft.effectiveToExclusive}
        placeholder="2027-02-01T00:00:00Z" onChange={(event) => set("effectiveToExclusive", event.target.value)} /></label>
    </fieldset>
    <fieldset><legend>Independent approval</legend>
      <label>Eligible approver <select required value={draft.intendedApproverActorId}
        onChange={(event) => set("intendedApproverActorId", event.target.value)}>
        <option value="">Select a current mandated approver</option>
        {approvers.map((item) => <option key={item.actorId} value={item.actorId}>
          {item.displayName} — {item.environment} / {item.intendedUse}</option>)}</select></label>
      <p>The server verifies this mandate again at approval time. This picker does not grant a role.</p>
      <label>Approval reference <input required value={draft.approvalReference}
        onChange={(event) => set("approvalReference", event.target.value)} /></label>
    </fieldset>
    <div><h4>Evaluation criteria and policy limits</h4>
      <p>Limits are policy requirements, not observed results. No quality metric or PASS evidence is prefilled.</p>
      {schema.map((item) => {
        const rule = draft.criteria.find((candidate) => candidate.criterion === item.criterion);
        if (!rule) return <p key={item.criterion}>{item.criterion}: load the current server schema.</p>;
        return <fieldset key={item.criterion}><legend>{item.criterion.replaceAll("_", " ")}</legend>
          <p>{item.metricSemantics}</p><p>Evidence: {item.evidenceSource}</p>
          <p>Population: {item.populationDefinition}; denominator: {item.denominatorDefinition}</p>
          <label>Applicability <select value={rule.applicability} onChange={(event) => setRule(item.criterion,
            { applicability: event.target.value, thresholds: event.target.value === "MANDATORY"
              ? Object.fromEntries(item.requiredThresholdKeys.map((key) => [key, ""])) : {},
              applicabilityJustification: event.target.value === "MANDATORY" ? null : "" })}>
            <option value="MANDATORY">Mandatory</option>
            {!item.nonWaivable && <option value="NOT_APPLICABLE_AUTHORIZED">Not applicable, authorized</option>}
          </select></label>
          {rule.applicability === "MANDATORY" ? item.requiredThresholdKeys.map((key) => {
            const limit = item.thresholds?.find((entry) => entry.key === key);
            return <label key={key}>{key} ({limit?.unit || "policy limit"}; {limit?.mustBePositive ? "greater than zero" : "at least zero"}{limit?.maximumInclusive ? `; at most ${limit.maximumInclusive}` : ""})
              <input required type="text" inputMode="decimal" value={rule.thresholds[key] ?? ""}
                onChange={(event) => setRule(item.criterion,
                  { thresholds: { ...rule.thresholds, [key]: event.target.value } })} /></label>;
          })
            : <label>Applicability justification <textarea required value={rule.applicabilityJustification || ""}
              onChange={(event) => setRule(item.criterion,
                { applicabilityJustification: event.target.value })} /></label>}
          <label>Maximum evidence age (seconds) <input required type="number" min="1" step="1"
            value={rule.maximumEvidenceAgeSeconds} onChange={(event) => setRule(item.criterion,
              { maximumEvidenceAgeSeconds: event.target.value })} /></label>
          <p>Required verification level: {rule.requiredVerificationLevel}</p>
        </fieldset>;
      })}
    </div>
    <details><summary>Compare current form with saved draft</summary>
      <p>Changed fields: {savedDraft ? changedFields(savedDraft, draft).join(", ") || "none" : "new draft"}.</p>
      <p>Saved revision</p><pre className="policyGovernanceJson">{JSON.stringify(savedDraft || {}, null, 2)}</pre>
      <p>Current form</p><pre className="policyGovernanceJson">{JSON.stringify(draft, null, 2)}</pre>
    </details>
    <button type="button" disabled={busy || !schema.length} onClick={submit}>Save draft</button>
  </div>;
}
