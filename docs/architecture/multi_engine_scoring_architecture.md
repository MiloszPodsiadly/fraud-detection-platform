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

The contract is bounded by both string length and collection size:

| Field | Maximum items |
| --- | ---: |
| `reasonCodes` | 32 |
| `contributions` | 32 |
| `evidence` | 16 |

Reason codes are stable machine-readable identifiers, not descriptions. Producers must not put customer
identifiers, raw payloads, exception text, account or card data, or secrets in them. `fallbackReason` uses an even
narrower uppercase reason-code form.

`FraudEngineEvidence.description` and `FraudEngineContribution.value` are safe bounded summaries only. Producers
must not put raw feature vectors, raw request or response payloads, customer, account, or card identifiers,
exception text, stack traces, tokens, secrets, or internal hostnames in them. Validation rejects basic unsafe
content; it is not a full data-loss-prevention control.

An engine result is not a final banking decision, not automatic blocking, and not core banking authorization.

## Status Semantics

| Status | Score and risk level | Confidence | Fallback reason |
| --- | --- | --- | --- |
| `AVAILABLE` | Both required | `LOW`, `MEDIUM`, or `HIGH` | Not permitted |
| `UNAVAILABLE` | Both absent | `UNKNOWN` | Required |
| `TIMEOUT` | Both absent | `UNKNOWN` | Required |
| `SKIPPED` | Both absent | `UNKNOWN` | Required |
| `DEGRADED` | Both absent or both present | `LOW`, `MEDIUM`, or `UNKNOWN` | Required |
| `FALLBACK_USED` | Both absent or both present | `LOW`, `MEDIUM`, or `UNKNOWN` | Required |

`engineLanguage` is canonical lowercase: `java`, `python`, `go`, `kotlin`, `scala`, `javascript`, or `other`.
Contribution direction and evidence type/status are closed contract enums rather than free-form labels.

## Existing Scoring Evidence Boundary

`FraudEngineEvidence` and its controlled vocabulary belong to the unintegrated engine-result contract. They do not
replace, extend, or project the existing `ScoringEvidenceItem` carried by `TransactionScoredEvent`.

Likewise, engine `reasonCodes` are safe machine-readable identifiers at this foundation boundary; they are not
silently promoted into the existing platform `ReasonCode` taxonomy. Any event integration must define explicit
mapping and compatibility policy before those concepts can cross the existing scoring-evidence boundary.

## Compatibility Policy

This foundation does not add `engineResults[]` to `TransactionScoredEvent` or any other Kafka event.
`FraudEngineResult` is not referenced by the current scoring service, alert projection, API, or UI.

Producers remain strict and emit only documented fields. Consumers tolerate unknown additive fields in the engine
result, contribution, and evidence records while still validating all known fields. Unknown fields are ignored,
not interpreted as decision signals. Breaking semantic changes require versioning.

## Out Of Scope

FDP-82 does not add scoring context, engine wrappers, orchestration, comparison behavior, event integration,
projections, API surface, UI, feedback evaluation, or automatic decisioning.
