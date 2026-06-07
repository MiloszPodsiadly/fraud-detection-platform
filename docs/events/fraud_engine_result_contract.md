# FraudEngineResult Contract

Status: FDP-101 bounded shared contract.

## Boundary

`FraudEngineResult` is diagnostic fraud-intelligence output from exactly one fraud signal engine. It is not a final
banking decision, payment authorization, automatic approve, automatic decline, automatic block, analyst
recommendation, model training label, ground truth record, feedback dataset record, or platform aggregation result.

FDP-101 changes only the shared `common-events` contract, contract tests, sample payloads, and documentation. It does
not add Kafka event fields, API or OpenAPI surface, analyst-console UI behavior, Mongo projections, scoring
orchestration, alert-service behavior, feedback export, or dataset export.

## Required Fields

| Field | Requirement |
| --- | --- |
| `engineId` | Required bounded engine identifier, maximum 64 characters. |
| `engineType` | Required controlled enum. |
| `engineLanguage` | Required canonical lowercase runtime language string, including `java`, `python`, or `other`. |
| `status` | Required controlled enum. |
| `generatedAt` | Required generation timestamp. |

`score` is optional and must not be interpreted as zero when missing. When present, it is a finite value from `0.0000`
through `1.0000` with at most four decimal places. `riskLevel` must be present with `score` whenever status semantics
require or allow scored output.

## Bounds

| Field | Bound |
| --- | ---: |
| `reasonCodes` | 10 items, each UPPER_SNAKE and max 64 characters |
| `contributions` | 10 items |
| `evidence` | 10 items |
| `latencyMs` | 0 through 300000 |
| `modelName` | max 64 characters |
| `modelVersion` | max 64 characters |
| `statusReason` / `fallbackReason` alias | UPPER_SNAKE, max 128 characters |

Contribution `feature` is a bounded machine code and contribution `value` is a safe bounded summary. Evidence
`reasonCode` is an optional bounded machine-readable identifier and evidence `source` is a bounded machine code.
Evidence `title` and `description` are safe bounded summaries, not raw payload channels.

## Status Semantics

| Status | Score and risk level | Confidence | Reason |
| --- | --- | --- | --- |
| `AVAILABLE` | Both required | Known confidence | No `statusReason` |
| `UNAVAILABLE` | Both absent | `UNKNOWN` | Optional bounded status reason |
| `TIMEOUT` | Both absent | `UNKNOWN` | Optional bounded status reason |
| `SKIPPED` | Both absent | `UNKNOWN` | Optional bounded status reason |
| `DEGRADED` | Both absent or both present | Not `HIGH` | Optional bounded status reason |
| `FALLBACK_USED` | Both absent or both present | Not `HIGH` | Required bounded status reason |

Only `FALLBACK_USED` declares that fallback occurred. `UNAVAILABLE`, `TIMEOUT`, `SKIPPED`, and `DEGRADED` do not imply
low risk, fallback behavior, or a platform decision.

## Unsafe Content

Public contract strings must not contain raw request or response payloads, raw evidence, raw contributions, feature
vectors, stack traces, exception messages, tokens, secrets, endpoints, metadata bags, customer/account/card/device or
merchant identifiers, PAN/IBAN/email/phone values, submitted-by identities, correlation or idempotency keys, payload
hashes, ground truth, training labels, final decisions, recommended actions, approve/decline/block instructions,
payment authorization, or rule-update semantics.
