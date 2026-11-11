# Source ENRICHED publication recovery

## Purpose and scope

This runbook covers the attested ENRICHED outbox, its independent recovery worker,
Redis feature-history finalization, the owner-run Mongo migration, and a bounded
one-shot operator redrive. It does not change legacy Redis behavior or the RAW,
ENRICHED, or SCORED wire contracts.

The Mongo outbox retains the canonical encrypted ENRICHED event and tracks Kafka
publication (`state`) separately from Redis feature history (`historyState`).
Recovery revalidates current source authority and payload integrity, applies the
same attested Redis history effect, then publishes the stored event bytes. Kafka
delivery remains at-least-once; Mongo and Redis do not share a distributed
transaction.

Code merge and deployment are separate decisions. Passing repository tests can
support code review; it does not authorize institutional onboarding. Deployment
also requires approved live issuer/source keys and scopes, owner-only Mongo
migrations and roles, retention/key-rotation approval, Kafka producer routing
coordination, a measured receipt-capacity threshold and alert route, and an
operator-approved recovery/cutover plan.

## Required roles and access

- A schema owner performs the three Mongo migrations. Its custom role needs scoped
  `createCollection`, `createIndex`, `collMod`, and backfill read/write actions
  only on the ENRICHED publication, business-identity, receipt, admission
  identity, RAW lineage, and feature-order gate collections. It does not need
  general `drop` or `delete` access.
- The ENRICHER runtime uses its restricted service principal. Do not add schema
  owner privileges to this role.
- A protected deployment or control-plane RBAC policy must authorize launching
  a one-shot redrive and supplying its runtime credentials. This repository does
  not define that external RBAC policy.
- Record migration and redrive approvals in the deployment change record. Never
  put Mongo URIs, encryption keys, source proofs, customer data, ciphertext, or
  raw receipt/publication IDs in tickets or logs.

## Source receipt capacity and rollover

`fraud.source-receipts.max-receipts` is configured for the receipt database and
must be between 1 and 100,000. There is no universal production capacity value
in this repository. The replay horizon must be positive and at most 30 days;
receipt retention must be at least the replay horizon plus five minutes and
at most 90 days. The exact limits are bound to the database's capacity policy
commitment, so changing them in place causes startup to fail with
`SOURCE_RECEIPT_CAPACITY_POLICY_INCOMPATIBLE`.

Capacity is lifetime-based and shared by every issuer, institution, source, and
source epoch that uses the same Mongo database. A single
`source_admission_limits` record stores `count`; each new admitted receipt
reserves a unique capacity slot and increments that counter in the same Mongo
transaction as its replay identity and receipt. The counter is never
decremented. After retention, compaction deletes the receipt and encrypted RAW
and ENRICHED replay material but leaves the admission identity as a tombstone
with its capacity slot. This keeps replays fenced and the consumed capacity
unavailable.

At the configured cap, an exact replay of a still-admitted matching identity
continues to return `EXISTING`; changed bindings conflict, and expired or
tombstoned identities return `TOMBSTONED`. A new identity returns
`CAPACITY_EXCEEDED` before RAW lineage or a RAW publication intent is created.
The ingest endpoint returns HTTP 503 with the bounded category
`SOURCE_RECEIPT_CAPACITY_EXCEEDED`; capacity exhaustion must never publish RAW
without an admitted receipt. The authenticated Mongo integration tests cover
duplicate replay at capacity, expiration/compaction, retained tombstones, and
the absence of a RAW outbox row on capacity rejection.

The persistent count is not included in the RAW recovery health log. Monitor it
through the protected read-only schema-owner operations view: compare
`source_admission_limits.count` for `_id="capacity"` with the active approved
`max-receipts` setting. Configure an early warning threshold from the
deployment's observed admission rate and the lead time needed for an approved
rollover; alert critically when count reaches the cap. The count and remaining
capacity are low-cardinality database signals. Do not add receipt IDs,
customer fields, issuer/source labels, or policy commitments to metrics or
logs.

