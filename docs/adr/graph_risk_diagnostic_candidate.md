# Graph risk diagnostic candidate

**Status:** disabled, test-only candidate. No production grant issuer, Spring bean, route, writer/reader registration, or scorer registry entry exists.

**Reviewed baseline:** `65a2c69964785b81135acedf35eb1b9e8b08bcbc` (2026-11-23). Its main CI, Docker Compose CI, and SBOM checks passed, but do not cover the current changes.

## Decision and trust boundary

The candidate may report only a retrospective, bounded association rooted at the exact transaction occurrence **B → scoped Device pseudonym D ← earlier occurrence A**. A positive requires independently verified signed Source, Device, coverage, and `INITIAL_RUN` evidence for both occurrences; current owner revalidation; an authorized principal and Graph grant; exact issuer, institution, source, epoch, identity version, Device key version, and time window; and an unchanged bounded Graph candidate set. `A.eventTime` must be strictly earlier than `B.eventTime`.

The candidate cannot infer fraud, customer identity, physical-device identity, global absence, low risk, or what was known at scoring time. Graph `admittedAt` and diagnostic read time establish neither scoring-time knowledge nor a shared atomic snapshot across Source, Device, Graph, and scorer stores. Any missing, stale, unavailable, denied, conflicting, revoked, expired, truncated, changed, or timed-out evidence produces a typed non-positive result with no partial witness.

`GraphRiskSignalEngine` remains package-private and outside the production registry. A positive diagnostic is only `DEGRADED` with unknown confidence; non-positive outcomes remain unavailable or typed failures. No Graph score, risk contribution, alert, or recommendation is emitted. Public Engine Intelligence remains v2 with the existing four engines and 16,384-byte limit; `graph.primary` is not admitted. Evidence DTOs contain typed scoped references and bounded times, never raw Source/Device IDs, payloads, signatures, keys, or Mongo documents. Query-plan logs redact token and index-bound values.

## Server-owned limits and query shape

The reader enforces depth ≤2, nodes ≤128, edges ≤256, history rows ≤1,000, page size ≤100, window ≤30 days, and a five-second operation deadline. A configured depth below two denies the two-hop path before owner or DAO I/O. Mongo receives `min(limit, maxPageSize)` as its cursor batch size; this does not reduce the global result bound. The reader retains the `remainingEdges + 1` sentinel and rejects incomplete or overflowed candidate sets.

The authenticated Device-neighbor query binds exact scope, edge type, key version, lifecycle/as-of, and `[fromInclusive, untilExclusive)` event-time range. Planner evidence showed the reviewed index handled up to 16 `$or` branches as a bounded merge. Larger derived hub sets are split into chunks of at most 16 under one remaining-time budget and one global result sentinel. Each query sorts by Device token/key version and occurrence time/token; the complete result is reassembled in that order. The live Mongo planner regression asserts exact bounded index ranges, the selected `graph_device_neighbors` index, no `COLLSCAN` or blocking `SORT`, and measured keys/documents against this fixture's 48 matching candidates. These fixture counts are planner bounds, not a production latency or capacity claim.

## Implementation findings and evidence

All local commands below ran on Windows 11, Eclipse Adoptium Java 21.0.10+7-LTS, Docker Desktop 29.5.2, and the repository's cached Maven dependencies. Mongo tests used authenticated MongoDB 7.0 Testcontainers; the signed owner test also used Kafka 3.8.0.

