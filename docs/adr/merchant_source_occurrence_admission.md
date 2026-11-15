# Merchant Source and Occurrence Admission

Status: accepted boundary for the disabled Merchant Risk foundation.

## Decision

Merchant history stays unavailable until a domain-owned verifier admits the institutional merchant mapping, coverage and exact occurrence. Optional attested ingest verifies the source signature over the original transaction bytes and privately links them to an exact scored fact; ordinary ingest remains unverified. This source proof supplies no monotonic sequence, merchant identity mapping, coverage seal, population denominator or mature labels. The post-scoring fact cannot authorize the current transaction's pre-scoring history read.

The internal admission interfaces remain default-deny. Test authorities may grant exact fixtures only; equality with caller claims is not admission. Governance signing identities are purpose-scoped and are not merchant-source authority. No production verifier, registration, or event contract is introduced by this decision.

## Typed Facts and Identity Scope

Internal `MerchantProfile` v3 carries versioned `MerchantProfileFacts` v2. Its typed values include same-currency window volume, time-effective MCC classification, source-scoped distinct-customer counts, mature resolved feedback counts, a versioned covered-population denominator, and an effective-dated institution merchant mapping. Label and population claims share a structural `MerchantPopulationIdentity`: either the exact source observation cohort bound to the profile history commitment, or an institution-mapped cohort bound to the current source identity, mapping version, source-set commitment, window, as-of time, and population-definition version. The mapped version must cover the full cohort window. Local-cohort labels and eligible rows cannot exceed the profile's recent count. A broader mapped cohort may exceed that count only when both claims carry the same identity and a matching mapping claim; the profile does not admit that claim or authorize cross-source use. Opaque commitments and `AVAILABLE` fact status are not signatures or authority.

Only mature `CONFIRMED_FRAUD` and `CONFIRMED_LEGITIMATE` outcomes enter the resolved-label counts; `INCONCLUSIVE`, `NEEDS_MORE_INFO`, and any unknown outcome stay excluded and are not counted as legitimate. The feedback store's unique transaction ID constraint is a storage guarantee, not proof that a future aggregate is deduplicated. Aggregate facts do not carry transaction IDs, so duplicate classification must be established by an independently admitted aggregation source. An incomplete or untrusted denominator is not zero; a zero eligible denominator cannot support a fraud-rate basis. The current transaction path still has no merchant-bound feedback, maturity policy, population coverage, or cross-source mapping, and carries no values. These facts are not added to the Mongo observation ledger or Kafka/EI v2 contracts.

The temporal contract is inclusive at equality: `windowEnd <= matureThrough <= populationIdentity.asOf <= fact.provenance.observedAt <= profile.provenance.evaluatedAt` for mature labels, and `windowEnd <= populationIdentity.asOf <= observedAt <= evaluatedAt` for population denominators. `matureThrough` is the maturity cutoff of included resolved labels; `asOf` bounds the represented cohort; `observedAt` says when that aggregate became available; `evaluatedAt` is the profile's knowledge cutoff. A structurally matching cohort does not authorize its source or make future evidence available early.

Observations remain scoped by issuer, institution, source, source epoch, merchant reference, and identity version. Equal raw merchant IDs or equal source pseudonyms never combine histories. Cross-source aggregation requires a separately authorized, versioned, effective-dated institution mapping. Key rotation starts a new identity scope; “first observed” means absent from the complete explicit recent window for that scoped identity, never “first ever.”

The pre-FDP-154 clean Mongo ledger used internal `schemaVersion=1` and transactionally materialized a per-merchant membership checkpoint chain from each contiguous sealed source batch. FDP-154 evolves that internal schema to v2 for signed proof anchors and v3 for retained-history commitments and the scored binding; these numbers are not public event or API versions. Each checkpoint binds the scope, merchant, event-time occurrence, source sequence, input commitment, and same-merchant predecessor; commits and reads compare the bounded half-open history window and current occurrence with that chain. Missing or mismatched membership makes the current read unavailable. Prefix compaction removes checkpoints only with their expired receipts, preserving sequence and replay fences. Event document IDs use a purpose-tagged, length-delimited SHA-256 key over all five scope fields plus `sourceEvent`; candidate, manifest, and membership uniqueness is enforced inside the exact `(issuer, institution, source, epoch, identityVersion)` scope. Earlier experimental FDP-150 layouts and the prior v1/v2 Merchant layouts are rejected at startup by exact collection, validator, and index checks; there is no in-place migration. For disposable local/test state, point the test configuration at a new uniquely named empty database or recreate its isolated Testcontainers instance; do not reset shared or production databases. These checkpoints establish internal consistency for explicitly admitted test fixtures; their commitments do not authenticate a production source.

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