The repository cannot choose a numeric warning point or alert route without a
deployment-specific admission rate and cutover lead time. Before enabling
attested production traffic, record those inputs, the selected threshold, the
on-call route, and the owner-approved cutover action. A schema-owner query can
read the count without returning receipt or customer data:

```javascript
db.source_admission_limits.findOne({ _id: "capacity" }, { _id: 0, count: 1 })
```

Capacity cannot be reset by deleting the counter or tombstones, raising the
limit, or changing source epoch. A new database or policy epoch requires an
explicit source-owner and schema-owner approved cutover plan that preserves
the replay fence across the configured replay horizon. The service does not
perform implicit rollover or migrate old tombstones into a new database.

## Mongo migration and rollout

Run all three owner migrations before starting the gate-aware ENRICHER version.
Pause attested source admission, RAW replay/recovery, ENRICHED writers/recovery,
and receipt compaction for the migration window. Drain in-flight work before
the owner scans publications. This maintenance boundary prevents old and new
writers from changing publication, receipt, RAW lineage, and fence inputs
while the migrations run; Mongo does not provide one atomic transaction across
these collections. Do not treat the migration as an all-database snapshot.

The outbox migration accepts only the known prior strict validators or an exact
partially completed migration state. It adds the recovery and history indexes,
updates the strict validator, and backfills existing rows with
`historyState=PENDING` and `historyAppliedAt=null`. Existing publication state,
event bytes, IDs, and feature snapshots are preserved. Old `PUBLISHED` rows stay
published and are claimed only to finalize Redis history. An incompatible
validator or index set fails closed; stop and inspect it rather than granting
runtime roles extra privileges.

The outbox operation is exposed as
`SourceEnrichedPublicationRecoveryMigration.migrate(MongoDatabase)` in
`source-persistence`. The business-identity backfill is exposed as
`SourceEnrichedBusinessIdentityMigration.migrate(MongoDatabase,
SourceEnrichedPublicationCipher, Clock)`. It decrypts and verifies every
unexpired stored publication before creating its scoped business-identity
fence; it fails closed above its 100,000-row bound or on any receipt/publication
binding mismatch. Invoke all three in the approved schema-owner maintenance
program with a separately supplied owner Mongo client and the publication key
ring, for example:

```java
import com.frauddetection.common.events.source.SourceEnrichedPublicationRecoveryMigration;
import com.frauddetection.common.events.source.SourceEnrichedBusinessIdentityMigration;
import com.frauddetection.common.events.source.SourceFeatureOrderGateMigration;
import com.frauddetection.common.events.source.SourceEnrichedPublicationCipher;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Clock;

String migrationUri = approvedSecretProvider.get("SOURCE_LINEAGE_MIGRATION_URI");
String databaseName = approvedConfig.get("SOURCE_LINEAGE_DATABASE");
SourceEnrichedPublicationCipher publicationCipher = approvedPublicationCipherFactory.create();
try (MongoClient owner = MongoClients.create(migrationUri)) {
    var database = owner.getDatabase(databaseName);
    SourceEnrichedPublicationRecoveryMigration.migrate(database);
    SourceEnrichedBusinessIdentityMigration.migrate(database, publicationCipher, Clock.systemUTC());
    SourceFeatureOrderGateMigration.migrate(database, Clock.systemUTC());
}
```

The maintenance program must obtain the URI, database name, and publication
key ring from the approved secret/configuration source. Do not run these
migrations with
`SourceLineageClients.open` or ENRICHER service credentials. Confirm completion
by starting a restricted ENRICHER instance: startup validates the exact strict
validators and all required indexes, and does not repair them. Verify and
record the `backfilledRows` count in the READY marker in
`source_enriched_business_identities`.