| Finding and code location | Before | Repair, regression, command | Observed result |
|---|---|---|---|
| Maven test-fixture boundary — module POMs and signed Graph fixtures | Docker-style package could not resolve `feature-enricher-service:jar:tests:0.0.1-SNAPSHOT`. | Removed unconditional test-JAR lifecycle/dependency. The signed scorer E2E uses a test-JAR dependency and attachment only in the explicit `graph-signed-e2e` profile; default test compilation excludes that one profile-bound class. | Default `-Dmaven.test.skip=true package` and `-DskipTests package` exit 0. The opt-in profile builds and runs the scorer test. Root `pom.xml` owns `maven-jar-plugin.version=3.4.2` in `pluginManagement`. |
| Runtime isolation — `FraudEngineContractRuntimeIsolationTest.existingScoringRuntimeDoesNotWireSignalEngineFoundation` | Main CI reported one isolation failure: Graph candidate source was not in the narrow exception list. | Allowlisted only `GraphRiskSignalEngine.java`; asserted no Graph/scorer runtime wiring, no `graph.primary`, and no Spring stereotype on the package-private candidate. | Focused guard 14/14; full `common-events` Maven test command exits 0. |
| Two-hop depth fence — `GraphProjectionReader.readAuthorizedOccurrenceDeviceWitnesses` | A configured `maxDepth=1` still allowed the two-hop method to read. | Check depth after principal/scope/grant decisions and before owner/DAO I/O. | 21/21 two-hop and 13/13 one-hop reader tests. |
| Fresh scorer authority — `GraphRiskEvidenceAccess.requireOccurrence`, `GraphRiskSignalEngine.evaluateScoped` | The scorer ignored the result of its second owner-authority check and could map an earlier positive DTO. | Return and map a fresh typed authority result; deny a changed positive witness. A live signed Source/Device/Kafka/Mongo Graph-to-scorer E2E evaluates before and after a real owner conflict. | Scorer Graph contracts 22/22; signed owner regression 1/1; live signed Graph-to-scorer test 1/1. No production provider or scorer wiring. |
| Dense Mongo access — `MongoGraphProjectionStore.findDeviceOccurrenceNeighbors`, `deviceNeighborFilter` | Multi-hub planner evidence did not cover the maximum root-derived hub shape. | Authenticated Mongo `explain(EXECUTION_STATS)` uses the production filter/sort/index/limit/batch/time path and chunks at 16; test asserts bounded index ranges. | MongoDB 7.0: 2 hubs returned/docs/keys = 6/6/6; 16 = 48/48/48; 127 used 8 chunks, aggregate = 48/48/48. All selected `graph_device_neighbors`, with no `COLLSCAN` or blocking `SORT`. The 127-hub case is planner-only, not a positive witness under the node ceiling. |
| Dense storage bounds — `MongoGraphProjectionStoreIntegrationTest` | Node ceiling, edge sentinel, and dense authenticated Mongo boundaries lacked a complete separation. | Authenticated Graph reader/store with an explicitly synthetic owner authority tests 126/127/255/256/257 prior rows and 256 versus 257 eligible edges. | Class 23/23; boundary outcomes fail closed with no partial witness. Signed-owner evidence is covered separately. |
| Cursor and query bounds — `GraphProjectionDao`, `GraphProjectionReader`, `MongoGraphProjectionStore` | Mongo cursor batch used the default page size instead of a stricter configured limit. | Pass the effective page ceiling through all initial and repeated snapshots; command-listener test covers `find` and `getMore` at 1/10/100. | Six repeated `find` commands honor each batch size; at size 1 all six `getMore` commands also use 1. Schema/planner class 18/18 after index-bound assertions. |
| Documentation accuracy — this ADR and the Graph boundary document | Earlier text described separate scorer/owner proofs as the final evidence and duplicated implementation history. | Corrected the existing documents and retained the current architecture boundary and reproducible local measurements. | The final branch handoff reports CI for the pushed HEAD; baseline runs are not treated as evidence for this change. |

### Executed Maven commands

The following focused and package commands completed with exit code 0. `-Dmaven.test.skip=true package` and `-DskipTests package` are the CI packaging regressions; no full Maven `verify` was run.

```powershell
# Targeted Graph reader, schema, store, and signed-owner tests
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl feature-enricher-service -am -Dtest=GraphProjectionReaderTest,GraphProjectionReaderTwoHopTest,GraphProjectionSchemaIntegrationTest,MongoGraphProjectionStoreIntegrationTest,SignedGraphTwoHopAuthorityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
# Planner-bound assertion rerun
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl feature-enricher-service -am -Dtest=GraphProjectionSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
# Scorer freshness and policy contracts
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl fraud-scoring-service -am -Dtest=GraphRiskSignalEngineTest,GraphRiskEvidenceAccessTest,GraphRiskDiagnosticPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
# Isolated runtime guard
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl common-events -am -Dtest=FraudEngineContractRuntimeIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test
# Complete common-events module tests
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl common-events -am test
# Scoring package mode without test compilation
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl fraud-scoring-service -am -Dmaven.test.skip=true package
# Enricher package mode without test compilation
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl feature-enricher-service -am -Dmaven.test.skip=true package
# Scorer package with test compilation
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl fraud-scoring-service -am -DskipTests package
```

The scored-runtime isolation regression was 14/14. The first Graph/Mongo batch was 76/76 across the five selected classes; the schema/planner class was rerun after adding the explicit index-bound assertions and passed 18/18. Scorer contracts passed 22/22. The signed owner vertical passed 1/1. Test reports are generated under the modules' ignored `target/surefire-reports` directories.

## Latest local verification

These local measurements used Windows 11, Java 21.0.10, Maven 3.9.14, Docker Desktop 29.5.2, and authenticated MongoDB 7.0.31 Testcontainers. The Mongo replica set had one member and no Docker CPU or memory quota. Measurements are local test evidence only, not an SLO or production capacity estimate.

