# Evidence-Gated Finalize State Machine

This is the state machine contract for the feature-flagged submit-decision local evidence-precondition-gated finalize
path. It applies only when both evidence-gated finalize flags are enabled for submit-decision.

## Invariants

1. Durable local-visible finalize is impossible before `EVIDENCE_PREPARED`.
2. `FINALIZED_EVIDENCE_CONFIRMED` is impossible before `FINALIZED_EVIDENCE_PENDING_EXTERNAL`.
3. `REJECTED_EVIDENCE_UNAVAILABLE` must not mutate visible business state.
4. `FINALIZE_RECOVERY_REQUIRED` must not be reported as successful business commit.
5. Idempotent replay must not rerun finalize after `FINALIZING` unless persisted state proves finalization never happened.

## States

### REQUESTED

- Meaning: Command intent was received or is being accepted.
- Authoritative source: Request plus idempotency key; no business mutation.
- Allowed transitions: `EVIDENCE_PREPARING`, `FAILED_BUSINESS_VALIDATION`.
- Forbidden transitions: Any finalized state.
- API-visible status: `PENDING_EVIDENCE`.
- Idempotency replay: Same key/same payload reads or creates command; conflict rejected.
- Recovery: Recreate from request if no command persisted.
- Trust-level impact: None unless repeated failures indicate system issue.
- Outbox impact: None.
- Audit/evidence impact: None or command intake metadata only.

### EVIDENCE_PREPARING

- Meaning: Required evidence preconditions are being prepared before visible mutation.
- Authoritative source: Command document.
- Allowed transitions: `EVIDENCE_PREPARED`, `REJECTED_EVIDENCE_UNAVAILABLE`, `FAILED_BUSINESS_VALIDATION`.
- Forbidden transitions: `FINALIZED_VISIBLE`, `FINALIZED_EVIDENCE_CONFIRMED`.
- API-visible status: `EVIDENCE_PREPARING`.
- Idempotency replay: Same key returns in-progress or prepared state; does not rerun unsafe work.
- Recovery: Retry evidence preparation when lease expires and no finalize started.
- Trust-level impact: Degrade only if preparation stalls or required evidence unavailable.
- Outbox impact: None.
- Audit/evidence impact: `ATTEMPTED` evidence may be recorded.

### EVIDENCE_PREPARED

- Meaning: Required local evidence preconditions are durable and finalize may begin.
- Authoritative source: Command document plus evidence records.
- Allowed transitions: `FINALIZING`, `REJECTED_EVIDENCE_UNAVAILABLE` only if later validation proves evidence invalid before finalize.
- Forbidden transitions: `FINALIZED_EVIDENCE_CONFIRMED`.
- API-visible status: `EVIDENCE_PREPARED`.
- Idempotency replay: Returns prepared/pending status; may trigger finalize only under coordinator ownership and lease rules.
- Recovery: Safe to resume finalize if no finalize marker or business mutation exists.
- Trust-level impact: None.
- Outbox impact: None yet.
- Audit/evidence impact: Local attempted evidence and deterministic success key reservation exist.

### FINALIZING

- Meaning: Local Mongo transaction is applying visible business mutation, outbox record, response snapshot, and local finalize marker.
- Authoritative source: Command state and local transaction.
- Allowed transitions: `FINALIZED_EVIDENCE_PENDING_EXTERNAL`, `FINALIZE_RECOVERY_REQUIRED`.
- Forbidden transitions: `REJECTED_EVIDENCE_UNAVAILABLE`, `FAILED_BUSINESS_VALIDATION`.
- API-visible status: `FINALIZING`.
- Idempotency replay: Must not rerun finalize unless persisted state proves transaction did not commit.
- Recovery: Inspect command, aggregate, outbox, and response snapshot.
- Trust-level impact: Recovery-required if ambiguity remains.
- Outbox impact: Outbox record is written inside local transaction if finalize commits.
- Audit/evidence impact: Local success audit evidence is written by `RegulatedMutationLocalAuditPhaseWriter` inside
  the same Mongo transaction as the alert decision, authoritative outbox record, response snapshot, and finalize
  marker. It does not use generic `AuditService` publisher fanout in the finalize transaction. External anchor
  publication, Trust Authority signing confirmation, and Kafka delivery remain asynchronous evidence confirmation
  steps.

### FINALIZED_VISIBLE

- Meaning: Compatibility/repair state for legacy or interrupted evidence-gated commands where the system observes a
  local visible finalize marker before the command was promoted to `FINALIZED_EVIDENCE_PENDING_EXTERNAL`.