The ordinary and earlier hot reference runs on 2026-10-31 used `mongo:7.0` / MongoDB 7.0.31, Docker Desktop with one replica-set member, Windows 11, Java 21.0.10, 12 processors, JVM max heap 8,392,802,304 bytes, and no Docker CPU or memory cap reported. These are diagnostic local measurements; they do not establish HA, production throughput, or an SLO.

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

The ordinary and earlier hot 1k reference runs passed on 2026-10-31. The 10k and 25k ordinary scales are `NOT VERIFIED` under the agreed targeted-run budget; reproduce with the same command, changing `merchant.store.workload.scale` to `10000` or `25000`:

```powershell
mvn -q "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=MongoMerchantObservationStoreWorkloadTest#boundedWorkloadReportsLatencyPlansConflictsAndResourceEnvelope" "-Dmerchant.store.workload=true" "-Dmerchant.store.workload.scale=10000" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=MongoMerchantObservationStoreWorkloadTest#boundedWorkloadReportsLatencyPlansConflictsAndResourceEnvelope" "-Dmerchant.store.workload=true" "-Dmerchant.store.workload.scale=25000" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

The exact 1k hot-mode invocation was:

```powershell
mvn -q "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=MongoMerchantObservationStoreWorkloadTest#hotSourceHotMerchantReportsContendedWritesReplayAndCompaction" "-Dmerchant.store.workload=true" "-Dmerchant.store.workload.hot=true" "-Dmerchant.store.workload.hot.rows=1000" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### FDP-150 latest hot workload review (2026-11-01)

At 21:51 Europe/Warsaw, the bounded-seal hot 1k mode passed on the environment stated above. This rerun supersedes the 2026-10-31 hot timing/conflict values in the historical table; the ordinary 1k values remain the 2026-10-31 reference and were not rerun alongside it. Additional state assertions for the seed and final seal were added after the measurement; they were statically reviewed but not rerun under the requested test scope.

| Hot 1k operation | Samples | p50 / p95 / p99 (ms) |
|---|---:|---:|
| Concurrent stage | 900 | 26.695 / 97.789 / 136.933 |
| Concurrent read | 200 | 31.307 / 52.316 / 75.288 |
| Commit | 902 | 100.819 / 160.993 / 178.575 |
| Exact stage replay | 1,000 | 7.008 / 9.539 / 11.305 |
| Exact commit replay | 32 | 89.658 / 156.681 / 159.603 |
| Window read at sequence 1 / 100 / 1,000 (10 each) | 30 total | p50 7.782 / 23.069 / 162.266; p95=p99 9.947 / 29.321 / 185.483 |
| Prefix seal batch (one sample) / exact seal replay (one sample) | 1 / 1 | 1,025.957 / 4.778 |

The fixture uses one source scope, one merchant, two writers, two readers with 100 reads each, and 1,000 observations. The four indexed plans (`merchant_history_window`, `merchant_candidate_window`, `merchant_manifest_window`, `merchant_membership_window`) each examined and returned 999 documents with 999 keys examined. Mongo reported 60,799 started commands, 1,258 failed commands (all retryable), 629 write conflicts, and 0 duplicate-key conflicts. There were 170 bounded `STAGE_UNRESOLVED` outcomes, all recovered by exact replay; three unknown commit acknowledgments were injected during final-seal contention, with zero unresolved seals. Input JSON p50 was 1,339 bytes and peak sampled used heap was 465,003,896 bytes. The prefix-seal latency is one sample and is descriptive only.

