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
