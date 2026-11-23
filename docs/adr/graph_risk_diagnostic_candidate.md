# Graph risk diagnostic candidate

**Status:** disabled, test-only candidate. No production grant issuer, Spring bean, route, writer/reader registration, or scorer registry entry exists.

**Evidence commit:** `1c320ec452a43ef0bcf7b2167168d0cf3c9be9b5` (`FDP-156`, 2026-11-23).

**Reviewed failing baseline:** `c539f806cbdee704abc0e15cd3a534abb3b5715b`.

**CI:** no exact-head GitHub Actions result is recorded here. The reviewed baseline failures are [main CI run 45180140980](https://github.com/MiloszPodsiadly/fraud-detection-platform/actions/runs/45180140980) and [Docker Compose CI run 45180141395](https://github.com/MiloszPodsiadly/fraud-detection-platform/actions/runs/45180141395); neither covers the evidence commit.

## Decision and trust boundary

The candidate may report only a retrospective, bounded association rooted at the exact transaction occurrence **B → scoped Device pseudonym D ← earlier occurrence A**. A positive requires independently verified signed Source, Device, coverage, and `INITIAL_RUN` evidence for both occurrences; current owner revalidation; an authorized principal and Graph grant; exact issuer, institution, source, epoch, identity version, Device key version, and time window; and an unchanged bounded Graph candidate set. `A.eventTime` must be strictly earlier than `B.eventTime`.

The candidate cannot infer fraud, customer identity, physical-device identity, global absence, low risk, or what was known at scoring time. Graph `admittedAt` and diagnostic read time establish neither scoring-time knowledge nor a shared atomic snapshot across Source, Device, Graph, and scorer stores. Any missing, stale, unavailable, denied, conflicting, revoked, expired, truncated, changed, or timed-out evidence produces a typed non-positive result with no partial witness.

`GraphRiskSignalEngine` remains package-private and outside the production registry. A positive diagnostic is only `DEGRADED` with unknown confidence; non-positive outcomes remain unavailable or typed failures. No Graph score, risk contribution, alert, or recommendation is emitted. Public Engine Intelligence remains v2 with the existing four engines and 16,384-byte limit; `graph.primary` is not admitted. Evidence DTOs contain typed scoped references and bounded times, never raw Source/Device IDs, payloads, signatures, keys, or Mongo documents. Query-plan logs redact token and index-bound values.

## Server-owned limits and query shape

The reader enforces depth ≤2, nodes ≤128, edges ≤256, history rows ≤1,000, page size ≤100, window ≤30 days, and a five-second operation deadline. A configured depth below two denies the two-hop path before owner or DAO I/O. Mongo receives `min(limit, maxPageSize)` as its cursor batch size; this does not reduce the global result bound. The reader retains the `remainingEdges + 1` sentinel and rejects incomplete or overflowed candidate sets.

The authenticated Device-neighbor query binds exact scope, edge type, key version, lifecycle/as-of, and `[fromInclusive, untilExclusive)` event-time range. Planner evidence showed the reviewed index handled up to 16 `$or` branches as a bounded merge. Larger derived hub sets are split into chunks of at most 16 under one remaining-time budget and one global result sentinel. Each query sorts by Device token/key version and occurrence time/token; the complete result is reassembled in that order. The live Mongo planner regression asserts exact bounded index ranges, the selected `graph_device_neighbors` index, no `COLLSCAN` or blocking `SORT`, and measured keys/documents against this fixture's 48 matching candidates. These fixture counts are planner bounds, not a production latency or capacity claim.

## FDP-156 finding closure

All local commands below ran on Windows 11, Eclipse Adoptium Java 21.0.10+7-LTS, Docker Desktop 29.5.2, and the repository's cached Maven dependencies. Mongo tests used authenticated MongoDB 7.0 Testcontainers; the signed owner test also used Kafka 3.8.0.

| Finding and code location | Before | Repair, regression, command | Observed result |
|---|---|---|---|
| FX-01 — `feature-enricher-service/pom.xml`, `fraud-scoring-service/pom.xml`, `SignedGraphABTestFixture`, `SignedGraphTwoHopAuthorityIntegrationTest` | Docker-style package could not resolve `feature-enricher-service:jar:tests:0.0.1-SNAPSHOT`. | Removed the test-JAR execution/dependency; retained signed owner evidence in its owning module. `C1`, `C6`–`C8`. | Both `-Dmaven.test.skip=true package` reactors and scoring `-DskipTests package` exit 0. Signed owner test 1/1. Root `pom.xml` already owns `maven-jar-plugin.version=3.4.2` in `pluginManagement`. |
| FX-02 — `FraudEngineContractRuntimeIsolationTest.existingScoringRuntimeDoesNotWireSignalEngineFoundation` | Main CI reported 458 common-events tests with one isolation failure: Graph candidate source was not in the narrow exception list. | Allowlisted only `GraphRiskSignalEngine.java`; asserted no Graph/scorer runtime wiring, no `graph.primary`, and no Spring stereotype on the package-private candidate. `C4`, `C5`. | Focused guard 14/14; full `common-events` Maven test command exits 0. |
| FX-03 — `GraphProjectionReader.readAuthorizedOccurrenceDeviceWitnesses` | A configured `maxDepth=1` still allowed the two-hop method to read. | Check depth after principal/scope/grant decisions and before owner/DAO I/O. `GraphProjectionReaderTwoHopTest`, `GraphProjectionReaderTest`; `C1`. | 21/21 two-hop and 13/13 one-hop reader tests. |
| FX-04 — `GraphRiskEvidenceAccess.requireOccurrence`, `GraphRiskSignalEngine.evaluateScoped` | The scorer ignored the result of its second owner-authority check and could map an earlier positive DTO. | Return and map a fresh typed authority result; deny a changed positive witness. `GraphRiskSignalEngineTest.freshConflictBetweenInitialReadAndOccurrenceBindingInvalidatesThePositive`; signed owner test `SignedGraphTwoHopAuthorityIntegrationTest`; `C1`, `C3`. | Scorer Graph contracts 22/22. Signed Graph owner read before/after independently signed conflict 1/1. Separate proofs by design; no live Graph-to-scorer E2E/provider. |
| FX-05 — `MongoGraphProjectionStore.findDeviceOccurrenceNeighbors`, `deviceNeighborFilter` | Multi-hub planner evidence did not cover the maximum root-derived hub shape. | Authenticated Mongo `explain(EXECUTION_STATS)` uses the production filter/sort/index/limit/batch/time path and chunks at 16; test asserts bounded index ranges. `GraphProjectionSchemaIntegrationTest.authenticatedProductionQueryPlansStayBoundedForTwoSixteenAnd127RootHubs`; `C1`, `C2`. | MongoDB 7.0: 2 hubs returned/docs/keys = 6/6/6; 16 = 48/48/48; 127 used 8 chunks (seven × 16, one × 15), aggregate = 48/48/48. All selected `graph_device_neighbors`, with no `COLLSCAN` or blocking `SORT`. The 127-hub case is planner-only, not a positive witness under the node ceiling. |
| FX-06 — `MongoGraphProjectionStoreIntegrationTest` | Node ceiling, edge sentinel, and dense authenticated Mongo boundaries lacked a complete separation. | Real authenticated Graph reader/store with explicitly synthetic owner authority tests 126/127/255/256/257 prior rows and 256 versus 257 eligible edges; `C1`. | Class 23/23; boundary outcomes fail closed with no partial witness. Signed-owner evidence remains the separate test above. |
| FX-07 — `GraphProjectionDao`, `GraphProjectionReader`, `MongoGraphProjectionStore` | Mongo cursor batch used the default page size instead of a stricter configured limit. | Pass the effective page ceiling through all initial and repeated snapshots; command-listener test covers `find` and `getMore` at 1/10/100. `GraphProjectionSchemaIntegrationTest.effectiveBatchSizesReachEveryAuthenticatedRepeatedSnapshotWithoutCappingResults`; `C1`, `C2`. | Six repeated `find` commands honor each batch size; at size 1 all six `getMore` commands also use 1. Schema/planner class 18/18 after index-bound assertions. |
| FX-08 — this ADR, `graph_evidence_admission_matrix.md`, `graph_evidence_projection_boundary.md` | Existing documentation falsely described a single scorer/owner E2E and test-JAR sharing; no scoped closure record reconciled the findings. | Added this canonical record and corrected both existing documents. Local evidence is linked to the implementation commit. | Documentation updated. Exact-head CI remains unobserved; no CI success is inferred from local results. |

### Executed Maven commands

The following focused and package commands completed with exit code 0. `-Dmaven.test.skip=true package` and `-DskipTests package` are the CI packaging regressions; no full Maven `verify` was run.

```powershell
# C1 — targeted Graph reader, schema, store, and signed-owner tests
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl feature-enricher-service -am -Dtest=GraphProjectionReaderTest,GraphProjectionReaderTwoHopTest,GraphProjectionSchemaIntegrationTest,MongoGraphProjectionStoreIntegrationTest,SignedGraphTwoHopAuthorityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
# C2 — planner-bound assertion rerun
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl feature-enricher-service -am -Dtest=GraphProjectionSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
# C3 — scorer freshness and policy contracts
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl fraud-scoring-service -am -Dtest=GraphRiskSignalEngineTest,GraphRiskEvidenceAccessTest,GraphRiskDiagnosticPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
# C4 — isolated runtime guard
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl common-events -am -Dtest=FraudEngineContractRuntimeIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test
# C5 — complete common-events module tests
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl common-events -am test
# C6 — scoring CI package mode
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl fraud-scoring-service -am -Dmaven.test.skip=true package
# C7 — enricher CI package mode
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl feature-enricher-service -am -Dmaven.test.skip=true package
# C8 — scorer package with test compilation
mvn.cmd --% -Dmaven.repo.local=C:\Users\mpods\IdeaProjects\fraud-detection-platform\.m2repo -B -ntp -q -pl fraud-scoring-service -am -DskipTests package
```

The scored-runtime isolation regression was 14/14. The first Graph/Mongo batch was 76/76 across the five selected classes; the schema/planner class was rerun after adding the explicit index-bound assertions and passed 18/18. Scorer contracts passed 22/22. The signed owner vertical passed 1/1. Test reports are generated under the modules' ignored `target/surefire-reports` directories.

## Activation gates

Keep this candidate disabled. Any future activation requires a production-owned grant and independently authorized live Graph read adapter, a live exact-context Graph-to-scorer integration test that proves fresh conflict/revocation behavior, reviewed latency and resource budgets under representative high-degree data, least-privilege runtime roles, and a successful CI run for the exact implementation commit. None of those production activation claims is established by this ADR.