The 1k bounded-read p50 grows from 7.782 ms at sequence 1 to 162.266 ms at sequence 1,000; hot commit p95 is 160.993 ms. Static inspection shows each commit reads and compares the bounded event-time ranges in history, committed candidates, manifest receipts, and membership. In this hot fixture all observations fall within the one-hour window. If all prior rows remain in-window, cumulative range-document comparisons are inferred to grow as `4 x sum(0..N-1)`, approximately `2N(N-1)`, subject to the 10,000-row history limit. This is an inference from the query shape; no 5k or 10k performance result was observed.

| Workload mode | Status |
|---|---|
| Ordinary 1k | Passed on 2026-10-31 (reference values above) |
| Ordinary 10k / 25k | `NOT VERIFIED` |
| Hot 1k | Passed on 2026-11-01 |
| Hot 5k | `NOT VERIFIED` |
| Hot 10k | `NOT VERIFIED` |

The exact hot 1k invocation is shown above. For hot 5k and 10k, use the same command with `-Dmerchant.store.workload.hot.rows=5000` or `10000`. These scales and sustainable hot-merchant throughput remain runtime pre-activation capacity gates. No optimization or production throughput claim is made from the unverified modes.

## Next Integration Owner

Integration still requires versioned, effective-dated institution merchant mapping, authenticated source sequence and coverage seals, and independently admitted denominators and mature deduplicated labels for the same population. MCC taxonomy, activity/concentration methods and any risk score require validation and calibration. Retention and target-load capacity remain approval gates.

## FDP-154 P01 proposal - independently reviewed

This is the implementation proposal for the FDP-154 kickoff scope, not production authorization. The branch will build the dedicated merchant-feed/population contract and prove it with an explicitly authorized test issuer; it will not wait for or claim integration with a real institutional source. The separately named final-scope file and canonical master plan were not present in the workspace and were not opened. P01 makes no runtime or test changes.

### Authority, population, and sequence

`SourceSignatureVerifier` authenticates the original source payload under its source scope, purpose, audience, key validity, and revocation policy. Merchant ownership is a separate assertion: a Merchant observation grant must bind issuer, institution, source, source epoch, population policy, identity version, and allowed merchant pseudonym mapping. Coverage uses a distinct purpose and independently authorized role/grant; a source key or matching `merchantId`, MCC, source-system string, or hash is not sufficient. One institutional issuer may hold both roles only through distinct public-key commitments/key material, distinct role grants, and distinct purposes; a key reused under another key-version label is not independent. Test issuers remain test-only.

The proposed sequence is **source-wide within a dedicated merchant-observation feed**, across all merchants in that feed; it is not a per-merchant counter and is not presumed to be the upstream transaction stream's global sequence. One sequence position represents one occurrence included by the signed population definition. Under the current store, every scope/feed begins at sequence 1 in a new source epoch; a different origin requires a reviewed store/schema change. The feed and population ID/definition version must be signed and pinned to one existing source scope (issuer/institution/source/epoch/identityVersion); they cannot change within that scope. A new feed or population definition therefore requires a new epoch or identityVersion and a fresh sequence beginning at 1. The coverage signer attests the definition/version, inclusion and exclusion rules, source scope/epoch, origin (1) and through-value, generation and previous commitment, coverage start, and watermark. The platform must verify signed authority, contiguous feed positions, exact event membership, and source lineage; it cannot prove that the issuer's defined cohort represents every real-world event. Excluded upstream events must not consume this feed's sequence. The branch implements these semantics as a signed, testable contract with separate observation and coverage key material; production onboarding remains out of scope and is not implied.

This interpretation matches `MongoMerchantObservationSchema`: candidate, manifest, and membership sequence uniqueness is scoped by issuer/institution/source/epoch/identityVersion without merchant; `MongoMerchantObservationStore.seal` checks the full scope sequence batch and its manifest before advancing coverage. Only after that source-wide fence is proven may bounded history be filtered by merchant. Existing identity fences remain issuer/institution/source/epoch/merchant/identityVersion; mapping versions are signed and effective-dated, and do not merge identities across scopes.

