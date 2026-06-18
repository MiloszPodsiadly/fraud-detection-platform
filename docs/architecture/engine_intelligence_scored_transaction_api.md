# Engine Intelligence On Scored Transaction Detail

Status: FDP-115 implemented read API boundary.

## Scope

FDP-115 exposes already-projected engine intelligence on the single scored transaction detail API:

- `GET /api/v1/transactions/scored/{transactionId}`
- `ScoredTransactionResponse.engineIntelligence`

This is an analyst diagnostic read surface. It is not a scoring path, decisioning path, recommendation path,
promotion path, threshold-management path, payment-authorization path, or workflow execution path.

## Source Of Truth

The source of truth is still the scored transaction projection plus the alert-service engine intelligence projection
created from the existing `TransactionScoredEvent` payload.

The scored transaction detail read:

- validates and normalizes `transactionId`
- uses an exact `findByTransactionId` lookup for the scored transaction
- reads the existing engine intelligence projection after the scored transaction exists
- maps the existing projection into bounded public DTOs

The controller and mapper do not recompute agreement, risk mismatch, score delta, strongest signals, warning counts,
engine status, fraud score, risk level, alert recommendation, or reason codes. FDP-115 does not recompute agreement;
it only maps the already-projected comparison fields.

## Public Contract

The public detail response exposes:

- existing scored transaction fields
- `engineIntelligence.status`
- bounded comparison diagnostics
- bounded per-engine diagnostics
- bounded diagnostic signals
- bounded warnings

`engineIntelligence.status=ABSENT` is explicit and expected for old transactions or periods when producer emission was
disabled and no projection exists. The scored transaction remains readable.

`engineIntelligence.status=UNAVAILABLE` is explicit when the engine intelligence projection read path is degraded. The
scored transaction fields still come from the scored transaction projection.

`engineIntelligence.status=DEGRADED` preserves visible degraded, timed-out, or unavailable engine state from the existing
projection without turning it into a final decision.

## Non-Exposure Rules

The scored transaction detail response must not expose:

- `FraudEngineResult`
- normalized internal aggregation objects
- raw feature vectors
- raw ML requests or responses
- raw evidence or raw payloads
- training labels or ground truth
- stack traces, exception messages, model paths, tokens, or secrets

The response is a bounded DTO contract, not a serialization of projection documents or internal engine objects.

## Non-Claims

FDP-115 does not add:

- final bank authorization
- payment authorization
- automatic approve, decline, or block
- analyst recommendation
- model promotion
- threshold recommendation or threshold change
- workflow execution
- feedback capture, feedback execution, or retraining
- list/search expansion of engine intelligence diagnostics
- UI changes
