# Suspicious Transactions

Status: current product documentation for the backend read model.

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
EvidenceSnapshot is the alert-local point-in-time snapshot.

## Current Scope

SuspiciousTransaction is a backend-only read model.
It stores alert-worthy scored events only.
It owns one current projection per transactionId.
It links to an alert through linkedAlertId when an alert exists or is created.
It stores minimal evidence metadata only.

When a newer authoritative occurrence is no longer alert-worthy, the current suspicious projection is removed in the
same occurrence transaction. A transaction that has never been suspicious does not receive a placeholder document.
The historical alert, alert publication intent, and analyst-owned fraud-case lifecycle remain independently auditable
and are not closed, reopened, dismissed, or otherwise mutated by this reconciliation.

## Out Of Scope

The read model does not add public API.
The read model does not add UI.
The read model does not add analyst mutation.
The read model does not add manual dismiss.
The read model does not add final outcome.
The read model does not add false positive management.
The read model does not mutate case lifecycle.
The read model does not store the full evidence snapshot.
The read model does not add timeline.
The read model does not add grouping.
The read model does not add Mongo backfill.

## Evidence Metadata Semantics

SuspiciousTransaction stores minimal evidence metadata only.
It does not store the full evidence snapshot.

`evidenceStatus` is conservative summary metadata.
`AVAILABLE` means no known degradation was present in scoring evidence metadata.
If scoring evidence contains mixed AVAILABLE and degraded items, the summary must not be AVAILABLE.

Rules:
- empty scoring evidence -> PARTIAL
- any ERROR -> ERROR
- any PARTIAL -> PARTIAL
- mixed AVAILABLE with UNAVAILABLE or NOT_APPLICABLE -> PARTIAL
- only unavailable/not-applicable evidence -> UNAVAILABLE
- all evidence available -> AVAILABLE

This prevents a positive available signal from hiding partial, unavailable, or failed evidence.

## Transaction-scoped ownership

The collection owns one current document per transactionId, enforced by a unique index. `sourceEventId` remains provenance
for the scored event represented by that document; it is not a second document identity.

The authoritative scoring-occurrence admission completes before this projection is written. A newly admitted current
occurrence updates the transaction-scoped read model and preserves its stable document ID. Replaying an occurrence
that admission did not accept cannot independently create another suspicious-transaction document.

A newer non-alert-worthy occurrence removes an existing current projection instead of retaining stale HIGH/CRITICAL
classification. The newer occurrence remains authoritative in `scored_transactions`; historical alert and case records
remain separate facts. An identical replay is a no-op after removal, while a stale alert-worthy replay is rejected by
occurrence admission before it can recreate the projection.

Projection persistence failures are surfaced as projection errors. The replaced duplicate-key readback path and its
dedicated retry metrics are not part of the current contract.

This projection does not add public API, UI, case lifecycle mutation, or new statuses.

## Status Semantics

NEW means a suspicious signal was captured and no alert link is set.
ALERT_CREATED means the suspicious signal is linked to an alert.

There are no dismissed, confirmed, fraud-verdict, analyst-disposition, or final statuses in this read model.
