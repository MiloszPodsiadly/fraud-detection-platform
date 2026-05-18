# Evidence Model

Status: current product documentation.

## Purpose

Evidence is a typed signal or observation used to support scoring and investigation context.

## Non-claims

Evidence is not confirmed fraud.
Evidence is not final outcome.
Evidence is not analyst disposition.
Evidence is not legal proof.
Evidence is not WORM.
Evidence is not notarized.
Evidence does not prove that a fraud case exists.
Evidence severity is not final risk level.
Evidence is not complete when correlation or transaction linkage is missing.

## Status Semantics

`AVAILABLE` means the evidence item is present and usable for investigation context.
`PARTIAL` means only part of expected evidence is present.
`UNAVAILABLE` means evidence was expected but not available.
`STALE` means evidence may no longer reflect current source state.
`ERROR` means evidence collection or projection failed.
`NOT_APPLICABLE` means this evidence type does not apply to the entity or context.
`LEGACY` means evidence was derived from older payloads with limited semantics.

## Relationship To ReasonCode

Evidence references controlled `ReasonCode` wire values from FDP-56.
UNKNOWN is a parse and compatibility diagnostic.
UNKNOWN must not be treated as supported evidence signal.
`ReasonCode` explains why something contributed to score.
Evidence records what observation supports that explanation.

## Correlation And Lineage

Projected evidence must preserve source-event lineage and correlation linkage.

New projected evidence requires:

- Transaction linkage.
- Correlation linkage.
- Source event lineage where available.

Missing correlationId or transactionId must not produce AVAILABLE evidence.
It must be represented as PARTIAL or UNAVAILABLE diagnostic context.

Evidence identity must include source event lineage to avoid collisions during replay, re-scoring, or reprocessing.

Evidence with missing lineage is not fully available investigation evidence.

## Out Of Scope

- No UI.
- No final outcome.
- No analyst mutation.
- No case lifecycle mutation.
- No Kafka contract change.
- No legal immutability claim.
- No suspicious transaction model.
- No public REST API.
- No public evidence search API.
- No production backfill.
- No evidence replay/idempotency policy beyond local projection identity.