The business-identity migration writes fences before it writes READY. A crash
or invalid publication can therefore leave exact fences with no READY marker;
keep writers paused, correct the underlying problem, then rerun the owner
migration. It accepts an existing fence only when receipt, RAW, ENRICHED, scope,
and expiry commitments match exactly. A collision or incompatible fence stops
the backfill and leaves ENRICHER startup closed. Startup checks the strict
validator, TTL index, READY record, and bounded fence-row shape; it never
repairs schema or data.

`SourceFeatureOrderGateMigration.migrate(MongoDatabase, Clock)` installs the
strict, versioned `source_feature_order_gates` collection. It fails closed if
any unexpired ENRICHED history is not `APPLIED`, a RAW publication remains
pending or quarantined, or an unexpired bound RAW row lacks its exact applied
ENRICHED publication. The bounded pre-gate scan rejects more than 100,000
unexpired RAW rows. Resolve or expire pre-gate work under source-owner policy
before retrying; do not create an empty gate collection over in-flight or DLT
RAW work. The gate uses only the `_id` index, stores a domain-separated hash
rather than customer data, and has no TTL. ENRICHER can read and update gates,
but cannot insert, delete, or alter their schema. A bounded aggregate view may
report `ACTIVE`, `RESOLVED`, and `LATE_REJECTED` counts and oldest active age;
never include gate IDs or customer fields in logs or metrics.

The repository exposes the durable gate state through Mongo rather than a
runtime gate-metrics endpoint. A deployment-owned, low-frequency monitor can
run these bounded owner-read queries (the collection is limited to the
100,000-receipt lifetime capacity):

```javascript
db.source_feature_order_gates.aggregate([
  { $group: { _id: "$status", count: { $sum: 1 } } },
  { $project: { _id: 0, status: "$_id", count: 1 } }
])

db.source_feature_order_gates.aggregate([
  { $match: { status: "ACTIVE" } },
  { $group: { _id: null, active: { $sum: 1 }, oldestActiveAt: { $min: "$activatedAt" } } },
  { $project: { _id: 0, active: 1, oldestActiveAt: 1 } }
])
```

Alert on a sustained active count or age according to the deployment's
reconciliation capacity. The monitor reports counts and timestamps only; it
does not expose the hashed `_id`, receipt ID, customer, or source labels.

Only unexpired ENRICHED publications are backfilled. The fence expiry matches
the source receipt's expiry and the TTL index removes it after that retention
deadline. Source-owner receipt compaction also deletes the corresponding
business fence in the same transaction that removes encrypted replay material,
while retaining the source admission tombstone to reject replays. Expired
publications are not reconstructed or adopted by the migration.

Roll forward in this order:

1. Pause attested ENRICHED writers and verify the owner change record.
2. Run all three owner migrations once with the schema-owner client and current
   publication decryption key ring.
3. Deploy the new ENRICHER with source lineage and the scheduled recovery owner
   enabled. Keep ordinary RAW listener startup under normal service policy.
4. Drain old ENRICHER processes before relying on the bounded history retry
   budget. The new validator keeps retry fields optional for compatibility with
   existing rows and old binaries, so an old worker can still run old retry
   behavior until it is stopped.
5. Confirm startup passes schema checks and observe pending, leased, exhausted,
   quarantined, oldest-pending, and gate-active health values. Use the protected
   schema-owner operations view for aggregate gate status counts and oldest
   active age; expose no gate IDs or customer labels.
6. Resume attested traffic after the independent recovery worker is healthy.

The new Mongo schema requires history state on writes. Do not run old and new
attested ENRICHER writers concurrently across the migration boundary. Public
Kafka event shapes are unchanged, so consumers can remain wire-compatible.

While a gate is `ACTIVE`, a later admission for the same source scope and
customer returns HTTP 503 `SOURCE_RECEIPT_ORDER_PENDING`. The transaction
aborts before reserving lifetime receipt capacity or creating a replay identity,
RAW row, or RAW outbox entry. The caller may retry after the earlier event's
history is applied. An active gate has no timeout: if the earlier event is
quarantined, revoked, corrupt, or expired, retain the barrier until an
authorized reconciliation proves its exact history effect or approves a
source/feature-namespace cutover. Never delete or reset a gate to restore
traffic.

