# Private Device diagnostics after SCORED publication

Status: test-only evidence for the private Stage B diagnostic path. Recovery evidence is recorded in [device_resilience.md](device_resilience.md). Independent final review, source-hierarchy reconciliation and production activation remain open. Current release gates are recorded in [Device Verified Source Admission](../adr/device_verified_source_admission.md).

## Scope and boundary

The private, read-only Stage B lookup runs only after a source-authorized SCORED fact has been published. The package-private `PrivateDeviceDiagnosticReader` is not a Spring bean, controller, HTTP endpoint, active `FraudSignalEngine`, or public event producer. No scoring lifecycle invokes it and no Device values are added to Engine Intelligence, the public scored contract, fraud score, risk level, alert, or recommendation.

The existing pure policy and its reason-code owner moved from `fraud-scoring-service` into `common-events` so both the scorer and private reader can call the same implementation. The algorithm, signal order, reasons and support rules are unchanged. Only `DEVICE_NOT_SEEN_RECENTLY` and `DEVICE_SWITCH` can be evaluated from complete trusted history. `OTHER_CUSTOMERS`, `COUNTRY_MISMATCH`, and `PROXY_OR_VPN` remain unsupported. The policy has no numeric score and can report a degraded aggregate.

## Exact Stage B binding

The Stage A adapter first verifies the original source signature, receipt, RAW and ENRICHED lineage and the independent Device observation and coverage signatures. After a real scored occurrence is committed as `PUBLISHED`, the adapter resolves the actual `SourceOccurrenceAccess` capability and appends one private binding to the immutable Device occurrence. The binding hashes the full signed source scope, receipt, RAW and ENRICHED commitments, Stage A snapshot and provenance commitments, plus the exact SCORED event ID, `createdAt`, fingerprint and run ID. Only the initial scoring run is eligible; a rescore of the same transaction is rejected.

The snapshot has a deterministic Stage A commitment over its identity, evidence, provenance, coverage authority and retention metadata. A separate, domain-separated HMAC under the configured Device pseudonym key authenticates persisted candidate, membership, snapshot and coverage facts. The reader and writer verify these tags before using the corresponding Mongo fields, so a database writer cannot make altered facts valid by recalculating the unkeyed Stage A or scored binding digests. These tags do not establish Device authority; source receipts, Device grants and coverage grants still pass their existing checks. No original assertion or signature bytes are retained. Missing or invalid tags fail closed, and changing the pseudonym key makes prior tags unverifiable unless an authorized continuity path is provided.

A binding never rewrites Stage A evidence. The Mongo validator accepts an optional binding with an exact field set, and the store only writes it when absent; an existing equal binding is idempotent and a different one conflicts. The validator binds every source-scope field. Databases with an earlier validator require a deliberate owner-run `collMod` after review; the service does not repair validators or migrate data at startup.

The diagnostic reader verifies the source receipt and lineage again, resolves the exact published scored occurrence through the read-only source capability, checks the initial run and exact ID/time/fingerprint, and checks the Device binding and Stage A version. Each Device pass reads the current occurrence, candidate, observations, historical occurrences, coverage and revocation tombstone in one majority snapshot transaction. Before returning, a second fresh snapshot repeats the bounded three-set comparison and checks that the current Stage A commitment, exact SCORED binding, coverage revision/generation and tombstone state still match. Source lineage, the initial SCORED occurrence and historical source grants are revalidated outside the Device transaction; this is not a distributed snapshot across the source and Device databases. Any inconsistent or unavailable revalidation returns `UNAVAILABLE`.

For supported history, Stage A stores a private HMAC commitment and count for the exact union of historical observation members used to derive its bounded facts. The anchor lives on the current immutable occurrence in the existing collection; it stores no raw event IDs or duplicate history. Stage B requires the observation, occurrence and candidate ID sets to match and recomputes the anchor. Candidate status and terminal metadata also have a separate HMAC, so a BSON status edit cannot manufacture a committed history member. Missing anchors on older rows, one-sided deletion, deletion of both history copies, deletion of a candidate, or deletion of all three historical copies returns `UNAVAILABLE`. The anchor detects gaps only while the current occurrence and application integrity key remain trusted; it does not prove upstream population completeness and does not protect against compromise of that key. The signed population claim still cannot prove that the source omitted nothing.

The bounded history scan returns unavailable above 1,000 rows or after its five-second end-to-end budget. Missing staged facts return PENDING; missing, incomplete, expired, revoked, inconsistent or unavailable facts fail closed.

## Capacity and query budget

These limits serve different purposes and are not interchangeable:

| Limit | Value | Meaning |
| --- | ---: | --- |
| Policy available window | 30 days | Required complete pre-event interval; not a row capacity |
| Persisted Stage A device/customer history | 10,000 / 25,000 rows | Ledger bounds used while creating immutable evidence |
| Persisted evidence observations | 25,000 | Evidence representation bound |
| Private reader verification cap | 1,000 members per queried representation | Every observation, occurrence and candidate set must fit and match exactly; 1,001 returns `UNAVAILABLE` |
| Reader time and Mongo operation budget | 5 seconds | End-to-end verification budget; Mongo client timeout must be positive and no greater than this limit |

