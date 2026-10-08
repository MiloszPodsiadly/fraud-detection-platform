# Scoring Occurrence Ownership Migration

## Decision

Current scored events and active `scored_transactions` documents require complete authoritative occurrence identity:
`sourceEventId`, exact source event time in all persisted representations, and the canonical payload fingerprint.
Identity-free and partially populated documents fail closed at runtime. They are not claimed, repaired, or replaced by
current traffic; retained pre-cut data requires the governed offline procedure below.

## Audit Classification

### RETAINED

- The current Python `ModelRuntime`, strict artifact loader, and file-backed registry selection by exact model name and
  version are the single ML runtime path.
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

## Legacy scoring evidence cutover

The removed scoring-evidence status is a persisted-data cutover concern, not a runtime compatibility contract. Repository
history proves that the old fallback producer emitted `source == SCORING_FALLBACK`, `status == LEGACY`, and the reviewed
fallback attributes `fallbackUsed == true` and `scoringEvidenceState == ml_decision_fallback_used`. The current producer
emits the same fallback meaning with `status == PARTIAL`.

Inventory every target environment before deploying the strict reader. Count the following sources independently:

- `alerts.evidenceSnapshot[*].status == "LEGACY"` and
  `alerts.evidenceSnapshot[*].attributes.evidenceProjectionState == "LEGACY_PROJECTED"`;
- `suspicious_transactions.evidenceStatus == "LEGACY"` and
  `suspicious_transactions.evidenceProjectionState == "LEGACY_PROJECTED"`;
- retained `transactions.scored` messages where `scoringEvidence[*].status == "LEGACY"`.

The reviewed historical suspicious-transaction projector normalized fallback legacy input to `PARTIAL` and
`PARTIAL_METADATA`; it did not emit either literal legacy value listed above. Therefore any such
`suspicious_transactions` value is unexpected and ambiguous unless an exact retained authoritative event proves its
origin. The alert snapshot also mapped `SCORING_FALLBACK` to `FRAUD_SCORING_SERVICE`, so its status and attributes alone
do not prove the original source.

Only evidence proven from an exact retained event to have `source == SCORING_FALLBACK`, `status == LEGACY`, and the
reviewed old producer shape may be normalized by a controlled offline migration to `PARTIAL`. An alert snapshot may be
normalized only when that exact event establishes the source; all other legacy values must be archived or quarantined
without reinterpretation. Blanket conversion by status is forbidden.

For each collection or retained topic partition, record and satisfy:

`legacyEvidenceSourceCount = migratedKnownFallbackCount + archivedCount + quarantinedCount`

The deployment evidence must also prove `remainingActiveLegacyEvidenceCount == 0` for every source before strict
deployment. When inventory finds no legacy data, record the zero counts explicitly rather than treating an absent
report as evidence. Retained Kafka messages must be drained, migrated only from their authoritative semantics, or
archived/quarantined; redrive through a permissive parser is forbidden.

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

## Exact ML Evidence Release Attestation

The repository cannot attest to retained records in a deployed Kafka cluster, Mongo database, export store, archive,
snapshot, or backup. Until an authorized operator records the evidence below for every deployed environment, the
cutover status is **NOT VERIFIED — EXTERNAL ATTESTATION REQUIRED**. Repository tests and ephemeral CI data cannot be
used to claim that retained logical-only evidence is absent.

Classify every inspected record without changing it:

| Class | Meaning | Required disposition |
| --- | --- | --- |
| A | Old event genuinely lacks the newer optional ML evidence. | Record its bounded source range; do not manufacture evidence. |
| B | Logical model name/version/feature identity exists without the exact artifact SHA-256. | Archive outside active replay, move to a separately governed non-exact historical representation, or block release. |
| C | Model name, version, feature contract, exact artifact SHA-256, and source execution timestamp are complete and valid. | Retain under the current evidence policy. |
| D | Evidence is partial, malformed, conflicting, or otherwise untrusted. | Quarantine unchanged and block replay until governed remediation. |

The required inventory covers the repository-owned `transactions.scored`, `engine-intelligence.dead-letter`, and
`engine-intelligence.redrive` topics; their retained partition offsets and timestamps; the
`ml_prediction_evidence_projections` and `fraud_feedback_records` collections; bounded feedback dataset exports; and
every operator-managed archive, snapshot, and backup/restore source. CI/Testcontainers and local Compose are ephemeral
execution environments, not evidence about any retained deployed environment.

For each environment and source, the evidence pack records the cluster/database identifier, topic or collection,
retention setting, earliest and latest inspected offset or timestamp, immutable snapshot/export reference, counts for
classes A-D, unresolved populations, operator identity, verification time, and independent approval. Class B must have
one explicit disposition: verified absent from the retained range, archived and removed from active replay, migrated
to a separate non-exact historical representation, or release blocked. A current registry digest or a model sharing
the historical version is never evidence of the original bytes and must never be assigned to class B.

Roll out in this order: freeze bounded replay/redrive inputs, capture immutable inventories, quarantine D, complete and
reconcile the approved B disposition, verify zero B/D records in active replay and current collections, deploy strict
consumers, then observe one full effective retention window. DLT records follow the same classification; they are not
redriven through a permissive reader. Rollback may stop the strict deployment but cannot restore permissive parsing or
reconstruct SHA-256 values. Go only when every covered source reconciles and two authorized reviewers approve the
evidence pack; otherwise the release remains blocked.

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
