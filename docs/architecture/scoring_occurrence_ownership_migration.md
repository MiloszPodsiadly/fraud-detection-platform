# Scoring Occurrence Ownership Migration

## Decision

Current scored events and active `scored_transactions` documents require complete authoritative occurrence identity:
`sourceEventId`, exact source event time in all persisted representations, and the canonical payload fingerprint.
Identity-free and partially populated documents fail closed at runtime. They are not claimed, repaired, or replaced by
current traffic; retained pre-cut data requires the governed offline procedure below.

## Audit Classification

### RETAINED

- The current Python `ModelRuntime`, strict artifact loader, and file-backed registry selection by explicit version or
  champion/challenger role are the single ML runtime path.
- Persisted model versions and registry entries are immutable historical identity. A name containing `v1` is not by
  itself obsolete executable compatibility and must not be rewritten as another model version.
- The Java ML adapter, orchestrator, canonical scored-event contract, immutable ML evidence projection, and
  authoritative scoring-occurrence admission are current production implementation.
- Historical ML evidence with its original score, model identity, source event identity, and inference timestamp is
  retained under the approved evidence retention policy.

### MODIFIED

- Scoring occurrence ownership is mandatory for current domain construction, reads, and writes.
- Identity-free historical Mongo documents are rejected by the active runtime and cannot be conditionally claimed by
  a current scored event.
- Partial identity, an unverifiable fingerprint, or conflicting current replay fails closed instead of being repaired
  from transaction ID, processing time, model registry state, or Mongo natural order.

### REMOVED

- The superseded alert-existence preflight and duplicate suspicious-transaction readback path were removed when the
  current atomic occurrence admission and transaction-scoped projection became authoritative.
- Repository methods and tests owned only by those replaced paths were removed with them.
- Convenience constructors that silently created identity-free ownership were removed. Tests and callers now declare
  occurrence ownership explicitly.

### MIGRATION_REQUIRED

- Every retained `scored_transactions` document with all five occurrence fields absent requires inventory and either
  authoritative event replay or governed archival.
- Any document with only some occurrence fields is invalid, must be quarantined, and must never enter the unknown
  current-state path.
- Identity-free or invalid `engine_intelligence_projections` must be rebuilt only from their exact retained scored
  event or archived/quarantined unchanged.
- Pre-lineage `fraud_feedback_records` remain audit history but must be archived, quarantined, or excluded by the
  current Dataset v2 eligibility policy. They never become current evaluation observations.
- Effective `transactions.scored` retention and the availability of exact original events require deployment evidence;
  repository defaults are not sufficient proof.
- Existing `suspicious_transactions` data must be reconciled before creating the transaction-scoped unique index.
  More than one document for a transaction is valid under the replaced schema but invalid under the current one.

## Offline Procedure

1. Pause alert-service writes or take a consistent Mongo snapshot and record the snapshot identifier.
2. Count documents where all of `sourceEventId`, `sourceEventCreatedAt`, `sourceEventCreatedAtEpochSecond`,
   `sourceEventCreatedAtNano`, and `sourceEventFingerprint` are null or absent. Record this as the identity-free set.
3. Separately count documents where at least one but not all five fields are present. Copy these invalid partial
   documents to an access-controlled quarantine collection without altering their original fields.
4. For an identity-free document, recover only from an exact retained authoritative `TransactionScoredEvent` through
   a reviewed offline migration. Do not invoke current ML inference to reconstruct history. Do not send the document
   through the active consumer or derive identity from transaction ID.
5. Copy unresolved identity-free documents to a governed historical archive and exclude them from authoritative current
   evaluation before removing them from `scored_transactions`. Preserve audit provenance and reconcile source and
   archive counts before deletion.
6. Do not manufacture source identity, inference timestamps, model identity, model version, feature-contract version,
   or ML score. Do not relabel historical model versions.
7. Record one reconciliation for each collection where
   `source count = migrated count + archived count + quarantined count`. Preserve the original BSON and hashes for
   every archived or quarantined record.

Retained pre-cut `transactions.scored` messages with neither `mlPredictionEvidence` nor
`mlPredictionEvidenceOmissionReason` must be drained before strict deployment, migrated only from authoritative
historical evidence, or archived/quarantined. Replay and redrive use the same strict current event contract; they do
not restore a permissive parser. Diagnostics intentionally disabled remain valid only through the explicit
`DIAGNOSTIC_EMISSION_DISABLED` omission reason.

For `suspicious_transactions`, inventory groups with more than one document per `transactionId` while writes are
paused. Select the survivor only from the occurrence already accepted by the authoritative `scored_transactions`
record; quarantine all non-survivors with their original fields and reconcile counts. Then remove the replaced
`suspicious_transaction_source_event_unique_idx` and create `suspicious_transaction_current_unique_idx` on
`transactionId`. If the authoritative occurrence cannot be proven, quarantine the entire group instead of choosing
by processing time, Mongo natural order, model version, or score.

## Cutover Evidence

The runtime compatibility has been removed. Before a database is treated as current authoritative state, a deployment
evidence pack must prove all of the following:

- identity-free count is zero in every target database;
- partial-identity count is zero and quarantine reconciliation is complete;
- archive/replay counts reconcile with the original snapshot;
- no duplicate `suspicious_transactions.transactionId` group remains and the current unique index exists;
- the effective Kafka retention period and retained-event availability have been recorded;
- a full retention window has passed with no new identity-free document;
- current producer, consumer, replay, and recovery tests remain green with authoritative identity required.

Failure to satisfy this evidence does not enable a runtime fallback. The affected data remains quarantined or archived
until its authoritative source identity can be proven through the governed migration.

The deployment evidence pack must also record the effective `transactions.scored` retention by topic and environment,
the earliest and latest available offsets/timestamps used for exact replay, and the operator who verified availability.
Repository defaults or current model-registry state are not evidence that an exact historical event remains available.

## Rollback Principle

Rollback must never restore identity-free runtime interpretation. If deployment discovers unresolved historical data,
roll back the strict deployment, keep affected records outside active current-state collections, complete the governed
offline migration or archive/quarantine procedure, reconcile and validate the inventory again, and redeploy. Historical
identity must never be reconstructed from transaction ID, processing time, Mongo natural order, score, current model,
model registry state, timestamp proximity, or current ML inference.