## Business-identity startup validation profile

`SourceEnrichedBusinessIdentitySchema.initialize()` retains the bounded
`verifyRecords()` scan on each ENRICHER startup. The opt-in Testcontainers
probe seeds valid fences, checks Mongo `executionStats`, and records elapsed
initialization time, thread allocation, and heap observations. One local run
used JDK 21.0.10, Mongo `mongo:7.0`, an 8,392,802,304-byte maximum Java heap,
and a host reporting 33,570,611,200 bytes of memory (7,346,049,024 bytes free
at probe start).

| Fences | Documents checked / explain examined | Explain ms | One init ms / allocated bytes | Three init ms per init / allocated bytes per init |
|---:|---:|---:|---:|---:|
| 0 | 1 / 1 | 0 | 46.364 / 852,392 | 43.374 / 852,490 |
| 10,000 | 10,001 / 10,001 | 2 | 166.697 / 66,497,824 | 113.556 / 61,882,037 |
| 100,000 | 100,001 / 100,001 | 24 | 833.192 / 635,607,648 | 841.946 / 610,373,821 |

Thread allocation is cumulative allocation during initialization, not retained
heap. The 100,000-row run's observed heap-used increase was about 124 MiB for
one initialization and 61 MiB for the three-initialization batch; a later
initialization can collect prior temporary objects. This is a single local
profile, not a production startup SLO. No operating-target threshold was
provided, so the full scan remains in place to validate existing fence shape
and READY integrity. Do not remove it based on Mongo's strict validator, which
does not retroactively validate stored documents.

To reproduce the probe:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dsource.business-identity.startup.benchmark=true" `
  -pl fraud-scoring-service -am `
  -Dtest=SourceLineagePipelineIntegrationTest#recordsOptInBusinessIdentityStartupScanProfile test
```

## Redis key compatibility

Attested writes use the v3 hashed customer slot tag. All six keys touched by
the atomic history Lua script share that Redis Cluster slot. This includes a
customer transaction-ID survival index written with the receipt-bound
commitment marker. A missing marker with surviving customer or merchant
membership fails closed; the script never adopts those memberships for a new
receipt. Legacy `feature:` keys and their writes are unchanged. Attested reads
also check existing v2 keys while old state expires naturally; there is no
broad key scan or bulk key copy. During the v2-to-v3 migration window these are separate bounded Redis
reads, not an atomic cross-slot snapshot. Concurrent v2 writes can race the
union and yield a transient feature snapshot; conflicting duplicate timestamps
fail closed. Drain v2 writers before relying on a point-in-time migration
snapshot.

Keep the v2 dual-read path enabled until at least 180 days after the last old
attested writer is stopped, matching the configured known-device TTL. The
transaction-history and last-transaction keys have shorter configured TTLs
(8 and 30 days by default). Monitor Redis memory and the effective TTL
configuration before removing dual reads in a separate reviewed change.

### Attested event-time ordering

No source contract currently defines a bounded event-time lateness allowance.
The ENRICHER therefore accepts a new attested transaction only when its
`transactionTimestamp` is equal to or later than the customer's latest applied
attested timestamp. A regression fails before Redis window pruning with
`FEATURE_EVENT_TIME_OUT_OF_ORDER`; the Kafka error handler sends it directly to
the RAW DLT. The ENRICHER durably changes only that exact event's active
order gate to `LATE_REJECTED` after revalidating RAW authority and gate
ownership. Redelivery remains a late rejection and cannot contribute feature
history. The signed RAW record and verified source authority remain intact,
but no ENRICHED publication or downstream SCORED occurrence is created. Do not
retry by changing Redis state, replacing the source proof, or sending the event
through the legacy path. Keep the DLT item for source-owner reconciliation.

