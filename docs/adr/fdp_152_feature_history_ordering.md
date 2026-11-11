# FDP-152-027: attested feature-history ordering

**Status:** accepted runtime safeguard; the pre-fix race was reproduced against live MongoDB and Redis, and the regression is covered by the authenticated integration suite.

**Scope:** attested RAW admission through ENRICHED Redis-history finalization. No wire event changes.

## Finding and observed boundary

The current code durably admits a RAW receipt and encrypted RAW publication before the ENRICHER calculates a snapshot. The ENRICHER loads that snapshot before preparing its durable ENRICHED publication. A separate recovery worker claims ENRICHED publications independently. Redis atomically rejects an event older than its applied watermark, and exact replay is commitment-checked, but those checks happen after a later event may already have derived and committed its snapshot. The existing recovery lease fences one publication; it does not order distinct transactions for one customer. The same-customer Kafka key only orders normal partition delivery and does not cover the independent recovery path or RAW DLT replay.

The gap was reproduced in the authenticated Mongo replica-set and real Redis harness: A was durably admitted, then deliberately failed before Redis append; B for the same scope/customer was admitted and scored from history missing A; recovery of A then failed closed as `HISTORY_DATA_INVALID` after the watermark had advanced. This is test-harness evidence of the interleaving, not a claim that a production incident occurred.

## Decision

Use a durable **single active admission gate per attested feature namespace and customer**, stored in a strict Mongo collection owned by the source-ingest schema owner. Its deterministic `_id` is a domain-separated SHA-256 digest of issuer, institution, source, source epoch, and customer ID; the document stores no plaintext customer ID. The feature namespace fields intentionally match the existing `FeatureNamespace.attested` scope, with customer added.

`acceptRaw` acquires the gate in the same Mongo transaction as receipt admission, RAW lineage binding, and encrypted RAW outbox insertion. A new admission can activate an absent or resolved gate. An already-active gate causes a bounded `ORDER_PENDING`/HTTP 503 result and aborts the transaction, so the later request consumes no receipt capacity, identity, RAW outbox row, or Kafka publication. Exact retries of the active receipt continue through the existing replay path. Mongo unique `_id` plus transaction write-conflict retry serializes simultaneous admissions across pods; no JVM mutex or read-then-write check is relied on.

Every attested ENRICHER lookup, preparation, claim, recovery claim, and operator redrive must revalidate that the gate is active for the exact receipt and RAW commitment. The first lookup is before `loadSnapshot`; preparation and claim repeat the check transactionally. The gate remains active through RAW publication, missing ENRICHED preparation, ENRICHED publish retry, Redis retry, DLT, lease expiry, and bounded retry exhaustion. Those states cannot be skipped by accepting a later transaction.

After Redis append, the ENRICHER acknowledges history in a Mongo transaction that both changes the exact ENRICHED row to `historyState=APPLIED` and resolves the matching gate. If Mongo's acknowledgement is uncertain, the gate is either still active or was resolved in the same committed transaction; recovery verifies/replays the same receipt-bound Redis commitment before attempting the Mongo acknowledgement again. Kafka publication remains at-least-once and is not part of the gate release condition. A row whose history is already applied but Kafka publication is pending therefore does not block the next observation.

An event that is genuinely late relative to the already-applied watermark remains fail-closed: it has no ENRICHED/SCORED output and keeps its signed RAW audit anchor. Only the specific pre-append `FEATURE_EVENT_TIME_OUT_OF_ORDER` result may resolve its gate with a durable `LATE_REJECTED` disposition, after exact RAW authority and gate ownership are revalidated. Redis watermark is monotonic, so redelivery remains late and cannot later contribute history. Any other failure, including integrity error, revoked authority, missing evidence, expiry, or retry exhaustion, leaves the gate active for authorized reconciliation. This may block that customer until the operator can prove the event's history effect or perform an approved source/feature-namespace cutover; it must never be silently cleared.

## State and failure matrix

