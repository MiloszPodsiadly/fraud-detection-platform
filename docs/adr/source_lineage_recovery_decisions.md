# Source origin, replay and recovery decisions

Status: implementation decision for the source-lineage repair work. This records
intended behavior; infrastructure tests remain the evidence for whether each
path is implemented correctly.

## Authority and atomicity

- External authority is established only by the configured Ed25519 verifier and
  its issuer, institution, source, epoch, purpose, audience, key and revocation
  policy. Event fields and Kafka metadata never create that authority.
- At internal Kafka hops, the producer attaches a private keyed origin proof
  bound to the exact event commitment. A verified `PROVEN_LEGACY` assertion is
  the only route that continues legacy processing during a lineage sidecar
  outage. Missing or invalid proof plus unavailable lineage is unresolved and
  fails closed; absence of a Mongo row is not evidence of legacy origin.
- Receipt admission, stable receiver RAW identity/payload, RAW lineage binding
  and durable publication intent share one Mongo replica-set transaction with
  snapshot reads and majority writes. Kafka publication follows commit. An
  unknown Mongo commit or Kafka acknowledgement remains unresolved and is
  reconciled by reading/re-publishing the same durable event.
- Scored replay identity is `(authorized receipt, run ID, exact enriched
  commitment)`. Exact replay returns stored scored bytes and identity; changed
  interpretation conflicts. A separately authorized rescore requires a
  distinct internal run ID and never overwrites the original occurrence.
- The occurrence collection validator requires an explicit owner-run additive
  migration before deploying the recovery state machine. Existing v1 rows and
  indexes remain intact; service principals do not receive `collMod` access.
  Run `SourceOccurrenceRecoveryMigration.migrateValidator(database)` once with
  the schema-owner Mongo client before enabling the new scorer. The migration
  accepts only the exact prior strict validator and changes no documents or
  indexes; scorer startup rejects an unmigrated validator.
- Scored publication uses an exclusive 120-second Mongo lease, eight bounded
  attempts with exponential delays, and a terminal `RETRY_EXHAUSTED` state.
  An internal redrive revalidates current lineage and ciphertext, records a
  fixed reason and recovery epoch, and is capped at three epochs. The recovery
  diagnostic reports only bounded counts and oldest pending age.

## Decision matrix

| Case | Result and durable state | Scoring / authority | Recovery owner |
|---|---|---|---|
| Signed source admitted | Accept after receipt, stable RAW event, binding and outbox intent commit together | Score only after verified lineage; Ed25519 authority | Ingest RAW recovery worker |
| Signed conflicting identity/payload | Conflict; preserve replay fence | No scoring or new authority | Ingest owner |
| Unsigned legacy proven by private origin proof | Preserve legacy behavior; do not infer an attested receipt | Legacy scoring may run; no external authority claim | Legacy pipeline |
| Origin unknown | Unresolved; do not mutate shared legacy Redis state | No scoring or authority claim | Origin/lineage owner |
| Sidecar unavailable | Continue only with valid proof of proven legacy; otherwise unresolved | Legacy proof may score; attested traffic requires current lineage | Service owner after sidecar recovery |
| Exact duplicate signed request | Reuse stored RAW ID, timestamp and bytes; no additional RAW link | Idempotent under same authority | Ingest recovery worker |
| Duplicate with changed features | Conflict against stored enriched commitment | Never reuse stale score | Enrichment/scoring owner |
| New authorized rescore | New explicit internal run; original occurrence immutable | Score after current policy and lineage verify | Scoring owner |
| Broker acknowledgement lost | Pending/unresolved; retry identical bytes; mark published only after durable ack record | Do not recompute score | Corresponding outbox worker |
| Mongo commit ambiguous | Unresolved; reconcile stable identity before retrying writes | No assumption that commit succeeded | Owning Mongo store/worker |
| Key revoked | Deny new admission and recovery requiring current authority; preserve fence | No scoring under revoked authority | Trust-policy owner |
| Cross-institution matching business IDs | Keep receipts, Redis namespace and occurrence binding scoped | Each source uses only its verified lineage | Ingest/enrichment/scoring owners |

