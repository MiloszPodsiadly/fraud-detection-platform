# Multi-Engine Scoring Contract Boundary

Status: FDP-82 contract-only addition to the current platform.

## Scope

The multi-engine fraud intelligence vocabulary supports analyst-assisted review:

```text
transaction -> features -> multiple engines -> risk intelligence -> alert/case -> analyst decision
            -> feedback -> model/rules evaluation
```

FDP-82 adds only a shared engine-result contract and documentation. It does not alter the current event flow,
current scoring selection, current alert projections, or any analyst UI.

## Declared Engine Categories

| Engine | Direction |
| --- | --- |
| Java rules engine | Produces explainable rule-driven risk context. |
| Python ML engine | Produces model risk context and bounded explanations; it is not a final decision source. |
| Velocity engine | Declared transaction-rate and burst-pattern category; not integrated by FDP-82. |
| Device risk engine | Declared device-context risk category; not integrated by FDP-82. |
| Merchant risk engine | Declared merchant-context risk category; not integrated by FDP-82. |
| Graph risk engine | Declared relationship-context risk category; not integrated by FDP-82. |

## Shared Contract Boundary

`FraudEngineResult` represents the output of one engine. It uses the existing platform `RiskLevel` enum and adds
bounded engine identity, status, confidence, reason codes, contribution descriptions, evidence descriptions,
latency, model identity, fallback reason code, and generation time.

Contribution values use bounded string representations, not arbitrary objects or raw feature vectors.
`fallbackReason` is a bounded reason code, not raw exception text. The contract does not contain tokens, secrets,
customer payloads, raw ML payloads, or stack traces.

An engine result is not a final banking decision, not automatic blocking, and not core banking authorization.

## Compatibility Policy

This foundation does not add `engineResults[]` to `TransactionScoredEvent` or any other Kafka event.
`FraudEngineResult` is not referenced by the current scoring service, alert projection, API, or UI.

The contract tests retain the current strict JSON interpretation for this new model: unknown top-level fields are
rejected rather than silently interpreted. Any contract extension must explicitly define additive compatibility
before a producer emits new fields.

## Out Of Scope

FDP-82 does not add scoring context, engine wrappers, orchestration, comparison behavior, event integration,
projections, API surface, UI, feedback evaluation, or automatic decisioning.
