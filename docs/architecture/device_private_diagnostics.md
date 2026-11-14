# Private Device diagnostics after SCORED publication

Status: test-only evidence for the private Stage B diagnostic path. Recovery evidence is recorded in [device_resilience.md](device_resilience.md). Independent final review, source-hierarchy reconciliation and production activation remain open. Current release gates are recorded in [Device Verified Source Admission](../adr/device_verified_source_admission.md).

## Scope and boundary

The private, read-only Stage B lookup runs only after a source-authorized SCORED fact has been published. The package-private `PrivateDeviceDiagnosticReader` is not a Spring bean, controller, HTTP endpoint, active `FraudSignalEngine`, or public event producer. No scoring lifecycle invokes it and no Device values are added to Engine Intelligence, the public scored contract, fraud score, risk level, alert, or recommendation.

The existing pure policy and its reason-code owner moved from `fraud-scoring-service` into `common-events` so both the scorer and private reader can call the same implementation. The algorithm, signal order, reasons and support rules are unchanged. Only `DEVICE_NOT_SEEN_RECENTLY` and `DEVICE_SWITCH` can be evaluated from complete trusted history. `OTHER_CUSTOMERS`, `COUNTRY_MISMATCH`, and `PROXY_OR_VPN` remain unsupported. The policy has no numeric score and can report a degraded aggregate.

## Exact Stage B binding

The Stage A adapter first verifies the original source signature, receipt, RAW and ENRICHED lineage and the independent Device observation and coverage signatures. After a real scored occurrence is committed as `PUBLISHED`, the adapter resolves the actual `SourceOccurrenceAccess` capability and appends one private binding to the immutable Device occurrence. The binding hashes the full signed source scope, receipt, RAW and ENRICHED commitments, Stage A snapshot and provenance commitments, plus the exact SCORED event ID, `createdAt`, fingerprint and run ID. Only the initial scoring run is eligible; a rescore of the same transaction is rejected.

The snapshot has a deterministic Stage A commitment over its identity, evidence, provenance, coverage authority and retention metadata. A separate, domain-separated HMAC under the configured Device pseudonym key authenticates persisted candidate, membership, snapshot and coverage facts. The reader and writer verify these tags before using the corresponding Mongo fields, so a database writer cannot make altered facts valid by recalculating the unkeyed Stage A or scored binding digests. These tags do not establish Device authority; source receipts, Device grants and coverage grants still pass their existing checks. No original assertion or signature bytes are retained. Missing or invalid tags fail closed, and changing the pseudonym key makes prior tags unverifiable unless an authorized continuity path is provided.

A binding never rewrites Stage A evidence. The Mongo validator accepts an optional binding with an exact field set, and the store only writes it when absent; an existing equal binding is idempotent and a different one conflicts. The validator binds every source-scope field. Databases with an earlier validator require a deliberate owner-run `collMod` after review; the service does not repair validators or migrate data at startup.

The diagnostic reader verifies the source receipt and lineage again, resolves the exact published scored occurrence through the read-only source capability, checks the initial run and exact ID/time/fingerprint, and checks the Device binding and Stage A version. Each Device pass reads the current occurrence, candidate, observations, historical occurrences, coverage and revocation tombstone in one majority snapshot transaction. Before returning, a second fresh snapshot repeats the bounded observation/occurrence/candidate comparison and checks that the current Stage A commitment, exact SCORED binding, coverage revision/generation and tombstone state still match. Sealed history-member anchors are checked when Stage A creates that commitment; Stage B does not need anchor-collection access. Source lineage, the initial SCORED occurrence and historical source grants are revalidated outside the Device transaction; this is not a distributed snapshot across the source and Device databases. Any inconsistent or unavailable revalidation returns `UNAVAILABLE`.

Two distinct integrity records protect history membership. The existing Stage A `historyMembershipCommitment` and count are stored on the current immutable occurrence and bind the exact union of retained historical observation members used to derive its bounded facts. Separately, `device_history_member_anchors` stores one pseudonymized, HMAC-protected membership record for each candidate when its source seal is accepted. These anchors contain no raw event payload or original event identifier and expire at `eventTime + 31 days + 5 minutes`, aligned with the retained observation window. Stage A compares observations, occurrences, committed candidates and sealed anchors; any mismatch makes supported history unavailable and omits the membership commitment. Stage B checks that commitment against its bounded observation/occurrence/candidate sets and does not read anchors. Candidate status and terminal metadata also have a separate HMAC, so a BSON status edit cannot manufacture a committed history member. Missing anchors, one-sided deletion, deletion of both history copies, deletion of a candidate, or deletion of all three event copies before a later Stage A returns incomplete history rather than false novelty. An authorized operational revoke deletes matching anchors with observations and invalidates coverage in the same transaction.

