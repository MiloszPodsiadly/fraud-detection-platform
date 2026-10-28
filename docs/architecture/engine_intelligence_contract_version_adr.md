# Engine Intelligence Contract Version Decision

Status: the current nested Engine Intelligence summary uses `contractVersion=2`. The numeric version is fixed and does
not depend on which optional engines are present.

## Current nested contract

The single identity registry is `FraudEngineIdentityContract`. It defines four known engine IDs and their canonical
order: `rules.primary`, `ml.python.primary`, `velocity.primary`, `device.primary`. Rules and ML are required; Velocity
and Device Risk are optional. The `RULES_VS_ML` comparator remains exactly `[rules.primary, ml.python.primary]` and
does not include either optional engine. Unknown, duplicated, mis-typed, partial, or out-of-order engine identities are
rejected. Current readers reject nested version 1, missing versions, and unknown versions.

Device Risk remains diagnostic and uncalibrated. Its result cannot be `AVAILABLE` and cannot carry a risk level or
score bucket. Its presence does not change the baseline Rules/ML score, risk, recommendation, or comparison.

The outer `TransactionScoredEvent.eventContractVersion=2` and the private exact-ML evidence XOR are independent and
unchanged. Nested version changes do not relax either invariant.

## Code and deployment compatibility

| Producer code and wire | Reader deployment | Result |
| --- | --- | --- |
| Current producer, nested v2 | Current in-repository v2 readers | Supported when diagnostic emission is enabled. |
| Historical producer, nested v1 | Current v2 readers | Rejected as unsupported; no automatic reinterpretation or conversion. |
| Current producer, nested v2 | Older v1-only deployment | Incompatible; deployment must not send v2 to that reader. |
| Current producer or reader | External consumer | Compatibility is unknown until that consumer is identified and verified. |
| Retained v1 records in Kafka, DLT, Mongo projections, or exports | Current v2 reader | Not automatically migrated or deleted. Replay requires a separately governed retention and recovery plan. |

This matrix describes code behavior and deployment combinations; repository tests cannot prove deployed versions or
external consumer readiness. It makes no global backwards-compatibility claim. A rollback to a v1-only reader requires
confirming that no v2 data can reach it; retained facts must not be deleted as a shortcut.

The nested summary remains bounded to 16 KiB of serialized UTF-8 bytes. Byte-oriented ingress and egress enforce this
limit in addition to the field and collection bounds. Public APIs and the Analyst Console validate the same current
version, four-ID allowlist, order, and reason-code ownership.