## Execution order

1. Repair the exact scoring-context contract and serialization tests.
2. Make RAW publication durable and exact-retry safe.
3. Namespace attested Redis feature state.
4. Enforce enriched binding and replay safety.
5. Enforce exact scored replay commitments.
6. Separate proven legacy origin from unknown origin during outages.
7. Add terminal scored-publication state, controlled redrive and diagnostics.
8. Move Mongo persistence out of the event-contract module without changing
   schemas or cryptographic serialization.
9. Run the adversarial Mongo/Kafka/Redis/restart matrix.
10. Run final focused and reactor checks on the resulting HEAD; report blockers
    honestly. No merge is part of this work.

## Local integration measurements

Collected on 2026-11-10 from the Windows 11 test host (Java 21.0.10, 12
processors, 8 GiB maximum JVM heap) using MongoDB 7.0.31, Kafka 3.8.0 and Redis
7.2.13 Testcontainers. Docker reported no explicit container memory/CPU limits.
These are local diagnostic samples, not production SLOs or throughput claims.

| Measurement | Result |
|---|---|
| Ed25519 verifier, 64-byte payload (25 warmups, 250 samples) | p50 1.180 ms; p95 1.609 ms |
| Ed25519 verifier, 65,536-byte payload (25 warmups, 250 samples) | p50 1.471 ms; p95 1.740 ms |
| Maximum-scope receipt BSON | 1,055 bytes |
| Representative durable RAW publication BSON | 2,090 bytes |
| Exact scored replay (5 warmups, 30 samples) | p50 14.945 ms; p95 18.402 ms |
| RAW recovery batch through Mongo and acknowledged Kafka publication | 8/8 intents in 282.659 ms (28.30 events/s) |
| Attested ingress through Redis enrichment in the E2E harness | 1,681.452 ms; includes test-consumer round trips |
| Scoring, occurrence persistence and Kafka publication | 108.174 ms |
| Alert projection Mongo write | 26.672 ms |

The E2E stage timings are single-run measurements. Receipt, lineage, occurrence
and outbox BSON sizes were respectively 624, 488, 520, 4,346 and 2,090 bytes
for the measured fixtures. The E2E sample does not provide a statistically
valid before/after comparison or a production throughput estimate.

## Durable RAW and ENRICHED recovery operations

The RAW recovery scheduler runs publication recovery before compaction, with
separate exception boundaries. A compaction failure therefore cannot skip
delivery in that poll. Its one-minute health log includes pending, live lease,
quarantine reason, exhausted epoch and oldest recoverable age counts. The
QUARANTINED/PUBLISH_FAILED queue may be redriven only with a current, unexpired
receipt and the exact stored AES-GCM payload after current source policy,
receipt identity, RAW commitment, event ID and timestamp validation. The
attempt counter resets only while incrementing a recovery epoch (maximum three).
AUTHORITY_DENIED and PAYLOAD_UNAVAILABLE are terminal and have no operator
override.

Before deploying the new RAW strict validator, the source schema owner runs
SourceRawPublicationRecoveryMigration.migrateValidator(database) using the
owner Mongo client. It accepts only the exact previous strict validator and
existing recovery index, applies collMod, and preserves every row. Limited
ingest credentials cannot perform this operation. If the migration or app
startup reports an incompatible schema, stop rollout and inspect validator,
validation mode and indexes; do not grant collMod or create-index privileges
to the service.

The ENRICHED outbox now uses the same Mongo claim, 30-second lease, attempt
counter and fencing token for live processing and independent scheduled
recovery. Recovery reads only the ENRICHER-visible receipt, RAW lineage,
ENRICHED lineage and encrypted ENRICHED publication. It does not read the RAW
encrypted publication collection. The stored event is authenticated, matched
to its immutable commitment, event ID, timestamp and lineage link, and checked
against current receipt expiry and source policy before the worker emits an
internal KNOWN_ATTESTED proof. Recovery does not recompute the feature snapshot
from Redis. Mongo tracks Kafka publication and Redis history finalization as
separate states. The worker first applies the attested Redis history effect,
then publishes the exact encrypted-outbox event. An already PUBLISHED row with
pending history is claimed only for history finalization and is not sent to
Kafka again. Acknowledgement is at-least-once: a process death after broker ACK
can produce a duplicate with the same event bytes and identity, and the
downstream exact occurrence fence remains responsible for deduplication.