This protection detects loss only while at least one sealed anchor and the application integrity key remain trusted. A database administrator able to erase every representation, including anchors, or compromise of the integrity key is outside this guarantee. Anchors do not prove upstream population completeness; the signed population claim still cannot prove that the source omitted nothing. Existing databases must pass the exact strict schema and index guard for the new collection. There is no startup migration or validator repair; any owner-run migration/backfill must derive anchors from independently verified original seal/source artifacts. Where those artifacts are unavailable, the store must fail closed rather than reconstruct a completeness claim from surviving rows.

The bounded history scan returns unavailable above 1,000 rows or when its soft five-second logical validation budget is exceeded. The reader checks the budget between synchronous source and Mongo operations; it cannot interrupt an operation already in flight, so this is not a five-second wall-clock request deadline. A slow dependency can make the total request exceed five seconds before the next check returns `UNAVAILABLE`. Missing staged facts return PENDING; missing, incomplete, expired, revoked, inconsistent or unavailable facts fail closed.

Stage A replay and Stage B diagnostic authorization are separate decisions. A commit replay returns `EXISTING` only when both the committed candidate and its current, unexpired occurrence are present and valid. A missing candidate returns `CANDIDATE_MISSING`, even if an occurrence remains; missing or expired occurrences cannot be treated as a successful replay. Stage B additionally requires the exact initially published SCORED event binding and current source/Device authority. A SCORED binding alone does not authorize a new Stage A commit or Device scoring.

## Capacity and query budget

These limits serve different purposes and are not interchangeable:

| Limit | Value | Meaning |
| --- | ---: | --- |
| Policy available window | 30 days | Required complete pre-event interval; not a row capacity |
| Persisted Stage A device/customer history | 10,000 / 25,000 rows | Ledger bounds used while creating immutable evidence |
| Persisted evidence observations | 25,000 | Evidence representation bound |
| Private reader verification cap | 1,000 members per queried representation | Every observation, occurrence and candidate set must fit and match exactly; 1,001 returns `UNAVAILABLE` |
| Reader validation budget | 5 seconds | Soft/logical fail-closed checks between operations; not a hard end-to-end request deadline |
| Mongo client timeout | At most 5 seconds | Per-client-operation ceiling; it does not bound the sum of sequential source and Mongo operations |

Stage A reads the device/customer observation union with one indexed `$or` query, then reads anchors, occurrences and committed candidates with the exact membership filters. It sorts customer-matched observation rows in Java and does not request a MongoDB sort. The four actual Stage A query plans on 2026-11-13 were:

| Stage A query | 100 members | 500 members |
| --- | --- | --- |
| Observation device/customer union | `observation_device_window`; 100 documents / 100 keys | `observation_customer_window`, `observation_device_window`; 500 documents / 1,000 keys |
| Sealed anchors | `history_member_device_window`; 100 documents / 100 keys | `history_member_customer_window`, `history_member_device_window`; 500 documents / 1,000 keys |
| Occurrences | `occurrence_device`; 100 documents / 100 keys | `occurrence_customer_window`, `occurrence_device`; 500 documents / 1,000 keys |
| Committed candidates | `candidate_status_event_source_event`; 100 documents / 100 keys | `candidate_customer_event_status_sequence`, `candidate_device_window`; 500 documents / 1,000 keys |