This accepted late-event outcome is different from an earlier in-order event
whose feature history is pending: its gate stays `ACTIVE`, and later same-scope
admission receives `SOURCE_RECEIPT_ORDER_PENDING` until recovery safely applies
the earlier effect. Neither RAW DLT nor exhausted retries implicitly clears an
active gate. Keep the DLT item and reconcile it under source-owner policy.

Attested RAW records are partitioned by a SHA-256 digest of customer ID so one
customer is processed serially across ENRICHER instances. Legacy RAW records
continue using the transaction-ID key. When deploying this routing change,
pause source admission and RAW recovery, drain the RAW consumer group and
in-flight attested RAW outbox, deploy the producer and ENRICHER versions
together, and resume only after the consumer is healthy. Old and new routing
must not be active together for the same customer's in-flight records.

Exact RAW replay still resolves the existing canonical publication before
feature calculation, so it remains idempotent even after normal velocity or
merchant-window pruning. The one-minute window remains exclusive at its lower
bound and inclusive at its upper bound; this ordering check does not change the
feature windows or legacy behavior.

The v3 receipt-bound commitment marker and customer transaction-ID survival
index outlive naturally pruned velocity-window memberships. Natural expiry from
the one-minute customer window or seven-day merchant window therefore does not
undo an already applied, otherwise verifiable history effect. An exact replay
does not contribute a second history entry. A missing marker or survival index,
or a missing membership that should still be inside its active window, remains a
rebuild-required integrity failure. Do not mark history complete from Mongo
alone or delete v2/v3 data to clear the condition. Restore Redis from its
supported persistence/backup or use the approved recovery plan. This guarantee
is bounded by the configured receipt and Redis retention model; it does not
promise deduplication after arbitrary key loss or expiry.

## Independent Redis history retry budget

Redis history finalization and verification have their own durable
`historyAttempts` counter, separate from Kafka publication `attempts`. This
also covers failures verifying a row already marked `APPLIED` while its Kafka
publication is still pending. Transient Redis/Mongo or network failures are
retried with delays of 1, 2, 4, 8, 16, 32, 64, then 128 seconds.
The eighth failed history attempt moves the row to
`historyState=QUARANTINED` with `historyFailureCode=HISTORY_RETRY_EXHAUSTED`;
known deterministic integrity/input failures are quarantined immediately with
`HISTORY_DATA_INVALID`. Quarantined history is not automatically selected, is
included in bounded health counts, and does not consume a Kafka publication
attempt. Existing PUBLISHED state and its `publishedAt` remain unchanged.

After repairing the underlying cause, an authorized operator can use the
one-shot `OPERATOR_RECONCILIATION` redrive described below. The application
rechecks current receipt authority, expiry, and encrypted event integrity,
reuses the same canonical event and business-identity fence, resets the history
retry schedule, and increments the existing recovery epoch. For quarantined
history it clears the `APPLIED` marker so recovery reapplies that same stored
history effect before Kafka send. The limit is three total recovery epochs.
Expired, revoked, malformed, or otherwise unauthorized rows cannot be
redriven.

## Measured merchant-history read profile

The gated benchmark uses Redis Testcontainers and measures v2-only, v3-only,
disjoint v2/v3, and overlapping v2/v3 histories. Each routine profile has 3
warmups and 8 timed reads. Values are milliseconds for p50/p95/max, allocated
bytes per read, observed post-read heap peak in bytes, GC count/time delta, all
Redis command-stat calls per read (excluding the INFO snapshots), and Redis
`used_memory` after the reads. The 8-sample nearest-rank p95 equals the
maximum; it is only a descriptive sample, and no p99 is reported. The routine
profile covers 0, 1,000, 10,000, and 100,000 union entries. High-bound 250,000,
500,000, and 1,000,000 profiles are separately gated and were not run.