### Stage A, Stage B, retention, and private read

Stage A verifies the source's original signed bytes and re-reads RAW/ENRICHED evidence through `SourceLineageAccess`/`MongoSourceLineage`; caller fields do not establish lineage. A separately verified Merchant observation and coverage capability may then pass through `MerchantObservationStore` admission into `MongoMerchantObservationStore.stage -> seal -> commit`. Commit records only the immutable pre-event window `[from, asOf)`; equal-time observations do not count one another. No SCORED fact is needed for this stage.

Source-proof trust boundary: at Stage A, verify the source signature over the original payload and use `MongoSourceLineage` as the authoritative durable receipt. On a later read, resolve that exact receipt and RAW/ENRICHED commitments again and check current receipt policy. `MongoSourceLineage` does not retain another copy of source events and does not reverify the original signature bytes on a later read; this design trusts the existing source-persistence boundary for that prior verification. Do not copy raw payment payloads into the Merchant ledger. If the independent reviewer finds that source-persistence boundary insufficient, P03/P05 must fail closed until a source-owned proof revalidation API is added; a stored hash alone is not a replacement.

Stage B starts only after publication. `SourceOccurrenceAccess.find` with an authorized `SourceLineage` and exact `Reference(eventId, createdAt, fingerprint)` must return the persisted `SourceScoredOccurrence` with `runId == MongoSourceOccurrenceStore.INITIAL_RUN`. A single append-once binding in the existing manifest records source receipt/scope, merchant identity version, signed coverage anchor, pre-event snapshot/history commitments, and exact scored event/time/fingerprint/run. The v3 history-window commitment covers the retained proof and membership anchors used by the immutable Stage A snapshot. A later published rescore, guessed ID, or caller-supplied event cannot bind the snapshot. Replaying the exact bind is idempotent; a different binding is rejected.

Keep the existing six Merchant collections and extend their strict schema rather than adding a ledger. P03 advanced the clean v1 ledger to v2 for durable signed Merchant evidence; P04 advances the combined FDP-154 layout to v3 for the history commitment and append-once scored binding. Strict validators reject unknown shapes. Store bounded original signed Merchant assertion bytes (never raw payment payloads or keys), signer/grant/policy references, source receipt plus RAW/ENRICHED commitments, the pinned feed/population ID and definition version, signed coverage assertion/chaining fields including origin 1, and the scored binding in the existing manifest/coverage documents. Retain each proof anchor for at least as long as any history or replay fence that depends on it. Existing v1/v2 or partial databases must fail startup unchanged; a fresh isolated namespace or separately reviewed explicit migration is required. No automatic drop, repair, or in-place migration is allowed.

Place a package-private `PrivateMerchantDiagnosticReader` in `feature-enricher-service/.../merchant`, behind a separately configured read-only Mongo principal and purpose-bound read grant. Each read rechecks current Merchant grants/revocation, retained source evidence and bounded feed/history, and the persisted initial SCORED occurrence; perform a final authority/occurrence check after cross-store reads to fence TOCTOU. Any missing, expired, altered, incomplete, revoked, or conflicting anchor returns denial/`UNAVAILABLE`/`PENDING`, never a zero or risk score. Keep the admission flag fail-closed; do not register `MerchantRiskSignalEngine` or `MerchantProfileAccess`, change scoring, public APIs, Kafka, EI v2, or `TransactionScoredEvent` v2.

### Implementation and test map after approval

