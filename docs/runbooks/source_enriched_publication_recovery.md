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

- A schema owner performs the Mongo validator and index migration. The owner
  credential needs only the migration privileges for this database, including
  `collMod` and `createIndex`.
- The ENRICHER runtime uses its restricted service principal. Do not add schema
  owner privileges to this role.
- A protected deployment or control-plane RBAC policy must authorize launching
  a one-shot redrive and supplying its runtime credentials. This repository does
  not define that external RBAC policy.
- Record migration and redrive approvals in the deployment change record. Never
  put Mongo URIs, encryption keys, source proofs, customer data, ciphertext, or
  raw receipt/publication IDs in tickets or logs.

## Mongo migration and rollout

Run the migration before starting the new ENRICHER version. Pause attested
ENRICHED writers for the migration window so an old writer cannot insert a row
without the new required history fields after the strict validator changes.

The migration accepts only the known prior strict validators or an exact
partially completed migration state. It adds the recovery and history indexes,
updates the strict validator, and backfills existing rows with
`historyState=PENDING` and `historyAppliedAt=null`. Existing publication state,
event bytes, IDs, and feature snapshots are preserved. Old `PUBLISHED` rows stay
published and are claimed only to finalize Redis history. An incompatible
validator or index set fails closed; stop and inspect it rather than granting
runtime roles extra privileges.

The operation is exposed as
`SourceEnrichedPublicationRecoveryMigration.migrate(MongoDatabase)` in
`source-persistence`. Invoke it from the approved schema-owner maintenance
program with a separately supplied owner Mongo client, for example:

```java
import com.frauddetection.common.events.source.SourceEnrichedPublicationRecoveryMigration;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

String migrationUri = approvedSecretProvider.get("SOURCE_LINEAGE_MIGRATION_URI");
String databaseName = approvedConfig.get("SOURCE_LINEAGE_DATABASE");
try (MongoClient owner = MongoClients.create(migrationUri)) {
    SourceEnrichedPublicationRecoveryMigration.migrate(owner.getDatabase(databaseName));
}
```

The maintenance program must obtain `migrationUri` and `databaseName` from the
approved secret/configuration source. Do not run the migration with
`SourceLineageClients.open` or ENRICHER service credentials. Confirm completion
by starting a restricted ENRICHER instance: startup validates the exact strict
validator and all required indexes, and does not repair them.

Roll forward in this order:

1. Pause attested ENRICHED writers and verify the owner change record.
2. Run `SourceEnrichedPublicationRecoveryMigration.migrate` once with the
   schema-owner client.
3. Deploy the new ENRICHER with source lineage and the scheduled recovery owner
   enabled. Keep ordinary RAW listener startup under normal service policy.
4. Confirm startup passes schema checks and observe pending, leased, exhausted,
   quarantined, and oldest-pending health values.
5. Resume attested traffic after the independent recovery worker is healthy.

The new Mongo schema requires history state on writes. Do not run old and new
attested ENRICHER writers concurrently across the migration boundary. Public
Kafka event shapes are unchanged, so consumers can remain wire-compatible.

## Redis key compatibility

Attested writes use the v3 hashed customer slot tag. All five keys touched by
the atomic history Lua script share that Redis Cluster slot. Legacy `feature:`
keys and their writes are unchanged. Attested reads also check existing v2
keys while old state expires naturally; there is no broad key scan or bulk key
copy.

Keep the v2 dual-read path enabled until at least 180 days after the last old
attested writer is stopped, matching the configured known-device TTL. The
transaction-history and last-transaction keys have shorter configured TTLs
(8 and 30 days by default). Monitor Redis memory and the effective TTL
configuration before removing dual reads in a separate reviewed change.

If Mongo records `historyState=APPLIED` but the Redis commitment marker is
missing, processing fails with a rebuild-required condition. Do not mark history
complete from Mongo alone or delete v2/v3 data to clear the condition. Restore
Redis from its supported persistence/backup or use the approved recovery plan.

## Normal runtime and health

When `fraud.source-lineage.enabled=true`, normal runtime requires
`fraud.source-enriched-publications.recovery.enabled=true`; otherwise startup
fails with `SOURCE_ENRICHED_RECOVERY_OWNER_REQUIRED`. Configure the polling
schedule through the recovery properties. Defaults are 5 seconds initial delay
and 30 seconds fixed delay. The worker claims one event at a time and applies
the Redis history effect before waiting up to the configured Kafka ACK deadline
(5 seconds by default, below the 30-second lease with a 20-second processing
reserve).

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

Redrive only a broker-related `RETRY_EXHAUSTED` publication after confirming
broker health and current source authority. A successful run only requeues the
same stored event; the normal recovery worker performs the next publication.
Each publication is limited to three operator recovery epochs.

Load the normal protected runtime configuration first. It must provide the
restricted ENRICHER Mongo identity, current source-verification policy,
ENRICHED publication encryption keys, and standard application connection
settings from the secret manager. Set the publication ID in the protected
operator environment, not in a shared shell history. Then run:

```powershell
if ($env:SOURCE_ENRICHED_PUBLICATION_ID -notmatch '^[0-9a-f]{64}$') {
    throw 'A valid publication ID is required.'
}

& java -jar $env:FEATURE_ENRICHER_EXEC_JAR `
  --spring.main.web-application-type=none `
  --spring.kafka.listener.auto-startup=false `
  --fraud.source-lineage.enabled=true `
  --fraud.source-enriched-publications.recovery.enabled=false `
  --fraud.source-enriched-publications.redrive.operator-mode=true `
  --fraud.source-enriched-publications.redrive.enabled=true `
  --fraud.source-enriched-publications.redrive.publication-id=$env:SOURCE_ENRICHED_PUBLICATION_ID `
  --fraud.source-enriched-publications.redrive.reason=OPERATOR_RECONCILIATION

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
