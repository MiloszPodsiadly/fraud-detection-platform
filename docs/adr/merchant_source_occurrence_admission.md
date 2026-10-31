# Merchant Source and Occurrence Admission

Status: accepted boundary for the disabled Merchant Risk foundation.

## Decision

Merchant history stays unavailable until a domain-owned verifier can admit both an authenticated institutional source scope and the exact occurrence being scored. The current ingest request supplies `sourceSystem`, merchant identity, and transaction time as caller input. It provides no authenticated issuer/institution binding or monotonic source sequence. The raw-to-enriched mapper creates a new event ID, and `ScoringContext` carries neither verified source provenance nor a scoring occurrence. The alert service's scoring occurrence is created after scoring and cannot serve as the pre-scoring source proof.

The internal admission interfaces remain default-deny. Test authorities may grant exact fixtures only; equality with caller claims is not admission. Governance signing identities are purpose-scoped and are not merchant-source authority. No production verifier, registration, or event contract is introduced by this decision.

## Typed Facts and Identity Scope

Internal `MerchantProfile` v3 carries versioned `MerchantProfileFacts` v2. Its typed values include same-currency window volume, time-effective MCC classification, source-scoped distinct-customer counts, mature resolved feedback counts, a versioned covered-population denominator, and an effective-dated institution merchant mapping. Label and population claims share a structural `MerchantPopulationIdentity`: either the exact source observation cohort bound to the profile history commitment, or an institution-mapped cohort bound to the current source identity, mapping version, source-set commitment, window, as-of time, and population-definition version. The mapped version must cover the full cohort window. Local-cohort labels and eligible rows cannot exceed the profile's recent count. A broader mapped cohort may exceed that count only when both claims carry the same identity and a matching mapping claim; the profile does not admit that claim or authorize cross-source use. Opaque commitments and `AVAILABLE` fact status are not signatures or authority.

Only mature `CONFIRMED_FRAUD` and `CONFIRMED_LEGITIMATE` outcomes enter the resolved-label counts; `INCONCLUSIVE`, `NEEDS_MORE_INFO`, and any unknown outcome stay excluded and are not counted as legitimate. The feedback store's unique transaction ID constraint is a storage guarantee, not proof that a future aggregate is deduplicated. Aggregate facts do not carry transaction IDs, so duplicate classification must be established by an independently admitted aggregation source. An incomplete or untrusted denominator is not zero; a zero eligible denominator cannot support a fraud-rate basis. The current transaction path still has no merchant-bound feedback, maturity policy, population coverage, or cross-source mapping, and carries no values. These facts are not added to the Mongo observation ledger or Kafka/EI v2 contracts.

Observations remain scoped by issuer, institution, source, source epoch, merchant reference, and identity version. Equal raw merchant IDs or equal source pseudonyms never combine histories. Cross-source aggregation requires a separately authorized, versioned, effective-dated institution mapping. Key rotation starts a new identity scope; “first observed” means absent from the complete explicit recent window for that scoped identity, never “first ever.”

Mongo schema v3 transactionally materializes a per-merchant membership checkpoint chain from each contiguous sealed source batch. Each checkpoint binds the scope, merchant, event-time occurrence, source sequence, input commitment, and same-merchant predecessor; commits and reads compare the bounded half-open history window and current occurrence with that chain. Missing or mismatched membership makes the current read unavailable. Prefix compaction removes checkpoints only with their expired receipts, preserving sequence and replay fences. Existing v2 databases are rejected at startup; local/test databases must be reset explicitly because no in-place migration is provided. These checkpoints establish internal consistency for explicitly admitted test fixtures; their commitments do not authenticate a production source.

The five signals keep separate prerequisites:

- Fraud rate needs mature confirmed-fraud and confirmed-legitimate labels plus a complete, defined merchant population.
- Activity spike needs complete comparable windows, same-currency volume, and a validated statistic and threshold.
- Category risk needs a trusted, time-effective MCC taxonomy and a validated category method.
- Merchant concentration needs an explicit population and merchant-wide customer linkage; it is distinct from Rules’ customer-by-merchant frequency.
- First observed is a bounded-window observation for the current source-scoped identity only.

No probability or risk score is represented without calibration.

## Verification Matrix