If a PUBLISHED row's encrypted payload or current authority fails validation
during history recovery, Mongo quarantines `historyState` separately while
preserving `state=PUBLISHED`. Transient sidecar unavailability receives a
bounded lease backoff; invalid payload and denied authority become terminal
history quarantine reasons and are included in health counts. This prevents an
unrecoverable row from being reclaimed on every scheduler cycle or starving
other PUBLISHED history rows.

Attested Redis history uses one Lua operation across customer/merchant windows,
known devices, a transaction commitment marker, and last transaction state. Its
five v3 keys share a customer-derived Redis Cluster hash slot. v3 keys hash the
attested scope and customer; reads temporarily dual-read v2 attested keys while
their configured TTLs expire. Legacy `feature:` keys and behavior are
unchanged. If Mongo says history is applied but the Redis commitment marker is
missing, processing reports rebuild-required rather than silently declaring
history complete.

For an existing ENRICHED collection, the schema owner runs
SourceEnrichedPublicationRecoveryMigration.migrate(database) before deploying
the new ENRICHER. This migration accepts only the exact prior strict validator
or an exact partial migration state, adds bounded recovery/history indexes,
installs the new strict validator, and backfills missing history fields as
PENDING without changing publication state or encrypted event bytes. Old
PUBLISHED rows therefore remain published but can be claimed for history
finalization. Service startup verifies the exact new validator and indexes; it
never repairs them. Pause old attested writers across the validator change,
because they do not write the required history fields. Keep the ENRICHER runtime
role separate from the schema owner. Treat this as a forward-only migration
once a new app has written history or lease fields; roll forward with a
corrected migration or application build rather than removing fields from live
rows. See the [ENRICHED publication recovery runbook](../runbooks/source_enriched_publication_recovery.md)
for rollout, Redis compatibility, health, and operator commands.

Both operator redrive paths are one-shot application launches with
spring.main.web-application-type=none; they reject launches without that
explicit setting and expose no HTTP route. Example RAW launch properties are
fraud.source-raw-publications.redrive.enabled=true,
fraud.source-raw-publications.redrive.receipt-id=<64-lowercase-hex> and
fraud.source-raw-publications.redrive.reason=BROKER_RECOVERED. ENRICHED uses
fraud.source-enriched-publications.redrive.enabled=true,
fraud.source-enriched-publications.redrive.publication-id=<64-lowercase-hex>
and fraud.source-enriched-publications.redrive.reason=BROKER_RECOVERED. ENRICHED
also requires `fraud.source-enriched-publications.redrive.operator-mode=true`.
Both custom listener factories force auto-startup off in operator mode, and the
ENRICHED operator context excludes scheduled recovery. Use
spring.kafka.listener.auto-startup=false for these launches as an explicit
runtime assertion. The operator must be authorized by the protected
deployment/control-plane RBAC that grants
access to start this one-shot process and supply its service credentials; this
repository contains no cluster RBAC policy. The Mongo role still enforces the
service's normal least-privilege boundary. Logs contain only safe reason and
epoch for redrive, and queue counts for health; receipt/publication IDs,
source proofs, ciphertext, customer fields and secrets are not logged.

Monitor RAW and ENRICHED health warnings for persistent pending age, exhausted
attempts, quarantine and recovery-epoch exhaustion. Resolve broker connectivity
first. For a transient broker-only failure, perform a single approved redrive
and confirm that the scheduler drains the same stored event. For authority or
payload failures, investigate policy/key/retention or encrypted-row integrity;
do not override the quarantine.
