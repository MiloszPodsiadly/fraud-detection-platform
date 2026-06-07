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

`score` is optional and must not be interpreted as zero when missing. It is diagnostic engine output, not a probability,
platform aggregation, final decision, payment authorization, approve, decline, block, analyst recommendation, model
label, ground truth, or feedback dataset value. The Java contract retains `Double` for compatibility; JSON producers
must emit finite values from `0.0000` through `1.0000` with at most four decimal places. `riskLevel` must be present
with `score` whenever status semantics require or allow scored output.

## Bounds

| Field | Bound |
| --- | ---: |
| `reasonCodes` | 10 items, each UPPER_SNAKE and max 64 characters |
| `contributions` | 10 items |
| `evidence` | 10 items |
| `latencyMs` | 0 through 300000 |
| `modelName` | max 64 characters |
| `modelVersion` | max 64 characters |
| `statusReason` | UPPER_SNAKE, max 128 characters |

`statusReason` is the canonical serialized JSON field. `fallbackReason` is accepted only as an input alias for
backward-compatible deserialization and is not emitted by serializers.

Contribution `feature` is a required UPPER_SNAKE machine code and contribution `value` is a safe bounded summary.
Contribution `direction` is the semantic source of truth. `weight` is diagnostic only: `INCREASES_RISK` allows null
or non-negative weight, `DECREASES_RISK` allows null or non-positive weight, `NEUTRAL` allows null or zero, and
`UNKNOWN` requires null.

Evidence `reasonCode` is an optional UPPER_SNAKE machine code and evidence `source` is a bounded machine code.
Evidence `title` and `description` are bounded display summaries only, not raw payload, response, exception,
feature-vector, endpoint, identifier, decisioning, training, ground-truth, or feedback channels.

## Status Semantics

| Status | Score and risk level | Confidence | Reason |
| --- | --- | --- | --- |
| `AVAILABLE` | Both required | `LOW`, `MEDIUM`, or `HIGH` required | No `statusReason` |
| `UNAVAILABLE` | Both absent | `UNKNOWN` | Required bounded status reason |
| `TIMEOUT` | Both absent | `UNKNOWN` | Required bounded status reason |
| `SKIPPED` | Both absent | `UNKNOWN` | Required bounded status reason |
| `DEGRADED` | Both absent or both present | Not `HIGH` | Required bounded status reason |
| `FALLBACK_USED` | Both absent or both present | Not `HIGH` | Required bounded status reason |

Only `FALLBACK_USED` declares that fallback occurred. `UNAVAILABLE`, `TIMEOUT`, `SKIPPED`, and `DEGRADED` do not imply
low risk, fallback behavior, or a platform decision.

## Unsafe Content

Public contract strings must not contain raw request or response payloads, raw evidence, raw contributions, feature
vectors, stack traces, exception messages, tokens, secrets, endpoints, metadata bags, customer/account/card/device or
merchant identifiers, PAN/IBAN/email/phone values, submitted-by identities, correlation or idempotency keys, payload
hashes, ground truth, training labels, final decisions, recommended actions, approve/decline/block instructions,
payment authorization, or rule-update semantics.

Contract validation blocks obvious unsafe content in bounded strings only. It is not DLP and must not be represented
as data-loss-prevention coverage.

FDP-101 also does not introduce `ScoringContext`, `FraudSignalEngine`, runtime model retraining, or runtime rule
updates.