| Union | Mode | p50 / p95 / max ms | Allocated B/read | Post-read heap peak B | GC count / ms | Commands/read | Redis used memory B |
|---:|---|---:|---:|---:|---:|---:|---:|
| 0 | v3 only | 23.152 / 27.339 / 27.339 | 37,906 | 36,557,160 | 0 / 0 | 14 | 1,087,296 |
| 0 | v2 only | 19.002 / 21.642 / 21.642 | 39,292 | 38,654,312 | 0 / 0 | 14 | 1,087,296 |
| 0 | disjoint | 13.180 / 17.889 / 17.889 | 37,741 | 39,702,888 | 0 / 0 | 14 | 1,087,296 |
| 0 | overlap | 14.300 / 17.989 / 17.989 | 37,741 | 41,800,040 | 0 / 0 | 14 | 1,087,296 |
| 1,000 | v3 only | 17.765 / 20.444 / 20.444 | 311,308 | 55,431,528 | 0 / 0 | 14 | 1,198,984 |
| 1,000 | v2 only | 19.842 / 21.310 / 21.310 | 311,055 | 36,899,856 | 0 / 0 | 14 | 1,199,160 |
| 1,000 | disjoint | 17.550 / 19.919 / 19.919 | 311,285 | 45,288,464 | 0 / 0 | 14 | 1,200,304 |
| 1,000 | overlap | 18.227 / 22.302 / 22.302 | 536,120 | 56,822,800 | 1 / 2 | 14 | 1,286,640 |
| 10,000 | v3 only | 27.372 / 31.986 / 31.986 | 2,620,223 | 57,028,520 | 1 / 4 | 14 | 2,161,424 |
| 10,000 | v2 only | 23.241 / 32.957 / 32.957 | 2,620,221 | 79,477,384 | 0 / 0 | 14 | 2,160,096 |
| 10,000 | disjoint | 19.080 / 24.427 / 24.427 | 2,620,389 | 144,489,096 | 0 / 0 | 14 | 2,152,600 |
| 10,000 | overlap | 25.285 / 30.915 / 30.915 | 4,751,593 | 197,966,472 | 1 / 2 | 14 | 3,210,872 |
| 100,000 | v3 only | 121.675 / 303.130 / 303.130 | 28,481,055 | 229,638,144 | 11 / 174 | 14 | 10,738,728 |
| 100,000 | v2 only | 99.430 / 138.799 / 138.799 | 28,481,053 | 223,746,704 | 15 / 135 | 14 | 10,749,864 |
| 100,000 | disjoint | 84.608 / 109.056 / 109.056 | 28,482,117 | 290,256,016 | 10 / 87 | 14 | 10,739,912 |
| 100,000 | overlap | 163.256 / 185.050 / 185.050 | 51,626,981 | 318,489,928 | 17 / 147 | 14 | 20,391,712 |

An additional R4 rerun exercised only the routine 100,000-entry profile. Keep
these measurements separate from the table above; they are a new eight-sample
run, not a replacement or a general performance promise.

| Union | Mode | p50 / p95 / max ms | Allocated B/read | Post-read heap peak B | GC count / ms | Commands/read | Redis used memory B |
|---:|---|---:|---:|---:|---:|---:|---:|
| 100,000 | v3 only | 84.044 / 106.060 / 106.060 | 28,481,053 | 240,648,192 | 11 / 95 | 14 | 10,743,888 |
| 100,000 | v2 only | 97.949 / 146.304 / 146.304 | 28,481,148 | 213,909,504 | 15 / 104 | 14 | 10,744,928 |
| 100,000 | disjoint | 95.521 / 129.821 / 129.821 | 28,482,117 | 241,696,768 | 15 / 122 | 14 | 10,737,656 |
| 100,000 | overlap | 155.449 / 211.765 / 211.765 | 51,626,981 | 277,594,032 | 20 / 146 | 14 | 20,385,112 |

