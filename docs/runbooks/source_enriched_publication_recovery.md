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

## Required roles and access

- A schema owner performs both Mongo migrations. Its custom role needs scoped
  `createCollection`, `createIndex`, `collMod`, and backfill read/write actions
  only on the ENRICHED publication, business-identity, receipt, admission
  identity, and RAW lineage collections. It does not need general `drop` or
  `delete` access.
- The ENRICHER runtime uses its restricted service principal. Do not add schema
  owner privileges to this role.
- A protected deployment or control-plane RBAC policy must authorize launching
  a one-shot redrive and supplying its runtime credentials. This repository does
  not define that external RBAC policy.
- Record migration and redrive approvals in the deployment change record. Never
  put Mongo URIs, encryption keys, source proofs, customer data, ciphertext, or
  raw receipt/publication IDs in tickets or logs.

## Mongo migration and rollout

Run both owner migrations before starting the new ENRICHER version. Pause
attested ENRICHED writers for the migration window so an old writer cannot
insert a row while the strict validator and durable business-identity fence are
being prepared.

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
binding mismatch. Invoke both from the approved schema-owner maintenance
program with a separately supplied owner Mongo client and the publication key
ring, for example:

```java
import com.frauddetection.common.events.source.SourceEnrichedPublicationRecoveryMigration;
import com.frauddetection.common.events.source.SourceEnrichedBusinessIdentityMigration;
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
}
```

The maintenance program must obtain the URI, database name, and publication
key ring from the approved secret/configuration source. Do not run either
migration with
`SourceLineageClients.open` or ENRICHER service credentials. Confirm completion
by starting a restricted ENRICHER instance: startup validates the exact strict
validators and all required indexes, and does not repair them. Verify and
record the `backfilledRows` count in the READY marker in
`source_enriched_business_identities`.

Roll forward in this order:

1. Pause attested ENRICHED writers and verify the owner change record.
2. Run both owner migrations once with the schema-owner client and current
   publication decryption key ring.
3. Deploy the new ENRICHER with source lineage and the scheduled recovery owner
   enabled. Keep ordinary RAW listener startup under normal service policy.
4. Drain old ENRICHER processes before relying on the bounded history retry
   budget. The new validator keeps retry fields optional for compatibility with
   existing rows and old binaries, so an old worker can still run old retry
   behavior until it is stopped.
5. Confirm startup passes schema checks and observe pending, leased, exhausted,
   quarantined, and oldest-pending health values.
6. Resume attested traffic after the independent recovery worker is healthy.

The new Mongo schema requires history state on writes. Do not run old and new
attested ENRICHER writers concurrently across the migration boundary. Public
Kafka event shapes are unchanged, so consumers can remain wire-compatible.

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
disjoint v2/v3, and overlapping v2/v3 histories. Each reported profile has 3
warmups and 8 timed reads. Values below are milliseconds for p50/p95/max,
allocated bytes per read, observed post-read heap peak in bytes, GC count/time
delta, and Redis commands per read. The routine profile covers 0, 1,000,
10,000, and 100,000 union entries; the separate 250,000-to-1,000,000 stress
profile was skipped.

| Union | Mode | p50 / p95 / max ms | Allocated B/read | Heap peak B | GC count / ms | Redis commands/read |
|---:|---|---:|---:|---:|---:|---:|
| 0 | v3 only | 16.149 / 20.216 / 20.216 | 33,292 | 43,586,320 | 0 / 0 | 12 |
| 0 | v2 only | 15.588 / 18.763 / 18.763 | 33,176 | 44,634,896 | 0 / 0 | 12 |
| 0 | disjoint | 13.947 / 15.368 / 15.368 | 33,145 | 46,732,048 | 0 / 0 | 12 |
| 0 | overlap | 14.281 / 18.416 / 18.416 | 33,139 | 48,829,200 | 0 / 0 | 12 |
| 1,000 | v3 only | 19.023 / 29.647 / 29.647 | 306,602 | 56,169,232 | 1 / 8 | 12 |
| 1,000 | v2 only | 15.588 / 19.777 / 19.777 | 306,706 | 38,501,784 | 0 / 0 | 12 |
| 1,000 | disjoint | 14.819 / 17.080 / 17.080 | 306,677 | 46,890,392 | 0 / 0 | 12 |
| 1,000 | overlap | 17.020 / 20.172 / 20.172 | 531,516 | 57,376,152 | 1 / 3 | 12 |
| 10,000 | v3 only | 25.226 / 31.802 / 31.802 | 2,615,613 | 56,327,576 | 1 / 4 | 12 |
| 10,000 | v2 only | 20.535 / 38.778 / 38.778 | 2,615,619 | 57,570,472 | 3 / 15 | 12 |
| 10,000 | disjoint | 20.522 / 31.245 / 31.245 | 2,615,781 | 57,469,664 | 3 / 9 | 12 |
| 10,000 | overlap | 27.924 / 34.650 / 34.650 | 4,746,853 | 150,448,448 | 0 / 0 | 12 |
| 100,000 | v3 only | 113.638 / 158.951 / 158.951 | 28,476,445 | 232,259,584 | 15 / 139 | 12 |
| 100,000 | v2 only | 100.043 / 129.843 / 129.843 | 28,476,540 | 293,601,280 | 10 / 84 | 12 |
| 100,000 | disjoint | 94.326 / 145.427 / 145.427 | 28,477,509 | 333,447,168 | 9 / 78 | 12 |
| 100,000 | overlap | 197.638 / 260.904 / 260.904 | 51,622,373 | 308,281,344 | 16 / 148 | 12 |

The measured implementation replaces the merchant-ID `LinkedHashMap` with a
`HashMap`; merchant map iteration order is not part of the output contract.
At 100,000 entries, measured allocation fell from 29,276,461 to 28,476,445 B
for v3-only, 29,276,556 to 28,476,540 B for v2-only, 29,277,525 to 28,477,509
B for disjoint data, and 52,422,389 to 51,622,373 B for overlapping data. The
change reduces allocation by about 2.7% in the first three profiles and 1.5%
for overlap; p95 varies between runs, so this is not an SLO claim. The
conflicting duplicate timestamp profile failed closed in 17.183 ms. No
250,000-to-1,000,000 result is available.

The post-change run used JDK 21.0.10, `-Xmx1g`, `redis:7.2-alpine` constrained
to 1 GiB, Redis `maxmemory=0` within that container limit, and a host reporting
33,570,611,200 bytes of memory. It ran with:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dtest=RedisMerchantHistoryBenchmarkTest" `
  "-Dmerchant.history.benchmark=true" "-DargLine=-Xmx1g" `
  "-Dsurefire.failIfNoSpecifiedTests=false" -pl feature-enricher-service -am test
```

The benchmark validates exact union counts and conflicting duplicate handling,
but does not establish a latency target or measured capacity at the one-million
contract ceiling. Keep the separately gated high-bound stress profile for a
resource-controlled job.

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