| Finding | Implementation and test | Local result |
|---|---|---|
| Signed Graph evidence reaches the scorer | `GraphRiskSignalEngineSignedMongoE2ETest.actualScorerReadsFreshSignedGraphEvidenceAndRejectsOwnerConflictInTheSameLiveSession`; one live signed Source/Device, Kafka `INITIAL_RUN`, authenticated Mongo Graph, and scorer session. | 1/1, 0 skipped. First evaluation was a bounded retrospective positive; a real signed Source conflict in the same session made the revalidation and next evaluation non-positive. The test recorded 4 scorer reads, 2 scorer revalidations, and 28 Graph owner revalidations; unrelated principal/context and forged positive controls were denied. MongoDB 7.0 and Kafka 3.8.0. |
| Dense one-hub bounds | `MongoGraphProjectionWorkloadTest.singleHubDenseTwoHopMeasuresVerifiedAndTruncatedBounds`; synthetic in-memory owner, authenticated Mongo writer/reader, one exact scoped Device hub, 10 warmups and 100 measured reads per degree. | 100 priors verified 100/100; 500 and 1,000 priors truncated 100/100 with empty witnesses. The production query plan selected `graph_device_neighbors` and had no `COLLSCAN` or blocking `SORT`. Detailed data is below. |
| Five-second owner read cutoff | `PrivateDeviceDiagnosticReaderIntegrationTest.graphOwnerMongoStallsAtDeviceSourceAndInitialScoreStagesRespectOneDeadline`; authenticated Mongo `failCommand` stalls Device, Source, and initial-score reads for 8 seconds. | 1/1, 0 skipped. Caller returned `DEADLINE_EXCEEDED` after 5,004 / 5,002 / 5,010 ms, with 1 / 5 / 15 Mongo read commands. The owner worker pool is capped at 8, uses a synchronous no-queue handoff, and drained after failpoint release. A synchronous Mongo call may continue occupying a bounded worker until the driver/server releases it; cancellation does not prove immediate server-side cancellation. |

Dense one-hub workload details (100 samples per row; command counts and returned documents cover those 100 reads; explain counts are per query):

| Eligible priors / hub degree | Edges / nodes | Outcome | p50 / p95 / p99 ms | `find` / `getMore` / returned docs | Explain docs / keys / rows | Max docs per read |
|---:|---:|---|---:|---:|---:|---:|
| 100 | 101 / 102 | `VERIFIED_BOUNDED`, 100/100 | 31.151 / 36.340 / 39.234 | 600 / 300 / 30,300 | 100 / 100 / 100 | 303 |
| 500 | 501 / 502 | `TRUNCATED_UNVERIFIED`, 0/100 positive | 15.794 / 19.751 / 20.661 | 200 / 200 / 25,700 | 257 / 257 / 257 | 257 |
| 1,000 | 1,001 / 1,002 | `TRUNCATED_UNVERIFIED`, 0/100 positive | 15.667 / 21.561 / 24.578 | 200 / 200 / 25,700 | 257 / 257 / 257 | 257 |

The dense test command was:

```text
mvn -B -o "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=MongoGraphProjectionWorkloadTest#singleHubDenseTwoHopMeasuresVerifiedAndTruncatedBounds" "-Dgraph.twoHop.denseBenchmark=true" "-Dbenchmark.maven.version=3.9.14" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

The stall regression command was:

```text
mvn -B -o "-Dmaven.repo.local=$PWD\.m2repo" -pl feature-enricher-service -am "-Dtest=PrivateDeviceDiagnosticReaderIntegrationTest#graphOwnerMongoStallsAtDeviceSourceAndInitialScoreStagesRespectOneDeadline" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

The signed scorer E2E is a test source in the existing scorer module. Its shared signed fixture is packaged as a test JAR only by the explicit `graph-signed-e2e` profile; the default Maven reactor has no new module or unconditional test-JAR dependency. No production Graph API, bean, issuer, route, consumer, registry entry, score, or Engine Intelligence v2 change is introduced.

```text
# Live signed E2E (one test, passed; no full reactor verify)
mvn -B -o "-Dmaven.repo.local=$PWD\.m2repo" -Pgraph-signed-e2e -pl fraud-scoring-service -am "-Dtest=GraphRiskSignalEngineSignedMongoE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" verify
# Default production package without test compilation
mvn -B -o "-Dmaven.repo.local=$PWD\.m2repo" -pl fraud-scoring-service,feature-enricher-service -am "-Dmaven.test.skip=true" package
# Default scorer package including test compilation
mvn -B -o "-Dmaven.repo.local=$PWD\.m2repo" -pl fraud-scoring-service -am -DskipTests package
```

## Activation gates

Keep this candidate disabled. Any future activation requires a production-owned grant and independently authorized live Graph read adapter, a live exact-context Graph-to-scorer integration test that proves fresh conflict/revocation behavior, reviewed latency and resource budgets under representative high-degree data, least-privilege runtime roles, and a successful CI run for the exact implementation commit. None of those production activation claims is established by this ADR.