The malformed duplicate-timestamp profile failed closed in 12.578 ms in this
rerun. It used JDK 21.0.10, `-Xmx1g`, `redis:7.2-alpine` constrained to 1 GiB
with Redis `maxmemory=0`, 3 warmups, 8 samples per mode, and a host reporting
33,570,611,200 bytes total memory. The high-bound profile was not run because
the configured 1 GiB heap and absent stress opt-in did not satisfy its resource
gate: `NOT_EXECUTED_RESOURCE_GATED`. The one-million value remains a contract
ceiling, not a demonstrated operating limit or SLO.

The benchmark replaces the merchant-ID `LinkedHashMap` with a `HashMap`; map
iteration order is not part of the output contract. The corrected command
instrumentation sums Redis command-stat calls and excludes its own INFO
snapshots. The conflicting duplicate timestamp profile failed closed, in
12.308 ms in this run. Heap peak is sampled after each completed read and may
miss a short-lived peak within a read. These measurements are diagnostic and
do not establish a latency or throughput SLO.

The validation run used JDK 21.0.10, `-Xmx1g`, `redis:7.2-alpine` constrained
to 1 GiB, Redis `maxmemory=0` within that container limit, and a host reporting
33,570,611,200 bytes total memory. It ran with:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dtest=RedisMerchantHistoryBenchmarkTest" `
  "-Dmerchant.history.benchmark=true" "-DargLine=-Xmx1g" `
  "-Dsurefire.failIfNoSpecifiedTests=false" -pl feature-enricher-service -am test
```

The high-bound test requires both benchmark and stress opt-ins, at least a
4 GiB Java heap, at least 8 GiB free host memory, and has a 20-minute timeout.
It seeds in bounded 10,000-entry batches. This run used a 1 GiB heap and did
not enable the stress property, so the 250,000, 500,000, and 1,000,000 profiles
were intentionally not measured. The one-million feature bound is a resource
ceiling, not evidence that the profile fits a 1 GiB heap or meets a latency
target.

To opt into the high-bound test after confirming the heap and free-memory
preconditions, add `-Dmerchant.history.stress=true` and `-DargLine=-Xmx4g` to
the Maven command above. The test reports each size/mode as it completes; stop
the run if host or container memory pressure appears.

## Normal runtime and health

When `fraud.source-lineage.enabled=true`, normal runtime requires
`fraud.source-enriched-publications.recovery.enabled=true`; otherwise startup
fails with `SOURCE_ENRICHED_RECOVERY_OWNER_REQUIRED`. Configure the polling
schedule through the recovery properties. Defaults are 5 seconds initial delay
and 30 seconds fixed delay. The worker claims one event at a time. It selects
`historyState=PENDING` rows when their separate history retry time is due, and
also selects `historyState=APPLIED` rows whose Kafka publication is still
pending or whose publication lease expired. This retries Kafka after a broker
failure without reapplying Redis history or consuming the history retry budget.
It applies or verifies the Redis history effect before renewing the same
unexpired Mongo token immediately before Kafka send. Renewal rechecks authority
and `historyState=APPLIED`, and reserves 10 seconds for the send. A stale worker
does not durably confirm another worker's lease; a late broker send after a
successful renewal can still be an identical at-least-once duplicate. A
deferred send releases its claim without consuming a Kafka publication attempt.
The configured Kafka ACK deadline is 5 seconds by default.

The bounded health log reports pending work (including already published rows
that still need Redis history), live leases, exhausted retries, quarantined
rows, oldest pending age, and the current cycle attempt count. It contains no
receipt IDs, event data, customer fields, proofs, or ciphertext. Investigate a
persistently growing pending count, old pending age, exhausted retries,
quarantine, or lease count that does not drain. Resolve Mongo, Redis, Kafka,
authority, or key-retention causes before redriving.

If a PUBLISHED row's stored event or current authority cannot be verified while
recovering Redis history, the worker quarantines `historyState` independently
and preserves `state=PUBLISHED`. Payload corruption is reported as
`PAYLOAD_UNAVAILABLE`; a denied or revoked authority is reported as
`AUTHORITY_DENIED`. Transient sidecar unavailability keeps the history pending
and applies a short lease backoff. These rows are terminal for automatic history
recovery and are counted in the quarantine health total; inspect the underlying
payload or authority issue before taking any manual action.

