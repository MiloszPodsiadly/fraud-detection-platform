# Multi-Engine Scoring Contract Boundary

Status: FDP-101 bounded contract maintenance on top of the current platform.

## Scope

The multi-engine fraud intelligence vocabulary supports analyst-assisted review:

```text
transaction -> features -> multiple engines -> risk intelligence -> alert/case -> analyst decision
            -> feedback -> model/rules evaluation
```

FDP-101 maintains only the shared engine-result contract and documentation. It does not alter the current event flow,
current scoring selection, current alert projections, Kafka payloads, API/OpenAPI surface, feedback datasets, or any
analyst UI.

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
latency, model identity, status reason code, and generation time.

The contract is bounded by both string length and collection size:

| Field | Maximum items |
| --- | ---: |
| `reasonCodes` | 10 |
| `contributions` | 10 |
| `evidence` | 10 |

Reason codes are stable machine-readable identifiers, not descriptions. Producers must not put customer
identifiers, raw payloads, exception text, account or card data, or secrets in them. `statusReason` uses an even
narrower uppercase reason-code form.

`FraudEngineEvidence.description` and `FraudEngineContribution.value` are safe bounded summaries only. Producers
must not put raw feature vectors, raw request or response payloads, customer, account, or card identifiers,
exception text, stack traces, tokens, secrets, or internal hostnames in them. Validation rejects basic unsafe
content; it is not a full data-loss-prevention control.

`FraudEngineEvidence.source` is a bounded uppercase machine-readable origin code, such as `RULES` or `ML_MODEL`,
not a description, hostname, service name, endpoint, or channel for operational details.

An engine result is not a final banking decision, not automatic blocking, and not core banking authorization.

## Status Semantics

| Status | Score and risk level | Confidence | Status reason |
| --- | --- | --- | --- |
| `AVAILABLE` | Both required | `LOW`, `MEDIUM`, or `HIGH` | Not permitted |
| `UNAVAILABLE` | Both absent | `UNKNOWN` | Required |
| `TIMEOUT` | Both absent | `UNKNOWN` | Required |
| `SKIPPED` | Both absent | `UNKNOWN` | Required |
| `DEGRADED` | Both absent or both present | `LOW`, `MEDIUM`, or `UNKNOWN` | Required |
| `FALLBACK_USED` | Both absent or both present | `LOW`, `MEDIUM`, or `UNKNOWN` | Required |

`statusReason` is a bounded machine-readable explanation of a non-available, skipped, timeout, degraded, or
fallback-used status. Only `FALLBACK_USED` declares that an actual fallback occurred; `UNAVAILABLE`, `TIMEOUT`,
`SKIPPED`, and `DEGRADED` do not imply fallback behavior.

`engineLanguage` is canonical lowercase, including `java`, `python`, or `other`.
Contribution direction and evidence type/status are closed contract enums rather than free-form labels.

## Existing Scoring Evidence Boundary

`FraudEngineEvidence` and its controlled vocabulary belong to the unintegrated engine-result contract. They do not
replace, extend, or project the existing `ScoringEvidenceItem` carried by `TransactionScoredEvent`.

Likewise, engine `reasonCodes` are safe machine-readable identifiers at this foundation boundary; they are not
silently promoted into the existing platform `ReasonCode` taxonomy. Any event integration must define explicit
mapping and compatibility policy before those concepts can cross the existing scoring-evidence boundary.

## Compatibility Policy

This foundation does not add `engineResults[]` to `TransactionScoredEvent` or any other Kafka event.
FDP-101 does not add new scoring orchestration, alert projection, API, OpenAPI, UI, feedback dataset, or export
integration. Existing internal engine-intelligence wiring remains outside this contract-maintenance change.

Producers remain strict and emit only documented fields. Consumers tolerate unknown additive fields in the engine
result, contribution, and evidence records while still validating all known fields. Unknown fields are ignored,
not interpreted as decision signals. Breaking semantic changes require versioning.

## Producer Responsibilities Before Emitting FraudEngineResult

Validation in this contract blocks obvious unsafe content only. It is not DLP and must not be marketed or relied
upon as DLP.

A producer must:

- sanitize explanation summaries before constructing `FraudEngineResult`;
- map raw model or runtime errors to bounded `statusReason` codes;
- never pass raw exception messages, stack traces, hostnames, URLs, request or response payloads, feature vectors,
  customer identifiers, account or card data, tokens, secrets, or authorization headers;
- emit documented fields only;
- treat unknown fields received by tolerant consumers as ignored extensions, never risk signals;
- define and test any mapping from engine `reasonCodes` to the existing platform `ReasonCode` taxonomy.

## Integration Preconditions

Before any later integration branch emits or projects `FraudEngineResult`, it must supply:

- producer sanitization tests;
- a `statusReason` mapping table;
- a bounded event payload test;
- an old-event compatibility test;
- a no raw payload or feature leak test;
- scenario tests for unavailable, timeout, skipped, and degraded engine results.

## Out Of Scope

FDP-101 does not add scoring context, engine wrappers, orchestration, comparison behavior, event integration,
projections, API surface, UI, feedback evaluation, dataset export, or automatic decisioning.