| Risk | Executable evidence |
| --- | --- |
| Issuer, institution, source, epoch, identity, and merchant isolation | `MongoMerchantObservationStoreIntegrationTest` and `MerchantProfileProjectorTest` cover forged bindings, collisions, rotation, exact replay, and scope-separated history. |
| Occurrence access, revocation, and real event contracts | `MerchantSourceOccurrenceVerticalContractTest` uses shared raw/enriched event types; `MerchantRiskSignalEngineTest` covers occurrence grants, revocation, forged reuse, and non-informative outcomes. Its authority grants are explicitly test-only. |
| Ordering, timestamp ties, sequence gaps, malformed boundaries, and conflicting replay | `MerchantProfileProjectorTest` and `MongoMerchantObservationStoreIntegrationTest` cover half-open windows, equal-time inputs, late arrivals, predecessor ordering, sequence continuity, and bounded-input rejection. |
| Lost, corrupt, expired, or partially withdrawn evidence; TTL lag and rotation | `MongoMerchantObservationStoreIntegrationTest` covers receipts, history manifests, read-time expiry, prefix compaction, missing rows, narrowed coverage, and replay floors. |
| Startup schema/index mismatch, capacity, races, timeout, unknown outcome, and restart | `MongoMerchantObservationStoreIntegrationTest` exercises fail-closed startup, bounded capacity, concurrent stage/commit/seal/read, cancellation budgets, acknowledgment recovery, and restart; `MerchantRiskSignalEngineTest` bounds non-cooperating adapters. |
| No pre-cutover effect on score or public contracts | `MerchantRiskEngineIsolationTest`, `FraudScoringOrchestratorNoDecisioningTest`, `ProducerEngineIntelligenceDisabledOutputContractTest`, `TransactionScoredEventCompatibilityTest`, `TransactionScoredEventConsumerInventorySourceScanGuardTest`, and the alert-service deserialization/listener compatibility tests protect engine registration, decision isolation, event shape, and consumers. |

These deterministic checks use shared event and engine contracts where the runtime path has them. Mongo and engine authority fixtures grant only explicit test rows and remain under `src/test`; they do not establish production authenticity, source liveness, or verified merchant evidence.

The separate `MongoMerchantObservationStoreWorkloadTest` is opt-in with `-Dmerchant.store.workload=true`; select `1000`, `10000`, or `25000` via `-Dmerchant.store.workload.scale=<scale>`. For example:

```text
mvn -pl feature-enricher-service -Dtest=MongoMerchantObservationStoreWorkloadTest -Dmerchant.store.workload=true -Dmerchant.store.workload.scale=1000 test
```

It uses four source scopes, 32 merchant pseudonyms, concurrent ingestion across scopes, per-scope ordered commits, concurrent snapshot reads, and one MongoDB replica-set member from `mongo:7.0`. Output records the resolved Mongo version/image, operating system, Java version, processor count, JVM heap cap, Docker memory/CPU limits, p50/p95/p99 ingest/commit/read latency, indexed explain counts, retryable command failures, write conflicts, heap samples, and input-document size distribution. Retryable failures are command-level retry signals, not a count of internal transaction-loop attempts. Docker's default resource limits apply unless the daemon reports explicit container limits; the output records those values. This is host-local diagnostic data, not a production SLO, availability result, or 30k events/s claim.

The prior implementation had a scope-wide membership count on event processing; the current path uses exact identity lookups and bounded, indexed sequence-prefix compaction. A numeric before/after benchmark is unavailable because the old implementation was not retained as a runnable baseline. The comparison is limited to the query shape and work bound visible in code.

### FDP-150 local workload evidence

Runs on 2026-10-31 used `mongo:7.0` / MongoDB 7.0.31, Docker Desktop with one replica-set member, Windows 11, Java 21.0.10, 12 processors, JVM max heap 8,392,802,304 bytes, and no Docker CPU or memory cap reported. These are diagnostic local measurements; they do not establish HA, production throughput, or an SLO.

