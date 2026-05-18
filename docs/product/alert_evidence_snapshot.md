# Alert Evidence Snapshot

Status: current product documentation.

## Purpose

Alert evidence snapshot is a point-in-time projection of scoring evidence into alert context.
It captures bounded scoring context at alert creation time so alert records can preserve the scoring evidence that
existed when the alert was created.

## Non-claims

Alert evidence snapshot is not confirmed fraud.
Alert evidence snapshot is not analyst decision.
Alert evidence snapshot is not final outcome.
Alert evidence snapshot is not a legally binding evidence record.
Alert evidence snapshot does not provide write-once immutable storage guarantees.
Alert evidence snapshot is not external-authority verification.
Alert evidence snapshot does not prove that a fraud case exists.
Alert evidence snapshot does not mutate case lifecycle.

## Relationship To FDP-58 ScoringEvidence

ScoringEvidence is emitted by scoring.
Alert evidence snapshot is copied from ScoringEvidence at alert creation time.
Snapshot preserves scoring context.
Snapshot does not replace reasonCodes.
Snapshot does not mutate ScoringEvidence.

## Snapshot Semantics

The snapshot is a point-in-time copy.
It is not dynamically recalculated on read.
It is not analyst-mutated.
It does not mutate case lifecycle.
It does not represent final outcome.

## Boundedness

Default max item count is 50.
Truncation is explicit.
If truncation occurs, the final snapshot contains max - 1 retained items plus one PARTIAL DIAGNOSTIC truncation item.
No silent truncation is allowed.

## Lineage

Each projected snapshot item preserves:

- sourceEventId
- transactionId
- correlationId
- observedAt
- projectedAt

## Missing Data Behavior

Missing eventId, transactionId, or correlationId creates PARTIAL diagnostic evidence, not AVAILABLE evidence.
Missing ScoringEvidence for HIGH or CRITICAL scoring creates PARTIAL diagnostic evidence, not silent success.

## Out Of Scope

FDP-59 does not add:

- UI
- public API
- SuspiciousTransaction
- FraudCaseEvidenceSummary
- case lifecycle mutation
- final outcome
- Mongo backfill
- dynamic evidence recalculation