Each plan returned exactly the profile's member count and had no in-memory `SORT` or `COLLSCAN`. Stage A checks sealed anchors with the `history_member_*_window` indexes. The private reader queries the device/customer union across observations, occurrences and all matching candidates; `MAX + 1` detects overflow before interpreting a truncated set. Startup remains read-only for an existing schema; it does not create or repair indexes and rejects a schema missing the anchor collection, strict validator or required indexes. The opt-in Testcontainers benchmark explains Stage A's four actual query shapes and Stage B observations/occurrences/all matching candidates separately. It reports index names, rows returned, keys and documents examined, in-memory sort, and collection scan. Stage B intentionally includes every matching candidate so staged, quarantined, or expired candidates reach the completeness checks rather than being filtered out. Only Stage A reads the anchor collection. The benchmark command is:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2repo" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfraud.device-risk.performance=true" -pl feature-enricher-service -am "-Dtest=PrivateDeviceDiagnosticReaderIntegrationTest#benchmarksAuthenticatedPrivateDiagnosticAtOneHundredFiveHundredAndOneThousandMembers" test
```

Local authenticated Testcontainers measurement on 2026-11-13: Windows 11, Java 21.0.10, 12 available processors, Docker Desktop 29.5.2 (15,619 MB reported by Docker), MongoDB 7.0 and apache/kafka:3.8.0. Each size had two warm-ups and 20 measured evaluations at concurrency 1 and 2. The benchmark-only ledger writer used a 30-second client timeout to seed fixtures; the measured private reader retained its 5-second timeout. Mongo reads below are command counts per evaluation, not documents returned. The benchmark asserts zero UNAVAILABLE results, p95 below 1,000 ms for every tested size/concurrency, and at most eight commands per source collection per evaluation.

| History members | Concurrency | p50 (ms) | p95 (ms) | p99 (ms) | Min / max (ms) | UNAVAILABLE / timeout |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 119.930 | 140.562 | 156.326 | 111.666 / 156.326 | 0/20 (0%) |
| 100 | 2 | 138.569 | 150.136 | 150.141 | 119.397 / 150.141 | 0/20 (0%) |
| 500 | 1 | 218.371 | 232.213 | 236.984 | 206.583 / 236.984 | 0/20 (0%) |
| 500 | 2 | 368.758 | 440.096 | 440.126 | 303.188 / 440.126 | 0/20 (0%) |
| 1,000 | 1 | 424.333 | 567.246 | 574.322 | 374.423 / 574.322 | 0/20 (0%) |
| 1,000 | 2 | 520.392 | 867.731 | 890.497 | 478.908 / 890.497 | 0/20 (0%) |

Average Mongo read commands and authorization revalidations per evaluation:

| History members | Concurrency | Device reads: observations / occurrences / candidates / anchors | Source reads each: receipts / RAW / ENRICHED / identities | Scored occurrence reads | Source / coverage grant revalidations |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 2.0 / 4.0 / 4.0 / 0.0 | 8.0 / 8.0 / 8.0 / 8.0 | 2.0 | 200.0 / 200.0 |
| 100 | 2 | 2.0 / 4.0 / 4.0 / 0.0 | 8.0 / 8.0 / 8.0 / 8.0 | 2.0 | 200.0 / 200.0 |
| 500 | 1 | 2.0 / 4.0 / 4.0 / 0.0 | 8.0 / 8.0 / 8.0 / 8.0 | 2.0 | 1,000.0 / 1,000.0 |
| 500 | 2 | 2.0 / 4.0 / 4.0 / 0.0 | 8.0 / 8.0 / 8.0 / 8.0 | 2.0 | 1,000.0 / 1,000.0 |
| 1,000 | 1 | 2.0 / 4.0 / 4.0 / 0.0 | 8.0 / 8.0 / 8.0 / 8.0 | 2.0 | 2,000.0 / 2,000.0 |
| 1,000 | 2 | 2.0 / 4.0 / 4.0 / 0.0 | 8.0 / 8.0 / 8.0 / 8.0 | 2.0 | 2,000.0 / 2,000.0 |

The 2026-11-13 Stage B profile used indexed observation, occurrence and candidate reads and issued zero anchor reads by design. The candidate filter has since been aligned with the production membership predicate so staged, quarantined and expired rows are not filtered out before completeness checks; its explain uses the same shared filter and expects the customer/device window indexes. The earlier candidate-plan details from the 100/500/1,000 timing run are historical and are not claimed as fresh execution statistics for this corrected filter. No Stage B performance profile was rerun after that query-shape correction.

The earlier unbatched run on this same local fixture had p95 above five seconds at 500 and 1,000 members, with every evaluation at those sizes returning UNAVAILABLE. The reader now resolves each history's exact ENRICHED, RAW, receipt and identity bindings with bounded indexed reads in a fresh source snapshot on each of the two verification passes. It still checks every member's receipt policy, lineage binding, scope, grant and epoch, and does not reuse authorization between passes. Historical candidates receive their MAC, state, epoch and expiry checks once per pass. HMAC and SHA-256 digest/UTF-8 state is reused only within the calling thread, preserving each protocol's existing length-prefixed representation and strict pseudonym-input validation. In this latest run, 500-member p95 was 232.213 ms at concurrency 1 and 440.096 ms at concurrency 2; 1,000-member p95 was 567.246 ms and 867.731 ms respectively. All sizes remained below the benchmark's 1,000 ms threshold with zero UNAVAILABLE results. Other JVM workloads were active on the local workstation during the run, so the concurrency-2 figures are noisy. These are local test-fixture measurements, not production capacity or SLO evidence; the configured 1,000-member cap remains unchanged.

The result exposes only the private bounded policy interpretation and fixed outcome. Its string representation, the fact reference, grant and proof classes redact content. The reader emits no logs, metrics, traces, API payloads or Kafka records.

## Mongo roles

The test proves three separate identities against authenticated Mongo:

| Principal | Access |
| --- | --- |
| `scored_writer` | Existing source read path and scored outbox find/insert/update required to prepare, claim and confirm publication |
| `source_diagnostic_reader` | Database `listCollections`; `find` on `source_admission_identities`, `source_admission_receipts`, `source_lineage_raw`, `source_lineage_enriched`, and `source_scored_occurrences`; `listIndexes` on `source_lineage_raw`, `source_lineage_enriched`, `source_lineage_raw_counts`, `source_lineage_enriched_counts`, `source_scored_occurrences`, and `source_scored_occurrence_counts`; no other effective privilege |
| `device_reader` | Database `listCollections`; `find` and `listIndexes` on `device_occurrences`, `device_observation_candidates`, `device_source_coverage`, `device_observations`, and `device_operational_revocation_tombstones`; no writes, source access, anchors, fences, or schema operations |

Both private readers check the authenticated principal and the complete allowed privilege set at construction. They reject extra actions, databases or collections. The code path does not construct a reader with an owner or writer client.

## Test evidence

`PrivateDeviceDiagnosticReaderIntegrationTest` uses disposable Ed25519 source, Device and coverage signers, the real receipt owner and RAW/ENRICHED chain from `VerifiedDeviceAdmissionIntegrationTest`, an authenticated Mongo replica set, the actual Mongo scored-occurrence store, and a real Kafka broker. It sends a version-2 `TransactionScoredEvent`, waits for the broker acknowledgement, confirms the exact outbox claim, consumes the Kafka bytes, and derives the read reference from that consumed event. The values are test fixtures; this is not production issuer, population or key custody evidence.

The integration tests verify:

- complete pre-event history yields novelty and switching interpretations while the other three signals stay unsupported; score remains null and the existing SCORED bytes and EI omission contract remain unchanged;
- a scored event published while Stage A is still staged returns PENDING; a later initial score can bind, but a rescore, wrong fingerprint/event ID, wrong customer or wrong epoch cannot read or replace the binding; revoked coverage authority returns UNAVAILABLE;
- a short-coverage empty history returns UNAVAILABLE with null observed values, never a false novelty result; read-only Mongo identities cannot update Device, source or scored-occurrence rows, and writer credentials cannot construct the private reader.
- changing either side of linked history, the candidate status, or all three historical copies after Stage A invalidates subsequent reads; the Stage A membership commitment detects complete deletion, including after a reader restart;
- deleting all three event copies of a sealed prior member before Stage A for a later member is detected from the independent sealed anchor; Stage A marks supported history unavailable, and a committed Stage B diagnostic returns `UNAVAILABLE` without observed novelty values;
- operational revocation removes the sealed anchor with its observation and invalidates coverage atomically;
- controlled post-snapshot races that delete a history row, change coverage revision/generation, add an operational revocation tombstone, or revoke the source receipt all return `UNAVAILABLE` during final revalidation.

Before the Stage A history-membership commitment was added, the authenticated Mongo reproducer deleted both the prior observation and its occurrence while retaining the candidate. The reader returned `EVALUATED`, showing that matching surviving collections alone did not detect a dual deletion (**RED**). With the commitment included in the immutable Stage A version, that post-Stage-A test now returns `UNAVAILABLE` with no evaluation, including on repeated reads and after a reader restart (**GREEN**).

For the separate pre-Stage-A loss case, the authenticated Mongo reproducer first sealed and committed member A, then deleted its observation, occurrence and candidate while preserving the sealed source coverage. Against the old production path, Stage A for B used only the now-incomplete rows and Stage B returned `EVALUATED` (**RED**, reproduced before the production fix). After introducing sealed membership anchors, the same scenario returns `UNAVAILABLE` with null observed supported signals (**GREEN**). This is a regression test for loss of all three event copies before the next Stage A; it does not prove completeness against an administrator who can also erase the independent anchor.

Targeted integration command:

```powershell
mvn "-Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo" -pl feature-enricher-service -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PrivateDeviceDiagnosticReaderIntegrationTest" test
```

On 2026-11-13 the focused suite finished **BUILD SUCCESS; 11 tests, 0 failures, 0 errors, 1 skipped**; the single skip is the explicitly opt-in Stage B performance benchmark, which is run separately. The combined Stage A/Stage B/store regression batch on 2026-11-13 finished **BUILD SUCCESS; 68 tests, 0 failures, 0 errors, 2 explicitly opt-in skips**. The broader targeted regression selection on 2026-11-14 finished **BUILD SUCCESS; 236 tests, 0 failures, 0 errors, 3 opt-in skips**. The startup guard also passed in the targeted batch, so attempted production activation remains rejected.

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
