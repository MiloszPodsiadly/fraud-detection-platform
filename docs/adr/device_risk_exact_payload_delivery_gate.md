# Device Risk Exact-Payload Delivery Gate

Status: engineering NO-GO for A05; the Device Risk admission path remains disabled and no production outbox is implemented.

## Current operations and durable boundaries

The Device Risk Mongo adapter commits an observation, an immutable occurrence containing serialized
`DeviceRiskEvidence`, and the candidate transition in one Mongo transaction. It does not persist a
`TransactionEnrichedEvent`, its exact serialized bytes, or a delivery intent. The adapter has no
production bean or production Mongo connection in the feature enricher. Admission defaults off and
fails startup when enabled because no authenticated issuer and scoped source authorization exist.

The existing baseline enricher follows this sequence:

1. Check the processed-transaction set in Redis.
2. Load a Redis snapshot. The load prunes expired sorted-set entries before it reads the snapshot.
3. Calculate features and map a new enriched event. The mapper generates a random event ID and a new
   `enrichedAt` timestamp on every attempt.
4. Publish to Kafka with `transactionId` as the key and wait for `send(...).get()`. Producer `acks`
   is configured as `all`.
5. Record history in Redis through multiple individual sorted-set, set, value, and expiry commands.

The raw Kafka listener calls the service synchronously. Its configured error handler retries failures
and then hands exhausted records to the dead-letter topic. This does not make the Kafka publish and
Redis mutation atomic.

| Failure point | Current durable/observable result | Retry implication |
| --- | --- | --- |
| After Redis snapshot pruning, before Kafka send | Some Redis cleanup may already have occurred; no enriched record is known to have been sent. | Listener retry recalculates from the current Redis state. |
| Kafka send throws or acknowledgment is unknown | Broker acceptance cannot be distinguished from rejection by this service call. | Listener retry maps a new event ID and timestamp and may publish again under the same transaction key. |
| Kafka acknowledges, then process stops before Redis recording | An enriched record may be visible while the processed marker and history are absent. | Retry can publish another event with a new event ID and a recomputed snapshot. |
| Redis recording fails partway | Earlier Redis commands remain applied; there is no transaction or rollback across the commands. | Retry behavior depends on whether the processed marker was already written; no durable repair intent exists. |
| Redis recording completes | Kafka publication preceded Redis recording; the two systems still have no shared commit. | The Redis marker suppresses later raw-event processing until its TTL expires. |

The current enriched contract includes raw device fields. Persisting its exact bytes would create a new
retained copy of sensitive data. A content hash cannot reconstruct those bytes after a crash.

## Decision and executable evidence

Do not add an outbox collection, payload serializer, worker, lease, or enabled delivery flag in this
branch. Without the source and privacy approvals below, those components would persist unauthenticated
claims and sensitive payloads without an authorized retention purpose. A partially wired outbox would
also leave Kafka, Redis, source-offset, and consumer-deduplication crash windows unresolved.

`TransactionFeatureEnricherServiceTest` contains targeted failure-injection tests for the existing
baseline call order. They prove that a publisher exception prevents the subsequent Redis recording
call, and that a Redis recording exception occurs after the publisher call has completed. These are
unit-level executable demonstrations of the current service boundary; they do not simulate an
ambiguous real broker acknowledgment or prove cross-system delivery.

## Blockers before A05 can be reconsidered

- An independently authenticated institution/source identity and a verified binding from that
  principal to the immutable occurrence and source sequence.
- Approved purpose, minimization, access controls, encryption/key custody, retention, erasure, and
  replay horizon for exact enriched payload bytes, including raw device fields.
- Production Mongo replica-set configuration and a transactionally atomic occurrence-plus-outbox
  write, with deterministic event/idempotency keys and exact versioned serialized bytes.
- Bounded worker claims with owner/token/generation fencing, retry of identical bytes after unknown
  acknowledgments, visible terminal quarantine, and downstream inbox/deduplication.
- Fault-injection proof across Mongo commit uncertainty, Kafka send/ack uncertainty, duplicate and
  reordered delivery, worker races/restarts, Redis partial writes and replay, and rollback.
- Proof that the outbox path preserves baseline Rules/ML effects and repairs Redis from the committed
  occurrence without fallback publication or a double effect.

Until these gates have evidence, A05 remains **engineering NO-GO**. The disabled-foundation Mongo
tests do not authorize production delivery, and the baseline publish/Redis sequence makes no
exactly-once, distributed-ACID, or broker-ack-reconciliation claim.
