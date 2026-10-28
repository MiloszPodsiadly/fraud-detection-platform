# Current Engine Intelligence consumer readiness

Status: active current-contract inventory. This is not a rollout history and does not authorize final
decisioning, payment authorization, automatic approval, decline, or block.

## Current contract

Every current `TransactionScoredEvent` has outer `eventContractVersion=2`. Its optional nested
`EngineIntelligenceSummary` has exactly `contractVersion=2` when present, is limited to 16 KiB of serialized UTF-8,
and uses one canonical ordered ID set:

1. `rules.primary` (required)
2. `ml.python.primary` (required)
3. `velocity.primary` (optional)
4. `device.primary` (optional and not an admitted runtime)

The comparison remains `RULES_VS_ML` over Rules and ML only. The exact ML evidence XOR remains independent of the
nested summary. There is no active v1 reader: missing, v1, and unknown nested versions are rejected safely and
boundedly. Unknown additive fields are tolerated only where the current contract explicitly allows them; that
tolerance never authorizes producer-side arbitrary fields or semantic reinterpretation.

## Current producer and consumer matrix

| Boundary | Current owner and behavior | Recovery / limitation |
| --- | --- | --- |
| Producer | `TransactionFraudScoringService` -> `TransactionScoredEventMapper` -> `KafkaTransactionScoredEventPublisher` | Diagnostic emission is disabled by default. When enabled through the reviewed flag, it publishes the bounded current summary without changing baseline Rules/ML decisions. |
| Wire boundaries | `JacksonKafkaSerializer<TransactionScoredEvent>` / `JacksonKafkaDeserializer<TransactionScoredEvent>` use `EngineIntelligenceKafkaPayloadBoundary` | Byte-oriented boundaries enforce the 16 KiB nested limit and duplicate-field rejection. Deserialization then validates outer event v2 and nested v2; shared contract validators enforce canonical IDs/order, reason ownership and exact-ML XOR. |
| Authoritative scored Kafka consumer | `TransactionScoredEventListener` | Strict full-event deserialization and baseline persistence remain the authoritative path; it neither repairs nor fabricates diagnostic evidence. |
| Baseline redrive | `TransactionScoredRedriveListener` on `transactions.redrive` | Disabled by default, requires original source coordinates, delegates to the sole baseline listener, and sends invalid recovery input to `transactions.quarantine`; it never replays to `transactions.scored`. |
| Engine Intelligence Kafka / redrive | `EngineIntelligenceProjectionEventListener`, `EngineIntelligencePendingProjectionService`, and `EngineIntelligenceRedriveListener` | Projection has its own consumer group, bounded retry and `engine-intelligence.dead-letter`; a valid deferred projection waits for its exact baseline occurrence. Projection-only redrive is disabled by default and cannot invoke baseline processing. |
| Alert projection / Mongo | `EngineIntelligenceProjectionService`, `EngineIntelligenceProjectionPolicy`, and `ScoringOccurrenceFingerprint` | Mongo stores bounded public projection data only when it belongs to the exact scored occurrence. Conflict, stale, malformed, or unavailable data fails closed without rewriting baseline alert state. |
| ML evidence / feedback / export | `MlPredictionEvidenceEventListener`, `MlPredictionEvidenceRedriveListener`, `MlPredictionEvidenceProjectionService`, and Engine Intelligence feedback-dataset export | Exact private ML evidence has its own consumer/recovery path. Export is bounded, authorized separately, and is not a scoring, training, or decisioning command. |
| REST / OpenAPI | alert-service Engine Intelligence read model and `docs/openapi/alert_service.openapi.yaml` | Read DTOs expose bounded v2 diagnostics only; absent/unavailable projections stay explicit and do not mean low risk. |
| React | Analyst Console validators and Engine Intelligence panels | The UI validates current v2 IDs, order, reason-code ownership and safe status semantics. It renders diagnostics, never raw evidence or a Device Risk fraud score. |
| External consumers | Not fully inventoried | Compatibility is unknown until every external consumer is identified and verified. No repository test is proof of deployed reader versions. |
| Fixture support | `TransactionFixtures` in `common-test-support` | This module is used in test scope only; the builder is not a runtime producer, listener, or consumer. |

