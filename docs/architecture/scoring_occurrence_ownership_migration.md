# Scoring Occurrence Ownership Migration

## Decision

Current scored events carry authoritative occurrence identity, but deployments upgraded from the earlier
`scored_transactions` shape may still contain documents without `sourceEventId`, exact source event time, or payload
fingerprint. The repository has no Mongo migration framework and does not configure or attest the effective retention
period of `transactions.scored`. An immediate runtime hard cut is therefore not verifiably safe.

`UNKNOWN_OCCURRENCE` remains temporary deployment compatibility only for a fully identity-free historical Mongo
document. Current Kafka input must provide an event ID and event creation time, and every current write candidate must
carry all occurrence fields. Partially populated identity fails closed. New Java domain construction has no implicit
unknown-occurrence default.

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

- Scoring occurrence ownership is mandatory for current domain construction and current writes.
- A completely identity-free historical Mongo document may still be read as `UNKNOWN_OCCURRENCE` and conditionally
  replaced by the first valid current scored event. The conditional Mongo query is the only migration compatibility;
  it is not a second current-state authority.
- Partial identity, an unverifiable fingerprint, or conflicting current replay fails closed instead of being repaired
  from transaction ID, processing time, model registry state, or Mongo natural order.

### REMOVED

- The superseded alert-existence preflight and duplicate suspicious-transaction readback path were removed when the
  current atomic occurrence admission and transaction-scoped projection became authoritative.
- Repository methods and tests owned only by those replaced paths were removed with them.
- Convenience constructors that silently created `UNKNOWN_OCCURRENCE` were removed. Tests and callers now declare
  occurrence ownership explicitly.

### MIGRATION_REQUIRED

- Every retained `scored_transactions` document with all five occurrence fields absent requires inventory and either
  authoritative event replay or governed archival.
- Any document with only some occurrence fields is invalid, must be quarantined, and must never enter the unknown
  compatibility path.
- Effective `transactions.scored` retention and the availability of exact original events require deployment evidence;
  repository defaults are not sufficient proof.
- Existing `suspicious_transactions` data must be reconciled before creating the transaction-scoped unique index.
  More than one document for a transaction is valid under the replaced schema but invalid under the current one.

## Offline Procedure

1. Pause alert-service writes or take a consistent Mongo snapshot and record the snapshot identifier.
2. Count documents where all of `sourceEventId`, `sourceEventCreatedAt`, `sourceEventCreatedAtEpochSecond`,
   `sourceEventCreatedAtNano`, and `sourceEventFingerprint` are null or absent. Record this as the unknown set.
3. Separately count documents where at least one but not all five fields are present. Copy these invalid partial
   documents to an access-controlled quarantine collection without altering their original fields.
4. For an unknown document, replay only an exact retained authoritative `TransactionScoredEvent` through the normal
   consumer. Do not derive identity from transaction ID and do not invoke current ML inference to reconstruct history.
5. Copy unresolved unknown documents to a governed historical archive and exclude them from authoritative current
   evaluation before removing them from `scored_transactions`. Preserve audit provenance and reconcile source and
   archive counts before deletion.
6. Do not manufacture source identity, inference timestamps, model identity, model version, feature-contract version,
   or ML score. Do not relabel historical model versions.

For `suspicious_transactions`, inventory groups with more than one document per `transactionId` while writes are
paused. Select the survivor only from the occurrence already accepted by the authoritative `scored_transactions`
record; quarantine all non-survivors with their original fields and reconcile counts. Then remove the replaced
`suspicious_transaction_source_event_unique_idx` and create `suspicious_transaction_current_unique_idx` on
`transactionId`. If the authoritative occurrence cannot be proven, quarantine the entire group instead of choosing
by processing time, Mongo natural order, model version, or score.

## Removal Gate

The compatibility may be removed only after one deployment evidence pack proves all of the following:

- unknown-set count is zero in every target database;
- partial-identity count is zero and quarantine reconciliation is complete;
- archive/replay counts reconcile with the original snapshot;
- no duplicate `suspicious_transactions.transactionId` group remains and the current unique index exists;
- the effective Kafka retention period and retained-event availability have been recorded;
- a full retention window has passed with no new identity-free document;
- current producer, consumer, replay, and recovery tests remain green with authoritative identity required.

After that gate, a separate reviewed change may remove `UNKNOWN_OCCURRENCE`, the historical conditional claim query,
and their focused tests. This document does not authorize that removal early.