The reader queries the device/customer union with the existing epoch, institution, source, key-version and event-time indexes for each customer/device branch. It sorts by `eventTime` and `sourceEvent` for deterministic traversal. The `MAX + 1` read detects overflow before interpreting a truncated set. Startup remains read-only; no index is created or repaired by the reader. The exact index keys are covered by Mongo schema/startup guards. This repository has not yet captured representative 100/500/1,000-row latency percentiles or database-operation counts: performance is **NOT MEASURED**, and no runtime SLO is claimed.

The result exposes only the private bounded policy interpretation and fixed outcome. Its string representation, the fact reference, grant and proof classes redact content. The reader emits no logs, metrics, traces, API payloads or Kafka records.

## Mongo roles

The test proves three separate identities against authenticated Mongo:

| Principal | Access |
| --- | --- |
| `scored_writer` | Existing source read path and scored outbox find/insert/update required to prepare, claim and confirm publication |
| `source_diagnostic_reader` | Source identity/receipt/RAW/ENRICHED reads and scored-occurrence find; no source or outbox writes |
| `device_reader` | `find`, `listIndexes` on the five Device read collections and database `listCollections`; no writes, source access, fences or schema operations |

Both private readers check the authenticated principal and the complete allowed privilege set at construction. They reject extra actions, databases or collections. The code path does not construct a reader with an owner or writer client.

## Test evidence

`PrivateDeviceDiagnosticReaderIntegrationTest` uses disposable Ed25519 source, Device and coverage signers, the real receipt owner and RAW/ENRICHED chain from `VerifiedDeviceAdmissionIntegrationTest`, an authenticated Mongo replica set, the actual Mongo scored-occurrence store, and a real Kafka broker. It sends a version-2 `TransactionScoredEvent`, waits for the broker acknowledgement, confirms the exact outbox claim, consumes the Kafka bytes, and derives the read reference from that consumed event. The values are test fixtures; this is not production issuer, population or key custody evidence.

The five integration tests verify:

- complete pre-event history yields novelty and switching interpretations while the other three signals stay unsupported; score remains null and the existing SCORED bytes and EI omission contract remain unchanged;
- a scored event published while Stage A is still staged returns PENDING; a later initial score can bind, but a rescore, wrong fingerprint/event ID, wrong customer or wrong epoch cannot read or replace the binding; revoked coverage authority returns UNAVAILABLE;
- a short-coverage empty history returns UNAVAILABLE with null observed values, never a false novelty result; read-only Mongo identities cannot update Device, source or scored-occurrence rows, and writer credentials cannot construct the private reader.
- changing either side of linked history, the candidate status, or all three historical copies invalidates subsequent reads; the persisted Stage A member anchor detects complete deletion, including after a reader restart;
- controlled post-snapshot races that delete a history row, change coverage revision/generation, add an operational revocation tombstone, or revoke the source receipt all return `UNAVAILABLE` during final revalidation.

Before the persisted Stage A history-membership commitment was added, the authenticated Mongo reproducer deleted both the prior observation and its occurrence while retaining the candidate. The reader returned `EVALUATED`, showing that matching surviving collections alone did not detect a dual deletion (**RED**). With the commitment included in the immutable Stage A version, the same test now returns `UNAVAILABLE` with no evaluation, including on repeated reads and after a reader restart (**GREEN**).

Targeted integration command:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PrivateDeviceDiagnosticReaderIntegrationTest" test
```

On 2026-11-12 this finished **BUILD SUCCESS; 5 tests, 0 failures, 0 errors, 0 skipped**. The pre-existing startup guard also passed in the targeted regression batch, so attempted production activation remains rejected.

The immutable Stage A corruption case returns `DEVICE_STORE_CORRUPT_SNAPSHOT` before attempting a stale identity read. The shared policy, mapper, engine, runtime graph and reason-code regression selection ran **28 tests, 0 failures, 0 errors, 0 skipped**. Stage A schema, identity and recovery evidence is recorded in [verified admission](device_verified_admission.md).

The corrected corruption case plus policy/runtime/reason-code regression command was:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl feature-enricher-service,fraud-scoring-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=MongoDeviceObservationStoreIntegrationTest#occurrenceIdentityMismatchCannotBeReadAsValidEvidence,DeviceRiskSignalPolicyTest,DeviceRiskSignalEvaluationMapperTest,DeviceRiskSignalEngineTest,EngineIntelligenceConditionalRuntimeGraphTest,FraudEngineReasonCodeOwnershipProducerTest" test
```

It finished **BUILD SUCCESS; 28 tests, 0 failures, 0 errors, 0 skipped**. After clarifying the startup guard message, its focused command also finished **BUILD SUCCESS; 4 tests, 0 failures, 0 errors, 0 skipped**:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=DeviceRiskAdmissionConfigurationTest" test
```

## Sanitized evidence chain

`test source signer -> actual receipt owner -> verified RAW and encrypted ENRICHED -> independently signed Device observation and population coverage -> immutable pre-event snapshot -> initial SCORED outbox record -> broker acknowledgement and consumed SCORED bytes -> append-once private occurrence binding -> read-only scoped diagnostic`.

No concrete receipt, event ID, transaction/customer/device identifier, token, commitment, source IP or assertion bytes are included in this report.

## Remaining gates

This is test-only implementation evidence. It does not approve the external source population, legal/privacy retention, production key custody, production Mongo roles, operator schema migration, independent review, or active Device engine use. Recovery and contract evidence is recorded in [device_resilience.md](device_resilience.md) and [Device Verified Source Admission](../adr/device_verified_source_admission.md).

Independent review and production activation remain open.