| Prompt | Smallest planned code and evidence |
|---|---|
| P02 | Add package-private `MerchantObservationAssertion`, `MerchantCoverageAssertion`, bounded canonical `MerchantAssertionCodec`, and `MerchantAssertionVerifier`/role policy in `feature-enricher-service/.../merchant`; reuse `SourceSignatureVerifier`. Add `MerchantAssertionVerifierTest` for original-byte tampering, shared-key/wrong-role, wrong scope, revocation, malformed/duplicate fields, and bounds. |
| P03 | Add package-private `VerifiedMerchantAdmission`; bridge opaque P02 capabilities through `MerchantObservationStore.AdmissionVerifier`; extend `MongoMerchantObservationStore`, `MerchantObservationDocumentCodec`, and `MongoMerchantObservationSchema` to v2 without adding collections. `MerchantVerifiedSourceAdmissionEndToEndIntegrationTest` covers a mixed-merchant contiguous feed from sequence 1, gaps, tamper, replay, and restart. |
| P04 | Add `MerchantScoredBinding` and append-once `bindScored`; persist it in the existing manifest and resolve through `SourceOccurrenceAccess`/`MongoSourceOccurrenceStore`. Require `runId == MongoSourceOccurrenceStore.INITIAL_RUN`; advance the combined strict schema to v3 with retained-history commitment. `MerchantVerifiedSourceAdmissionEndToEndIntegrationTest` covers exact initial bind, pre-publication absence, non-initial run, wrong references, unrelated receipt, conflict, concurrent exact replay, and restart. The focused fixture persists the source-store acknowledgement; P07 exercises Kafka publication. |
| P05 | Add package-private `PrivateMerchantDiagnosticReader`; validate a purpose/scope-bound grant and the exact read-only Mongo principal at construction, then recheck Merchant evidence, source lineage, and the initial SCORED occurrence around cross-store reads. `MerchantVerifiedSourceAdmissionEndToEndIntegrationTest` exercises supported claims and redaction, history loss, mid-read revocation/coverage mutation/key expiry/source-receipt deletion, missing or wrong binding/reference, grant-scope mismatch, read-only privilege enforcement, error scrubbing, and deadline handling. It remains unregistered from Spring scoring and public endpoints; production grant delivery is not configured by this private branch vertical. |
| P06 | Adversarial pass retained the existing assertion, Mongo race/restart/TTL/ACK suite and added two reader regressions: deleting the source receipt after the first snapshot cannot produce a claim, and an internal read failure is redacted as `UNAVAILABLE`. The combined end-to-end class also covers revocation, evidence mutation, exact binding, read-only credentials, and bounded reads. |
| P07 | `MerchantVerifiedSourceAdmissionEndToEndIntegrationTest` exercises signed source/observation/distinct coverage, the six-collection Mongo ledger, consumed Kafka SCORED bytes, persisted initial occurrence, append-once binding, and private read; a wrong fingerprint must remain unevaluated. Verify public scoring/EI/event contracts remain unchanged. Run applicable tests and state final-HEAD CI accurately; update a concise operational note only if deployment setup needs it. |

### P01 handoff

Before P02, the independent architecture/security reviewer must approve: (1) the branch-local test issuer and distinct observation/coverage keys and grants; (2) the dedicated feed contract, one sequence origin of 1 per source epoch, and signed cohort/exclusion semantics; (3) pinning one feed/population to each source scope, with a new epoch or identityVersion for changes; (4) the existing source-persistence receipt as the durable source trust boundary, or a clear NO-GO if that boundary is insufficient; (5) population/mapping-version semantics and evidence retention; (6) the v1-to-v2 namespace/migration plan; and (7) private reader placement and least-privilege Mongo principal. The FDP-153 baseline also has a reported `Regulated Mutation Regression Gate` Testcontainers startup failure; it remains an unresolved CI result and must be separated from FDP-154 findings and cleared at the final gate.

**Independent review outcome: GO for P02.** The reviewer accepted the explicit source-persistence trust boundary after Stage A original-byte verification, with current policy/commitment rechecks and fail-closed handling for lost, altered, expired, or revoked anchors. The dedicated feed/population contract is implemented and tested with test-only authority in this branch; no production-source trust is claimed. No runtime code or tests were changed in P01.

## Non-claims

This ADR does not establish source authenticity, institutional authority, source liveness, retention approval, or production readiness. Merchant history remains unavailable on the current transaction path. The internal store's prior lifetime cap would be reached after `C / λ` seconds for cap `C` and arrival rate `λ`; its code ceiling is 100,000 observations, but the repository declares no production event rate or store capacity. Kafka's three partitions and consumer concurrency of one do not provide that rate, so an exact production exhaustion time cannot be calculated from current configuration.