- Authoritative source: Business aggregate plus command finalize marker.
- Allowed transitions: `FINALIZED_EVIDENCE_PENDING_EXTERNAL`, `FINALIZE_RECOVERY_REQUIRED` only if local evidence inconsistency is later detected.
- Forbidden transitions: Pre-finalize states.
- API-visible status: `FINALIZED_EVIDENCE_PENDING_EXTERNAL` for evidence-gated submit-decision replay. Clients must
  not depend on `FINALIZED_VISIBLE` as a stable new-command response.
- Idempotency replay: Return committed response snapshot; never rerun business mutation.
- Recovery: Reconstruct response from snapshot or aggregate if strategy permits.
- Trust-level impact: Healthy only if no required evidence gaps remain.
- Outbox impact: Outbox publish may proceed asynchronously.
- Audit/evidence impact: Local evidence is available; external evidence may not be confirmed.

### FINALIZED_EVIDENCE_PENDING_EXTERNAL

- Meaning: Visible commit is complete locally; external witness or broker confirmation is still pending.
- Authoritative source: Command, local evidence, outbox/publication status records.
- Allowed transitions: `FINALIZED_EVIDENCE_CONFIRMED`, `FINALIZE_RECOVERY_REQUIRED`.
- Forbidden transitions: Any rejected state.
- API-visible status: `FINALIZED_EVIDENCE_PENDING_EXTERNAL`.
- Idempotency replay: Return committed snapshot with pending evidence status.
- Recovery: Reconcile external anchor and outbox confirmation.
- Trust-level impact: May be degraded or partially trusted until external evidence confirms.
- Outbox impact: Publish/reconcile outbox asynchronously.
- Audit/evidence impact: External confirmation pending; no false confirmed claim.

### FINALIZED_EVIDENCE_CONFIRMED

- Meaning: Local commit and required evidence confirmations are complete.
- Authoritative source: Command plus external/local evidence status.
- Allowed transitions: None except administrative correction to recovery if tamper/inconsistency is detected.
- Forbidden transitions: Pending, rejected, or preparing states.
- API-visible status: `FINALIZED_EVIDENCE_CONFIRMED`.
- Idempotency replay: Return final response snapshot.
- Recovery: No normal recovery; integrity incidents if later invalidated.
- Trust-level impact: Can contribute to healthy trust if all other signals are clean.
- Outbox impact: Outbox evidence is confirmed or no outbox required.
- Audit/evidence impact: Required evidence is confirmed.

### REJECTED_EVIDENCE_UNAVAILABLE

- Meaning: Required evidence could not be prepared before visible mutation.
- Authoritative source: Command document and failure reason code.
- Allowed transitions: Retry to `EVIDENCE_PREPARING` only with explicit operator/system retry policy and no visible mutation.
- Forbidden transitions: `FINALIZED_VISIBLE` without restarting evidence gate.
- API-visible status: `REJECTED_EVIDENCE_UNAVAILABLE`.
- Idempotency replay: Same key returns rejection; different payload conflicts.
- Recovery: No business rollback required because no visible mutation occurred.
- Trust-level impact: Degradation/incident if repeated or bank-mode required dependency unavailable.
- Outbox impact: None.
- Audit/evidence impact: Failed/rejected phase may be recorded; no success evidence.

### FAILED_BUSINESS_VALIDATION

- Meaning: Business rules rejected the command before visible mutation.
- Authoritative source: Domain validation result stored with command.
- Allowed transitions: None for same command.
- Forbidden transitions: Finalized states.
- API-visible status: `FAILED_BUSINESS_VALIDATION`.
- Idempotency replay: Same key returns same validation failure unless policy explicitly allows new command key.
- Recovery: None.
- Trust-level impact: None.
- Outbox impact: None.
- Audit/evidence impact: Failed validation evidence may be recorded.

### FINALIZE_RECOVERY_REQUIRED

- Meaning: The system cannot prove whether finalization completed safely or cannot reconstruct required committed evidence.
- Authoritative source: Command recovery state and inspection results.
- Allowed transitions: `FINALIZED_VISIBLE`, `FINALIZED_EVIDENCE_PENDING_EXTERNAL`, or terminal failure only after explicit recovery proves state.
- Forbidden transitions: Reporting successful commit without proof.
- API-visible status: `FINALIZE_RECOVERY_REQUIRED`.
- Idempotency replay: Return recovery-required; do not rerun finalize blindly.
- Recovery: Operator-authorized recovery strategy must inspect command, aggregate, outbox, response snapshot, and evidence.
- Trust-level impact: Degrades trust until resolved.
- Outbox impact: Outbox may be blocked or confirmation-unknown.
- Audit/evidence impact: Recovery audit required; no false success evidence.
