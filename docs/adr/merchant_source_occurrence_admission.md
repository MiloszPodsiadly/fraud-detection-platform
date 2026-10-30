# Merchant Source and Occurrence Admission

Status: accepted boundary for the disabled Merchant Risk foundation.

## Decision

Merchant history stays unavailable until a domain-owned verifier can admit both an authenticated institutional source scope and the exact occurrence being scored. The current ingest request supplies `sourceSystem`, merchant identity, and transaction time as caller input. It provides no authenticated issuer/institution binding or monotonic source sequence. The raw-to-enriched mapper creates a new event ID, and `ScoringContext` carries neither verified source provenance nor a scoring occurrence. The alert service's scoring occurrence is created after scoring and cannot serve as the pre-scoring source proof.

The internal admission interfaces remain default-deny. Test authorities may grant exact fixtures only; equality with caller claims is not admission. Governance signing identities are purpose-scoped and are not merchant-source authority. No production verifier, registration, or event contract is introduced by this decision.

## Typed Facts and Identity Scope

Internal `MerchantProfile` v2 carries versioned `MerchantProfileFacts` v1. Its typed values include same-currency window volume, time-effective MCC classification, source-scoped distinct-customer counts, mature resolved feedback counts, a versioned covered-population denominator, and an effective-dated institution merchant mapping. Fact provenance and opaque commitments are claims; a reader must still independently admit the exact profile and provenance. Current projections carry no values: amount and customer linkage remain client-asserted, category risk lacks a trusted taxonomy, feedback lacks merchant binding and a maturity policy, population coverage is absent, and cross-source identity mapping is absent. These facts are not added to the Mongo observation ledger or Kafka/EI v2 contracts.

Observations remain scoped by issuer, institution, source, source epoch, merchant reference, and identity version. Equal raw merchant IDs or equal source pseudonyms never combine histories. Cross-source aggregation requires a separately authorized, versioned, effective-dated institution mapping. Key rotation starts a new identity scope; “first observed” means absent from the complete explicit recent window for that scoped identity, never “first ever.”

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

## Next Integration Owner

The source-admission/integration branch must establish domain-separated source authentication and key lifecycle, bind issuer and institution to the authenticated principal, provide a monotonic source sequence, and preserve an exact payload-to-scoring-occurrence binding. It must also define revocation, completeness, and retention policy before any production admission path exists.

## Non-claims

This ADR does not establish source authenticity, institutional authority, source liveness, retention approval, or production readiness. Merchant history remains unavailable on the current transaction path. The internal store's prior lifetime cap would be reached after `C / λ` seconds for cap `C` and arrival rate `λ`; its code ceiling is 100,000 observations, but the repository declares no production event rate or store capacity. Kafka's three partitions and consumer concurrency of one do not provide that rate, so an exact production exhaustion time cannot be calculated from current configuration.