## Source-scan review

The repository scan currently finds 33 Java files that reference `TransactionScoredEvent`. It scans `src/main` Java
sources, excludes test and generated-output paths, and therefore also sees the separately scoped `common-test-support`
module. It is a reference inventory rather than a count of Kafka consumers.

The two additions to the reviewed reference set are
`common-events/src/main/java/com/frauddetection/common/events/kafka/JacksonKafkaSerializer.java` and
`common-events/src/main/java/com/frauddetection/common/events/kafka/JacksonKafkaDeserializer.java`. The serializer
applies the shared bounded-wire check before publishing a typed scored event. The deserializer applies that check
before constructing the event and handing failures to the configured Kafka recovery handler. Neither class declares
a listener, consumer group, topic, retry policy, deduplication rule, projection, or API path; they are shared wire
adapters, not additional consumer registrations. The package-private
`EngineIntelligenceKafkaPayloadBoundary` is called by both adapters and validates raw nested JSON size and duplicate
properties; it is not itself a typed scored-event consumer.

Runtime consumer coordinates remain owned by the alert-service listener configuration: the baseline listener
consumes `transactions.scored`; Engine Intelligence and ML evidence use separate consumer groups on that topic. Their
redrive paths use `transactions.redrive`, `engine-intelligence.redrive`, and `ml.prediction-evidence.redrive`, with
separate dead-letter/quarantine handling and redrive disabled by default. Baseline occurrence admission is
idempotent/stale/conflict-aware; Engine Intelligence binds projection to the exact scoring occurrence and defers an
ordering gap; exact ML evidence has a separate projection and recovery path. None of those paths makes an external
consumer or deployed v2 compatibility attestable from this repository.

There is no direct `TransactionScoredEvent` deserializer in API or analyst console UI; those paths consume bounded
read DTOs and their own contract validators.

## Failure isolation and recovery

All current scored-event consumers use strict full-event deserialization. Diagnostic persistence is isolated from
baseline processing: a successfully consumed base event is not replayed solely because a diagnostic store is
unavailable. The Engine Intelligence consumer persists an ordering-gap envelope before acknowledging it; a fenced
worker retries once the authoritative occurrence exists. Conflicts fail closed and bounded retry/age exhaustion is
observable as `UNRESOLVED`.

The immutable ML evidence consumer and Engine Intelligence projection consumer each have separate consumer groups,
bounded retry, dead-letter handling, and dedicated redrive topics. Redrive records require original topic, group,
partition, offset, and transaction-key provenance. Headers provide consistency checks, not authorization or a second
source of business truth.

The source-scan guard fails with `TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED` whenever a new
production-source reference is added without review. The reviewer must classify its role before updating the
inventory; a new typed reference does not by itself prove a new Kafka consumer. The guard is a review trigger, not a
substitute for design review.

## Current-version acceptance and cutover

There is no parallel v1 runtime, compatibility parser, automatic conversion, or automatic deletion path. A current
reader accepts only a valid current outer event and, when present, a valid nested v2 summary. Historical or malformed
records are rejected or quarantined according to the owning recovery/retention procedure; consumers must not invent
identity, model lineage, evidence, or a lower-risk status.

Before enabling producer emission or deploying a reader change, the operator must verify the bounded retained-topic
and projection scope, confirm all known deployed readers understand nested v2, and identify external consumers. A
rollback to a v1-only reader is not a compatibility strategy: it requires proof that no v2 event can reach that
reader. This is a controlled deployment decision, not an application startup migration.

## Non-claims

Engine Intelligence is diagnostic. Its current producer, Kafka, projection, API, and UI paths do not alter final
fraud decisions, payment authorization, alert severity, rules, model thresholds, feedback ownership, or Device Risk
admission. Device Risk remains a declared optional identity only; its runtime activation is separately blocked until
independently authenticated, occurrence-bound evidence exists.