## One-shot ENRICHED redrive

For broker recovery, redrive only a broker-related `RETRY_EXHAUSTED`
publication after confirming broker health and current source authority. For
history reconciliation, redrive only `historyState=QUARANTINED` rows after the
underlying Redis or data-integrity issue has been repaired and reviewed. Use
`OPERATOR_RECONCILIATION` for the history path. A successful run only requeues
the same stored event; the normal recovery worker performs the next publication
or history finalization. Each publication is limited to three operator
recovery epochs.

Load the normal protected runtime configuration first. It must provide the
restricted ENRICHER Mongo identity, current source-verification policy,
ENRICHED publication encryption keys, and standard application connection
settings from the secret manager. Set the publication ID in the protected
operator environment, not in a shared shell history. Then run:

```powershell
if ($env:SOURCE_ENRICHED_PUBLICATION_ID -notmatch '^[0-9a-f]{64}$') {
    throw 'A valid publication ID is required.'
}
if ($env:SOURCE_ENRICHED_REDRIVE_REASON -notin @('BROKER_RECOVERED', 'OPERATOR_RECONCILIATION')) {
    throw 'A valid redrive reason is required.'
}

& java -jar $env:FEATURE_ENRICHER_EXEC_JAR `
  --spring.main.web-application-type=none `
  --spring.kafka.listener.auto-startup=false `
  --fraud.source-lineage.enabled=true `
  --fraud.source-enriched-publications.recovery.enabled=false `
  --fraud.source-enriched-publications.redrive.operator-mode=true `
  --fraud.source-enriched-publications.redrive.enabled=true `
  --fraud.source-enriched-publications.redrive.publication-id=$env:SOURCE_ENRICHED_PUBLICATION_ID `
  --fraud.source-enriched-publications.redrive.reason=$env:SOURCE_ENRICHED_REDRIVE_REASON

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

The protected one-shot mode starts no HTTP server, scheduled recovery task,
Kafka listener, or topic-creation bean. The custom listener factory also forces
`autoStartup=false` in this mode. A zero exit means the exact publication was
requeued and the one-shot process closed successfully. Invalid input, denied or
unavailable publication, exhausted epochs, source-policy failure, or startup
failure must produce a nonzero exit; investigate the diagnostic category
without exposing the ID. After a zero exit, confirm the normal recovery owner
claims and drains the publication. Do not start the ordinary recovery scheduler
inside the one-shot process.

## Forbidden actions and recovery limits

- Do not grant `collMod` or `createIndex` to the ENRICHER runtime.
- Do not bypass current issuer/source authority checks or force redrive of
  authority-denied or payload-unavailable rows.
- Do not replay by creating a new event ID, recalculating the feature snapshot,
  or publishing replacement bytes.
- Do not promise exactly-once Kafka delivery or atomic Mongo/Redis/Kafka commit.
  A lost Kafka ACK can cause the identical event to be delivered again; downstream
  occurrence fencing handles exact duplicates.
- Redis history is idempotent only within the configured keys, commitment marker,
  and TTL model. Eviction of an applied marker is an explicit rebuild-required
  condition, not evidence that the history is complete.

## Audit and escalation

Record the approver, change reference, operator identity, reason, publication
result category, and post-run queue health in the protected deployment audit
system. Do not record the raw publication ID or event contents there unless the
organization's approved sensitive-data process explicitly requires it. Escalate
schema mismatch to the Mongo owner, authority/key problems to the source trust
owner, and sustained storage or broker failures to the service owner.

## Non-claims

This runbook does not establish external source authority, enable Device or
Merchant decisions, change scoring math, provide cluster RBAC, or make Kafka,
Mongo, and Redis a single transaction.
