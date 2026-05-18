# Suspicious Transactions

Status: current product documentation for the FDP-60 backend read model.

## Purpose

SuspiciousTransaction is a backend read model for system-detected suspicious scoring signals.

## Non-Claims

SuspiciousTransaction is not confirmed fraud.
SuspiciousTransaction is not an alert.
SuspiciousTransaction is not a fraud case.
SuspiciousTransaction is not analyst decision.
SuspiciousTransaction is not final outcome.
SuspiciousTransaction is not legal proof.
SuspiciousTransaction does not mutate case lifecycle.

## Relationship To Existing Concepts

TransactionScoredEvent is the scoring event emitted by fraud-scoring-service.
SuspiciousTransaction is the alert-service read model for suspicious scoring signal lookup and reconciliation.
Alert is the operational alert created for analyst review.
FraudCase is the investigation workflow.
EvidenceSnapshot is the alert-local point-in-time snapshot introduced by FDP-59.

## FDP-60 Scope

FDP-60 is a backend-only read model.
It stores alert-worthy scored events only.
It is idempotent by transactionId plus sourceEventId.
It links to an alert through linkedAlertId when an alert exists or is created.
It stores minimal evidence metadata only.

## Out Of Scope

FDP-60 does not add public API.
FDP-60 does not add UI.
FDP-60 does not add analyst mutation.
FDP-60 does not add manual dismiss.
FDP-60 does not add final outcome.
FDP-60 does not add false positive management.
FDP-60 does not mutate case lifecycle.
FDP-60 does not store the full evidence snapshot.
FDP-60 does not add timeline.
FDP-60 does not add grouping.
FDP-60 does not add Mongo backfill.

## Evidence Metadata Semantics

SuspiciousTransaction stores minimal evidence metadata only.
It does not store the full evidence snapshot.

`evidenceStatus` is conservative summary metadata.
`AVAILABLE` means no known degradation was present in scoring evidence metadata.
If scoring evidence contains mixed AVAILABLE and degraded items, the summary must not be AVAILABLE.

Rules:
- empty scoring evidence -> PARTIAL
- any ERROR -> ERROR
- any PARTIAL or LEGACY -> PARTIAL
- mixed AVAILABLE with UNAVAILABLE or NOT_APPLICABLE -> PARTIAL
- only unavailable/not-applicable evidence -> UNAVAILABLE
- all evidence available -> AVAILABLE

This prevents a positive available signal from hiding partial, unavailable, legacy, or failed evidence.

## Idempotency

The idempotency key is transactionId plus sourceEventId.
transactionId alone is not sufficient because the same transaction can be rescored, replayed, or backfilled with a
different source event.

## Duplicate-key race handling

SuspiciousTransaction uses transactionId + sourceEventId as its idempotency key.

A duplicate key during suspicious transaction projection can happen under concurrent retry or concurrent alert
processing:

- one worker creates the read-model document
- another worker attempts the same insert
- the unique index rejects the second insert

This is not data corruption when readback succeeds.
It means the unique index protected the idempotency invariant.

Required semantics:
- DuplicateKeyException during save triggers readback by transactionId + sourceEventId.
- Successful readback is recorded as duplicate_retry.
- Successful readback is not recorded as projection_error.
- Failed readback after DuplicateKeyException is recorded as projection_error.
- Readback must not use transactionId alone.

If linkedAlertId is provided and the existing suspicious transaction has no linkedAlertId, the duplicate-key readback
path may reconcile linkedAlertId and mark the read model as ALERT_CREATED.
It must not overwrite an existing linkedAlertId.

FDP-61 does not add public API, UI, case lifecycle mutation, or new statuses.

## Status Semantics

NEW means a suspicious signal was captured and no alert link is set.
ALERT_CREATED means the suspicious signal is linked to an alert.
LEGACY_IMPORTED means a legacy or incomplete imported signal if such migration is explicitly supported later.

There are no dismissed, confirmed, fraud-verdict, analyst-disposition, or final statuses in FDP-60.