| Mode and cardinality | Operation (samples) | p50 / p95 / p99 |
|---|---|---|
| Ordinary: 1,000 rows, 4 scopes, 32 merchants | stage/ingest (1,000) | 24.622 / 41.576 / 56.740 ms |
| | commit (1,000) | 20.777 / 27.855 / 31.280 ms |
| | snapshot read (1,000) | 13.638 / 20.290 / 85.944 ms |
| | seal (4) | 622.835 / 622.925 / 622.925 ms |
| Hot: 1,000 rows, 1 scope, 1 merchant (100% share), 2 writers, 2 readers × 100 reads | concurrent stage (900) | 23.883 / 84.702 / 110.552 ms |
| | concurrent read (200) | 26.963 / 34.950 / 36.221 ms |
| | commit (902) | 101.796 / 172.210 / 189.519 ms |
| | exact stage replay (1,000) / exact commit replay (32) | 7.001 / 9.158 / 11.105 ms; 96.269 / 167.772 / 178.055 ms |
| | bounded read at sequence 1 / 100 / 1,000 (10 each; prior rows 0 / 99 / 999) | p50 10.328 / 22.199 / 133.284 ms; p95=p99 18.703 / 30.064 / 151.444 ms |

The ordinary run examined and returned exactly 7 documents per indexed collection/scope (16 plans across history, candidate, manifest, and membership); it recorded 0 failed/retryable Mongo commands, 0 write conflicts, and 0 duplicate-key conflicts. The hot run examined and returned exactly 999 documents per indexed collection. It recorded 1,198 failed commands (all retryable), 599 write conflicts, and 0 duplicate-key conflicts; bounded contention produced 166 `STAGE_UNRESOLVED` results, all recovered by exact replay, and one unresolved seal was recovered by exact seal replay. The hot test verified 1,000 exact stage replays, 32 exact commit replays, rejection of a source sequence gap without advancing the fence, and quarantine of a post-watermark event. Correlated E1 history/candidate/manifest loss remained unavailable with unknown prior count after duplicate seal. A separate 100-row compaction scope compacted through sequence 100, removed all membership checkpoints, and rejected stale replay; its single trigger-stage sample was 833.697 ms. Median input JSON sizes were 1,461 bytes (ordinary) and 1,469 bytes (hot); peak sampled used heap was 196,292,880 bytes (ordinary) and 388,227,248 bytes (hot), against the reported JVM cap.

Hot writers share one source coverage document and contend on its revision inside Mongo transactions; seals advance only across the contiguous source-sequence fence. Transaction retries are capped at three attempts. The observed conflicts and unresolved acknowledgments are therefore properties of this deliberately serialized single-source path, with exact replay providing recovery.

The ordinary 1k run passed. The hot 1k run passed. The 10k and 25k ordinary scales are `NOT VERIFIED` under the agreed targeted-run budget; reproduce with the same command, changing `merchant.store.workload.scale` to `10000` or `25000`:

```powershell
mvn -q "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=MongoMerchantObservationStoreWorkloadTest#boundedWorkloadReportsLatencyPlansConflictsAndResourceEnvelope" "-Dmerchant.store.workload=true" "-Dmerchant.store.workload.scale=10000" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=MongoMerchantObservationStoreWorkloadTest#boundedWorkloadReportsLatencyPlansConflictsAndResourceEnvelope" "-Dmerchant.store.workload=true" "-Dmerchant.store.workload.scale=25000" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

The exact 1k hot-mode invocation was:

```powershell
mvn -q "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=MongoMerchantObservationStoreWorkloadTest#hotSourceHotMerchantReportsContendedWritesReplayAndCompaction" "-Dmerchant.store.workload=true" "-Dmerchant.store.workload.hot=true" "-Dmerchant.store.workload.hot.rows=1000" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## Next Integration Owner

The source-admission/integration branch must establish domain-separated source authentication and key lifecycle, bind issuer and institution to the authenticated principal, provide a monotonic source sequence, and preserve an exact payload-to-scoring-occurrence binding. It must also define revocation, completeness, and retention policy before any production admission path exists.

## Non-claims

This ADR does not establish source authenticity, institutional authority, source liveness, retention approval, or production readiness. Merchant history remains unavailable on the current transaction path. The internal store's prior lifetime cap would be reached after `C / λ` seconds for cap `C` and arrival rate `λ`; its code ceiling is 100,000 observations, but the repository declares no production event rate or store capacity. Kafka's three partitions and consumer concurrency of one do not provide that rate, so an exact production exhaustion time cannot be calculated from current configuration.