| A state | Mongo / Redis result | Gate | B admission and history |
|---|---|---|---|
| RAW accepted; no ENRICHED row yet | RAW receipt/outbox durable | Active | B gets retryable 503; no B receipt/outbox/score |
| ENRICHED prepared; Redis fails before append | ENRICHED `historyState=PENDING`; no A marker | Active | B deferred at admission; exact A retry/recovery first |
| Redis applies A; Mongo history ACK fails | Redis has A marker/watermark; Mongo may still be pending | Active unless ACK transaction committed | B deferred; recovery verifies exact A commitment, then resolves gate |
| History APPLIED; Kafka send pending/failed | Mongo says APPLIED; Redis contains A | Resolved | B may proceed; A Kafka publication recovers independently |
| A is a genuinely late new observation | RAW remains; no ENRICHED/SCORED | `LATE_REJECTED` only after the specific late check | B may proceed; A remains fail-closed on redelivery |
| A is revoked, corrupt, expired, quarantined, or exhausted | Effect may be unknown or untrusted | Active | No silent skip; operator escalation required |
| Different scope or customer | Different hashed `_id` | Independent | Progress is isolated |

Equal event timestamps remain valid under the existing Redis contract. A later-admitted event with an earlier timestamp remains genuinely late and is rejected; this gate does not reorder events by claimed event time.

## Schema, rollout, and operational limits

Add a strict, versioned gate collection with an `_id` index only; point reads and compare-and-set updates avoid customer scans. Keep one document per distinct scope/customer and reuse it after resolution, so the number of documents cannot exceed the existing 100,000 lifetime receipt cap. The owner migration installs and verifies the validator and exact index. ENRICHER receives only `find` and narrowly scoped `update`; it cannot create/alter schema or delete fences. The owner creates/activates gates from the receipt transaction. Gate documents have no TTL. Runtime startup fails closed if the exact schema is absent or incompatible.

Roll out under a maintenance boundary: pause attested admission, RAW recovery, ENRICHER writers/recovery, and compaction; drain Kafka and DLT work; verify there are no unresolved RAW publications or ENRICHED history rows; run the owner migration; deploy all gate-aware writers/readers; then resume traffic. Do not mix old and new ENRICHER binaries or resume ingress while any old admitted event lacks a gate. If the precondition fails, drain/reconcile the outstanding work before rollout; do not initialize an empty gate set over it.

The gate deliberately has no automatic expiry. If A cannot be recovered before source-trust retention ends, the affected customer remains blocked until an authorized reconciliation proves the exact effect or an approved cutover rebuilds history under a new source epoch. Capacity counters, receipt tombstones, and trust fences are never reset to clear the gate. Metrics expose only aggregate active/resolved/late-rejected counts and oldest-active age.

## Deterministic proof required before production changes

Use the authenticated Mongo replica-set and real Redis fixtures in `SourceLineagePipelineIntegrationTest`. The live pre-fix reproduction established the race. The post-fix regression proves B receives HTTP 503 `SOURCE_RECEIPT_ORDER_PENDING` without consuming a capacity slot or creating B's RAW/outbox, then succeeds after A's history is applied; its snapshot includes both A and B.

Companion cases cover a failure after Lua but before Mongo acknowledgement; RAW accepted before ENRICHED preparation; lease takeover; equal-time and genuinely late events; duplicate A; independent recovery; and different scope/customer progress. A real Kafka/Mongo/Redis listener test verifies that a genuinely late event keeps `FEATURE_EVENT_TIME_OUT_OF_ORDER` in RAW DLT and creates no ENRICHED/SCORED output. A migration test verifies that unresolved legacy history blocks gate creation and that the owner migration installs the strict no-TTL schema after reconciliation. The tests use live services and explicit failpoints rather than sleeps or mock-only evidence for the ordering guarantee.

These are local Testcontainers results on the current working tree. They do not replace required GitHub checks on the final pushed SHA or prove production deployment readiness.
