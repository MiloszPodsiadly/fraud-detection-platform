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
Projection configuration accepts max item counts from 2 through 100.
AlertDocument hard persistence cap is 100.
Truncation is explicit.
If truncation occurs, the final snapshot contains max - 1 retained items plus one PARTIAL DIAGNOSTIC truncation item.
No silent truncation is allowed.

## Failure Handling

If alert evidence snapshot projection fails, alert creation must not produce fake AVAILABLE evidence.
The alert-service records an ERROR DIAGNOSTIC snapshot item instead.
The diagnostic records projection failure state without raw exception message, raw event payload, raw model payload, or PII.

## Boundedness Boundaries

Snapshot projection enforces configured max item count.
AlertDocument also enforces a hard persistence cap to prevent unbounded internal misuse.
Projection truncation is explicit and diagnostic.
AlertDocument does not silently truncate; it rejects oversized snapshots at persistence model boundary.
The explicit truncation diagnostic is created by projection service, not by persistence entity.

## Operational Signal

Projection errors are not normal degraded success.
They indicate that alert-service could not project scoring evidence into the snapshot and had to persist an ERROR
diagnostic item.
The metric fraud.alert.evidence_snapshot.projection.error should be treated as an operational incident signal when
non-zero or increasing.
Error diagnostics must not contain raw exception messages.
Error diagnostics must not contain stack traces.
Error diagnostics must not contain raw event payloads.
Error diagnostics must not create AVAILABLE evidence.

## Projection States

Allowed projection states are:

- PROJECTED
- PARTIAL_MISSING_SOURCE_EVENT_ID
- PARTIAL_MISSING_TRANSACTION_ID
- PARTIAL_MISSING_CORRELATION_ID
- PARTIAL_MISSING_REQUIRED_LINEAGE
- PARTIAL_EMPTY_SCORING_EVIDENCE
- PARTIAL_TRUNCATED
- UNAVAILABLE_UNSUPPORTED_EVIDENCE
- ERROR_PROJECTED
- ERROR_PROJECTION_FAILED
- LEGACY_PROJECTED

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

## Case Lifecycle Separation

FDP-59 does not change case lifecycle ordering.
Evidence snapshot projection records alert-side scoring context only.

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
