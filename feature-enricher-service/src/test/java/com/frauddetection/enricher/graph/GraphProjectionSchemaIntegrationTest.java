package com.frauddetection.enricher.graph;

import com.mongodb.MongoCommandException;
import com.mongodb.ExplainVerbosity;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphProjectionSchemaIntegrationTest {
    private static final Instant EVENT = Instant.parse("2026-11-18T10:00:00.000Z");
    private static final GraphProjectionMongoFixture MONGO = new GraphProjectionMongoFixture();
    private static MongoClient root;
    private static int databaseSequence;

    @BeforeAll
    static void startMongo() throws Exception {
        MONGO.start();
        root = MONGO.root();
    }

    @AfterAll
    static void stopMongo() {
        MONGO.close();
    }

    @Test
    void emptyDatabaseBootstrapsOnlyAsSchemaOwnerAndRuntimeAdaptersVerifyReadOnlyContract() {
        Context context = context();

        GraphProjectionSchema.bootstrapEmptyDatabase(context.schemaOwnerDatabase());
        assertThatCode(() -> new MongoGraphProjectionStore(context.writerDatabase(), GraphProjectionSchema.WRITER))
                .doesNotThrowAnyException();
        assertThatCode(() -> new MongoGraphProjectionStore(context.readerDatabase(), GraphProjectionSchema.READER))
                .doesNotThrowAnyException();
        assertThat(context.graphDatabase().listCollectionNames()).containsExactly(GraphProjectionSchema.COLLECTION);
        assertThat(indexNames(context.graphDatabase())).containsExactlyInAnyOrder(
                "_id_", "graph_logical_relation_unique", "graph_device_neighbors", "graph_occurrence_neighbors");
    }

    @Test
    void adapterStartupUsesTheSameBoundedMetadataCommandsForEmptyAndThousandRowCollections() {
        Context context = bootstrappedContext();
        StartupCommandProbe commands = new StartupCommandProbe();
        MongoClient monitoredReader = MONGO.monitoredReader(context.databaseName(), commands);

        new MongoGraphProjectionStore(monitoredReader.getDatabase(context.databaseName()), GraphProjectionSchema.READER);
        int emptyDatabaseCommands = commands.schemaCommands();
        assertThat(commands.findCommands()).isZero();
        assertThat(emptyDatabaseCommands).isEqualTo(4);

        List<Document> rows = new ArrayList<>(1_000);
        GraphIdentityScope rowScope = scope("startup", "bounded");
        for (int index = 0; index < 1_000; index++) {
            rows.add(GraphProjectionDocumentCodec.encode(stored(rowScope, "startup-event-" + index,
                    "startup-proof-" + index)));
        }
        context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).insertMany(rows);
        assertThat(context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).countDocuments()).isEqualTo(1_000);

        commands.reset();
        new MongoGraphProjectionStore(monitoredReader.getDatabase(context.databaseName()), GraphProjectionSchema.READER);

        assertThat(commands.findCommands()).isZero();
        assertThat(commands.schemaCommands()).isEqualTo(emptyDatabaseCommands).isEqualTo(4);
    }

    @Test
    void rawGraphPersistenceTypesRemainPackagePrivateAndUnwired() {
        for (Class<?> internalType : List.of(GraphProjectionStore.class, GraphProjectionDao.class,
                MongoGraphProjectionStore.class, GraphProjectionReader.class, GraphStoredEdge.class)) {
            assertThat(java.lang.reflect.Modifier.isPublic(internalType.getModifiers()))
                    .as("%s stays unavailable outside the Graph package", internalType.getSimpleName()).isFalse();
            assertThat(internalType.getAnnotations()).as("%s is not registered as a runtime component",
                    internalType.getSimpleName()).isEmpty();
            assertThat(java.util.Arrays.stream(internalType.getDeclaredMethods())
                    .flatMap(method -> java.util.Arrays.stream(method.getAnnotations())).toList())
                    .as("%s methods have no framework entry-point annotations", internalType.getSimpleName()).isEmpty();
        }
    }

    @Test
    void correctlyNamedReaderWithAdditionalWriteRoleFailsStartup() {
        Context context = bootstrappedContext();
        context.graphDatabase().runCommand(new Document("grantRolesToUser", GraphProjectionSchema.READER)
                .append("roles", List.of(new Document("role", "readWrite").append("db", context.databaseName()))));

        assertSchemaMismatch(() -> new MongoGraphProjectionStore(context.readerDatabase(), GraphProjectionSchema.READER));
    }

    @Test
    void correctlyNamedWriterWithDeleteAndDdlCapabilitiesFailsStartup() {
        Context context = bootstrappedContext();
        context.graphDatabase().runCommand(new Document("grantRolesToUser", GraphProjectionSchema.WRITER)
                .append("roles", List.of(new Document("role", "readWrite").append("db", context.databaseName()))));

        assertSchemaMismatch(() -> new MongoGraphProjectionStore(context.writerDatabase(), GraphProjectionSchema.WRITER));
    }

    @Test
    void correctlyNamedSchemaOwnerWithDatabaseOwnerCapabilitiesFailsVerification() {
        Context context = bootstrappedContext();
        context.graphDatabase().runCommand(new Document("grantRolesToUser", GraphProjectionSchema.SCHEMA_OWNER)
                .append("roles", List.of(new Document("role", "dbOwner").append("db", context.databaseName()))));

        assertSchemaMismatch(() -> GraphProjectionSchema.verify(context.schemaOwnerDatabase(), GraphProjectionSchema.SCHEMA_OWNER));
    }

    @Test
    void readerAuthenticatedAgainstTheWrongDatabaseCannotVerify() {
        Context context = bootstrappedContext();
        MongoClient wrongAuthenticationDatabase = MONGO.readerWithWrongAuthenticationDatabase(context.databaseName());

        assertThatThrownBy(() -> GraphProjectionSchema.verify(
                wrongAuthenticationDatabase.getDatabase(context.databaseName()), GraphProjectionSchema.READER))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void strictValidatorAcceptsV2AndRejectsUnknownRootNestedAndVersionFields() {
        Context context = bootstrappedContext();
        MongoCollection<Document> collection = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        Document valid = GraphProjectionDocumentCodec.encode(stored(scope("institution-a", "epoch-a"), "event-a", "proof-a"));

        collection.insertOne(valid);
        assertThat(collection.countDocuments()).isEqualTo(1);

        Document withUnknownRoot = copy(valid).append("unexpected", true);
        assertValidationRejected(collection, withUnknownRoot);

        Document withUnknownEvidence = copy(valid);
        withUnknownEvidence.get("evidence", Document.class).append("unreviewedProof", "x");
        assertValidationRejected(collection, withUnknownEvidence);

        Document unknownVersion = copy(valid).append("schemaVersion", GraphProjectionSchema.SCHEMA_VERSION + 1);
        assertValidationRejected(collection, unknownVersion);
        assertThat(collection.countDocuments()).isEqualTo(1);
    }

    @Test
    void compoundUniqueFenceRejectsConflictingProofAndAllowsOtherTenantOrEpoch() {
        Context context = bootstrappedContext();
        MongoCollection<Document> collection = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        GraphIdentityScope firstScope = scope("institution-a", "epoch-a");
        GraphStoredEdge firstProof = stored(firstScope, "same-event", "proof-one");
        GraphStoredEdge conflictingProof = stored(firstScope, "same-event", "proof-two");

        collection.insertOne(GraphProjectionDocumentCodec.encode(firstProof));
        assertThat(firstProof.edge().canonicalId()).isNotEqualTo(conflictingProof.edge().canonicalId());
        assertDuplicateKey(collection, GraphProjectionDocumentCodec.encode(conflictingProof));

        collection.insertOne(GraphProjectionDocumentCodec.encode(stored(scope("institution-b", "epoch-a"), "same-event", "proof-one")));
        collection.insertOne(GraphProjectionDocumentCodec.encode(stored(scope("institution-a", "epoch-b"), "same-event", "proof-one")));
        assertThat(collection.countDocuments()).isEqualTo(3);
    }

    @Test
    void explainUsesReviewedIndexesForLogicalRelationAndDeviceNeighborhoodQueries() {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("institution-a", "epoch-a");
        GraphStoredEdge stored = stored(scope, "event-a", "proof-a");
        GraphStoredEdge secondOccurrence = stored(scope, "event-b", "proof-b");
        MongoCollection<Document> collection = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        collection.insertOne(GraphProjectionDocumentCodec.encode(stored));
        collection.insertOne(GraphProjectionDocumentCodec.encode(secondOccurrence));

        Document logical = collection.find(logicalFilter(stored)).limit(1).explain(ExplainVerbosity.QUERY_PLANNER);
        GraphNodeRef deviceHub = GraphNodeRef.device(scope,
                stored.edge().evidence().associatedIdentityToken(), stored.edge().evidence().pseudonymKeyVersion());
        Instant asOf = EVENT.plusSeconds(10);
        Instant fromInclusive = EVENT.minus(Duration.ofDays(30));
        Instant untilExclusive = EVENT.plusSeconds(1);
        List<GraphNodeRef> queryHubs = List.of(deviceHub,
                GraphNodeRef.device(scope, token("device:another"), 8));
        Document deviceNeighborsFilter = MongoGraphProjectionStore.deviceNeighborFilter(scope, queryHubs,
                fromInclusive, untilExclusive, asOf);
        Document neighbors = collection.find(deviceNeighborsFilter).sort(MongoGraphProjectionStore.deviceNeighborSort())
                .hintString("graph_device_neighbors")
                .limit(GraphQueryLimits.DEFAULT.maxEdges() + 1).batchSize(GraphQueryLimits.DEFAULT.maxPageSize())
                .explain(ExplainVerbosity.QUERY_PLANNER);
        Document occurrenceNeighbors = collection.find(occurrenceFilter(stored)).sort(new Document("_id", 1))
                .limit(GraphQueryLimits.DEFAULT.maxEdges() + 1).explain(ExplainVerbosity.QUERY_PLANNER);

        assertThat(indexNamesInPlan(logical)).contains("graph_logical_relation_unique");
        assertThat(indexNamesInPlan(neighbors)).contains("graph_device_neighbors");
        assertThat(indexNamesInPlan(occurrenceNeighbors)).contains("graph_occurrence_neighbors");

        GraphProjectionDao store = new MongoGraphProjectionStore(context.readerDatabase(), GraphProjectionSchema.READER);
        assertThat(store.findLogicalRelation(stored.edge())).contains(stored);
        assertThat(store.findNeighbors(GraphNodeRef.device(scope, stored.edge().evidence().associatedIdentityToken(), 7), 32))
                .containsExactlyInAnyOrder(stored, secondOccurrence);
        assertThat(store.findDeviceOccurrenceNeighbors(scope, queryHubs, fromInclusive, untilExclusive,
                asOf, 32, GraphQueryLimits.DEFAULT.maxPageSize(), Duration.ofSeconds(2)))
                .containsExactlyInAnyOrder(stored, secondOccurrence);
        DeviceNeighborBatchProbe batchProbe = new DeviceNeighborBatchProbe();
        try (MongoClient monitoredReader = MONGO.monitoredReader(context.databaseName(), batchProbe)) {
            GraphProjectionDao monitoredStore = new MongoGraphProjectionStore(
                    monitoredReader.getDatabase(context.databaseName()), GraphProjectionSchema.READER);
            monitoredStore.findDeviceOccurrenceNeighbors(scope, queryHubs, fromInclusive, untilExclusive,
                    asOf, GraphQueryLimits.DEFAULT.maxEdges() + 1, GraphQueryLimits.DEFAULT.maxPageSize(),
                    Duration.ofSeconds(2));
        }
        assertThat(batchProbe.batchSizes).containsExactly(GraphQueryLimits.DEFAULT.maxPageSize());
        assertThat(store.findDeviceOccurrenceNeighbors(scope,
                List.of(GraphNodeRef.device(scope, stored.edge().evidence().associatedIdentityToken(), 8)),
                fromInclusive, untilExclusive, asOf, 32, GraphQueryLimits.DEFAULT.maxPageSize(),
                Duration.ofSeconds(2))).isEmpty();
        GraphIdentityScope otherScope = scope("institution-b", "epoch-a");
        assertThat(store.findDeviceOccurrenceNeighbors(otherScope,
                List.of(GraphNodeRef.device(otherScope, stored.edge().evidence().associatedIdentityToken(), 7)),
                fromInclusive, untilExclusive, asOf, 32, GraphQueryLimits.DEFAULT.maxPageSize(),
                Duration.ofSeconds(2))).isEmpty();
        MongoGraphProjectionStore writer = new MongoGraphProjectionStore(context.writerDatabase(), GraphProjectionSchema.WRITER);
        assertThatThrownBy(() -> writer.findDeviceOccurrenceNeighbors(scope, List.of(deviceHub), fromInclusive,
                untilExclusive, asOf, 32, GraphQueryLimits.DEFAULT.maxPageSize(), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GRAPH_QUERY_PRINCIPAL_INVALID");
        assertThatThrownBy(() -> store.findDeviceOccurrenceNeighbors(scope, List.of(deviceHub), fromInclusive,
                untilExclusive, asOf, GraphQueryLimits.DEFAULT.maxEdges() + 2,
                GraphQueryLimits.DEFAULT.maxPageSize(), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("GRAPH_QUERY_LIMIT_INVALID");
        assertThatThrownBy(() -> store.findDeviceOccurrenceNeighbors(scope, List.of(deviceHub), fromInclusive,
                untilExclusive, asOf, 32, 0, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("GRAPH_QUERY_LIMIT_INVALID");
        assertThatThrownBy(() -> store.findDeviceOccurrenceNeighbors(scope, List.of(deviceHub), fromInclusive,
                untilExclusive, asOf, 32, GraphQueryLimits.DEFAULT.maxPageSize() + 1, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("GRAPH_QUERY_LIMIT_INVALID");
        assertThatThrownBy(() -> store.findDeviceOccurrenceNeighbors(scope, List.of(deviceHub), fromInclusive,
                untilExclusive, asOf, 32, GraphQueryLimits.DEFAULT.maxPageSize(), Duration.ofSeconds(6)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("GRAPH_QUERY_LIMIT_INVALID");
        assertThat(store.findOccurrenceNeighbors(scope, stored.edge().evidence().occurrence(), EVENT.plusSeconds(10),
                GraphQueryLimits.DEFAULT.maxEdges() + 1, GraphQueryLimits.DEFAULT.maxPageSize(),
                Duration.ofSeconds(2))).containsExactly(stored);

        GraphPrivateReadGrant graphGrant = GraphPrivateReadGrant.testOnly("graph-diagnostics-test", scope, 1,
                EVENT.plusSeconds(60));
        GraphRelationAuthority owners = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                return GraphRelationResolution.verified(expected, expected.expiresAt());
            }
        };
        GraphProjectionReader reader = new GraphProjectionReader(store, owners,
                (grant, principal, exactScope, now) -> GraphPolicyDecision.AUTHORIZED,
                GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(new byte[32]),
                Clock.fixed(EVENT.plusSeconds(10), java.time.ZoneOffset.UTC));
        GraphReadOutcome firstRead = reader.readAuthorizedOccurrenceNeighbors("graph-diagnostics-test", graphGrant,
                scope, stored.edge().evidence().occurrence(), EVENT.minus(Duration.ofDays(1)), EVENT.plusSeconds(1), 10, null);
        GraphReadOutcome secondRead = reader.readAuthorizedOccurrenceNeighbors("graph-diagnostics-test", graphGrant,
                scope, secondOccurrence.edge().evidence().occurrence(), EVENT.minus(Duration.ofDays(1)),
                EVENT.plusSeconds(1), 10, null);
        assertThat(firstRead.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(firstRead.edges()).containsExactly(stored.edge());
        assertThat(secondRead.state()).isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(secondRead.edges()).containsExactly(secondOccurrence.edge());
    }

    @Test
    void effectiveBatchSizesReachEveryAuthenticatedRepeatedSnapshotWithoutCappingResults() {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("batch-size", "epoch-batch");
        GraphStoredEdge root = storedAt(scope, "batch-root", "batch-root-proof", EVENT);
        GraphStoredEdge priorOne = storedAt(scope, "batch-prior-one", "batch-prior-one-proof", EVENT.minusSeconds(60));
        GraphStoredEdge priorTwo = storedAt(scope, "batch-prior-two", "batch-prior-two-proof", EVENT.minusSeconds(120));
        context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION).insertMany(List.of(
                GraphProjectionDocumentCodec.encode(root), GraphProjectionDocumentCodec.encode(priorOne),
                GraphProjectionDocumentCodec.encode(priorTwo)));

        for (int maxPageSize : List.of(1, 10, 100)) {
            CursorBatchProbe probe = new CursorBatchProbe();
            try (MongoClient monitoredReader = MONGO.monitoredReader(context.databaseName(), probe)) {
                GraphProjectionDao store = new MongoGraphProjectionStore(
                        monitoredReader.getDatabase(context.databaseName()), GraphProjectionSchema.READER);
                GraphRelationAuthority syntheticOwner = new GraphRelationAuthority() {
                    @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                        return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
                    }

                    @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                        return GraphRelationResolution.verified(expected, expected.expiresAt());
                    }

                    @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal,
                                                                          Duration remainingBudget) {
                        return revalidate(expected, principal);
                    }
                };
                GraphQueryLimits limits = new GraphQueryLimits(2, 128, 256, Duration.ofDays(30),
                        1_000, maxPageSize, Duration.ofSeconds(5));
                GraphProjectionReader reader = new GraphProjectionReader(store, syntheticOwner,
                        (grant, principal, exactScope, now) -> grant.matches(principal, exactScope, now)
                                ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED,
                        limits, new GraphReadCursorCodec(new byte[32]), Clock.fixed(EVENT.plusSeconds(10),
                        java.time.ZoneOffset.UTC));
                GraphPrivateReadGrant grant = GraphPrivateReadGrant.testOnly("graph-diagnostics-test", scope, 1,
                        EVENT.plusSeconds(600));

                GraphDeviceWitnessOutcome outcome = reader.readAuthorizedOccurrenceDeviceWitnesses(
                        "graph-diagnostics-test", grant, scope, root.edge().evidence().occurrence());

                assertThat(outcome.state()).as("authenticated Mongo read: %s", outcome)
                        .isEqualTo(GraphProjectionState.VERIFIED_BOUNDED);
                assertThat(outcome.witness()).isPresent();
                assertThat(probe.findBatchSizes).hasSize(6).containsOnly(maxPageSize);
                assertThat(probe.findMaxTimeMillis).hasSize(6)
                        .allSatisfy(maxTime -> assertThat(maxTime).isBetween(1, 5_000));
                if (maxPageSize == 1) {
                    assertThat(probe.getMoreBatchSizes).hasSize(6).containsOnly(1);
                } else {
                    assertThat(probe.getMoreBatchSizes).isEmpty();
                }
            }
        }
    }

    @Test
    void authenticatedProductionQueryPlansStayBoundedForTwoSixteenAnd127RootHubs() {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("multi-hub-plan", "epoch-plan");
        Instant rootTime = EVENT.minusSeconds(10);
        GraphOccurrenceRef root = new GraphOccurrenceRef(scope, token("event:multi-hub-root"), rootTime);
        List<GraphStoredEdge> rootEdges = new ArrayList<>(127);
        List<String> hubs = new ArrayList<>(127);
        for (int index = 0; index < 127; index++) {
            String hub = "plan-hub-" + index;
            hubs.add(hub);
            rootEdges.add(edgeForOccurrence(scope, root, hub, "plan-root-proof-" + index));
        }
        List<GraphStoredEdge> candidates = new ArrayList<>(48);
        for (int candidate = 0; candidate < 3; candidate++) {
            Instant priorTime = rootTime.minusSeconds(candidate + 1L);
            GraphOccurrenceRef prior = new GraphOccurrenceRef(scope,
                    token("event:plan-prior-" + candidate), priorTime);
            for (int hub = 0; hub < 16; hub++) {
                candidates.add(edgeForOccurrence(scope, prior, hubs.get(hub),
                        "plan-prior-proof-" + candidate + "-" + hub));
            }
        }
        List<Document> fixture = new ArrayList<>(rootEdges.size() + candidates.size());
        java.util.stream.Stream.concat(rootEdges.stream(), candidates.stream())
                .map(GraphProjectionDocumentCodec::encode).forEach(fixture::add);
        context.writerClient().getDatabase(context.databaseName()).getCollection(GraphProjectionSchema.COLLECTION)
                .insertMany(fixture);

        GraphProjectionDao readerStore = new MongoGraphProjectionStore(context.readerDatabase(), GraphProjectionSchema.READER);
        List<GraphStoredEdge> authorizedRootRows = readerStore.findOccurrenceNeighbors(scope, root,
                EVENT.plusSeconds(10), GraphQueryLimits.DEFAULT.maxEdges() + 1,
                GraphQueryLimits.DEFAULT.maxPageSize(), GraphQueryLimits.DEFAULT.maxQueryTime());
        assertThat(authorizedRootRows).hasSize(127);
        List<GraphNodeRef> derivedHubs = hubs.stream().map(hub -> {
            String identityToken = token("device:" + hub);
            return authorizedRootRows.stream().map(row -> row.edge().to())
                    .filter(node -> node.identityToken().equals(identityToken)).findFirst().orElseThrow();
        }).toList();

        for (int hubCount : List.of(2, 16, 127)) {
            List<GraphNodeRef> selectedHubs = derivedHubs.subList(0, hubCount);
            int remaining = GraphQueryLimits.DEFAULT.maxEdges() - authorizedRootRows.size() + 1;
            int totalReturned = 0;
            int totalDocsExamined = 0;
            int totalKeysExamined = 0;
            for (List<GraphNodeRef> hubChunk : MongoGraphProjectionStore.deviceNeighborChunks(selectedHubs)) {
                Document filter = MongoGraphProjectionStore.deviceNeighborFilter(scope, hubChunk,
                        rootTime.minus(Duration.ofDays(30)), rootTime, EVENT.plusSeconds(10));
                Document plan = context.readerDatabase().getCollection(GraphProjectionSchema.COLLECTION).find(filter)
                        .sort(MongoGraphProjectionStore.deviceNeighborSort())
                        .hintString("graph_device_neighbors")
                        .limit(remaining)
                        .batchSize(Math.min(remaining, GraphQueryLimits.DEFAULT.maxPageSize()))
                        .maxTime(GraphQueryLimits.DEFAULT.maxQueryTime().toMillis(),
                                java.util.concurrent.TimeUnit.MILLISECONDS)
                        .explain(ExplainVerbosity.EXECUTION_STATS);
                Document planner = plan.get("queryPlanner", Document.class);
                Document winningPlan = planner.get("winningPlan", Document.class);
                Document executionStats = plan.get("executionStats", Document.class);
                int returned = ((Number) executionStats.get("nReturned")).intValue();
                int docsExamined = ((Number) executionStats.get("totalDocsExamined")).intValue();
                int keysExamined = ((Number) executionStats.get("totalKeysExamined")).intValue();
                Set<String> selectedIndexes = indexNamesInPlan(winningPlan);
                Set<String> selectedStages = stageNamesInPlan(winningPlan);
                System.out.printf("GRAPH_MULTI_HUB_PLAN hubs=%d chunk_hubs=%d docs_examined=%d "
                                + "keys_examined=%d returned=%d indexes=%s stages=%s winning_plan=%s execution_stats=%s%n",
                        hubCount, hubChunk.size(), docsExamined, keysExamined, returned, selectedIndexes, selectedStages,
                        redactSensitivePlanValues(winningPlan).toJson(),
                        redactSensitivePlanValues(executionStats).toJson());
                assertThat(selectedIndexes).contains("graph_device_neighbors");
                assertThat(selectedStages).doesNotContain("COLLSCAN", "SORT");
                assertThat(hubChunk).hasSizeBetween(1, 16);
                assertThat(hasBoundedDeviceIndexBounds(winningPlan))
                        .as("each IXSCAN must bind the exact scope, Device key and event-time range")
                        .isTrue();
                totalReturned += returned;
                totalDocsExamined += docsExamined;
                totalKeysExamined += keysExamined;
                remaining -= returned;
            }
            assertThat(totalReturned).isEqualTo(hubCount == 2 ? 6 : 48);
            assertThat(totalDocsExamined).isBetween(totalReturned, candidates.size());
            assertThat(totalKeysExamined).isBetween(totalReturned, candidates.size());
        }
        // The 127-hub plan is query-planner evidence only: root + 127 hubs already exhaust maxNodes=128.
    }

    @Test
    void graphLifecycleFenceLandingDuringOwnerRecheckInvalidatesTheMongoRead() {
        Context context = bootstrappedContext();
        GraphIdentityScope scope = scope("institution-race", "epoch-race");
        GraphStoredEdge stored = stored(scope, "event-race", "proof-race");
        MongoCollection<Document> collection = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        collection.insertOne(GraphProjectionDocumentCodec.encode(stored));
        GraphProjectionDao store = new MongoGraphProjectionStore(context.readerDatabase(), GraphProjectionSchema.READER);
        java.util.concurrent.atomic.AtomicInteger ownerChecks = new java.util.concurrent.atomic.AtomicInteger();
        GraphRelationAuthority ownerAuthority = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                if (ownerChecks.getAndIncrement() == 0) {
                    collection.updateOne(com.mongodb.client.model.Filters.eq("_id", stored.edge().canonicalId()),
                            com.mongodb.client.model.Updates.set("lifecycle.state", GraphEdgeLifecycle.REVOKED.name()));
                }
                return GraphRelationResolution.verified(expected, expected.expiresAt());
            }
        };
        Instant readAt = EVENT.plusSeconds(10);
        GraphPrivateReadGrant grant = GraphPrivateReadGrant.testOnly("graph-diagnostics-test", scope, 1,
                EVENT.plusSeconds(60));
        GraphProjectionReader reader = new GraphProjectionReader(store, ownerAuthority,
                (current, principal, exactScope, now) -> current.matches(principal, exactScope, now)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED,
                GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(new byte[32]),
                Clock.fixed(readAt, java.time.ZoneOffset.UTC));

        GraphReadOutcome result = reader.readAuthorizedOccurrenceNeighbors("graph-diagnostics-test", grant, scope,
                stored.edge().evidence().occurrence(), EVENT.minus(Duration.ofHours(1)), EVENT.plusSeconds(1), 10, null);

        assertThat(ownerChecks).hasValue(1);
        assertThat(result.state()).isEqualTo(GraphProjectionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(GraphReadReason.TRUNCATED_UNVERIFIED);
        assertThat(result.edges()).isEmpty();
        assertThat(GraphProjectionDocumentCodec.decode(collection.find(new Document("_id", stored.edge().canonicalId())).first())
                .lifecycle()).isEqualTo(GraphEdgeLifecycle.REVOKED);
    }

    @Test
    void runtimePrincipalsCannotChangeDdlOrWriteThroughReaderAndHaveNoOwnerDatabaseAccess() {
        Context context = bootstrappedContext();
        MongoCollection<Document> graph = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        Document valid = GraphProjectionDocumentCodec.encode(stored(scope("institution-a", "epoch-a"), "event-a", "proof-a"));

        assertUnauthorized(() -> context.writerDatabase().getCollection(GraphProjectionSchema.COLLECTION)
                .createIndex(new Document("unexpected", 1)));
        assertUnauthorized(() -> context.readerDatabase().getCollection(GraphProjectionSchema.COLLECTION).insertOne(valid));
        assertUnauthorized(() -> context.schemaOwnerDatabase().getCollection(GraphProjectionSchema.COLLECTION).insertOne(valid));
        assertUnauthorized(() -> context.writerDatabase().getCollection(GraphProjectionSchema.COLLECTION)
                .deleteOne(new Document("_id", valid.getString("_id"))));

        MongoDatabase deviceDatabase = root.getDatabase("device_service");
        MongoDatabase sourceDatabase = root.getDatabase("source_service");
        MongoDatabase merchantDatabase = root.getDatabase("merchant_service");
        deviceDatabase.getCollection("device_observations").insertOne(new Document("marker", "device"));
        sourceDatabase.getCollection("source_lineage_raw").insertOne(new Document("marker", "source"));
        merchantDatabase.getCollection("merchant_observations").insertOne(new Document("marker", "merchant"));
        assertUnauthorized(() -> context.writerClient().getDatabase("device_service")
                .getCollection("device_observations").find().first());
        assertUnauthorized(() -> context.writerClient().getDatabase("source_service")
                .getCollection("source_lineage_raw").insertOne(new Document("marker", "forbidden")));
        assertUnauthorized(() -> context.readerClient().getDatabase("device_service")
                .getCollection("device_observations").find().first());
        assertUnauthorized(() -> context.readerClient().getDatabase("source_service")
                .getCollection("source_lineage_raw").insertOne(new Document("marker", "forbidden")));
        assertUnauthorized(() -> context.writerClient().getDatabase("merchant_service")
                .getCollection("merchant_observations").find().first());
        assertUnauthorized(() -> context.readerClient().getDatabase("merchant_service")
                .getCollection("merchant_observations").find().first());
    }

    @Test
    void mismatchedStartupFailsClosedWithoutRepairingIndexesOrChangingData() {
        Context context = bootstrappedContext();
        MongoCollection<Document> graph = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        graph.insertOne(GraphProjectionDocumentCodec.encode(stored(scope("institution-a", "epoch-a"), "event-a", "proof-a")));
        graph.dropIndex("graph_device_neighbors");
        List<String> beforeNames = indexNames(context.graphDatabase());

        assertSchemaMismatch(() -> new MongoGraphProjectionStore(context.writerDatabase(), GraphProjectionSchema.WRITER));

        assertThat(indexNames(context.graphDatabase())).containsExactlyInAnyOrderElementsOf(beforeNames);
        assertThat(graph.countDocuments()).isEqualTo(1);
    }

    @Test
    void wrongIndexDefinitionFailsClosedWithoutDroppingExistingDocuments() {
        Context context = bootstrappedContext();
        MongoCollection<Document> graph = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        graph.insertOne(GraphProjectionDocumentCodec.encode(stored(scope("institution-a", "epoch-a"), "event-a", "proof-a")));
        graph.dropIndex("graph_device_neighbors");
        graph.createIndex(new Document("device.token", 1), new com.mongodb.client.model.IndexOptions()
                .name("graph_device_neighbors"));
        List<String> beforeNames = indexNames(context.graphDatabase());

        assertSchemaMismatch(() -> new MongoGraphProjectionStore(context.readerDatabase(), GraphProjectionSchema.READER));

        assertThat(indexNames(context.graphDatabase())).containsExactlyInAnyOrderElementsOf(beforeNames);
        assertThat(graph.countDocuments()).isEqualTo(1);
    }

    @Test
    void wrongValidatorFailsClosedWithoutRepairingSchemaOrChangingDocuments() {
        Context context = bootstrappedContext();
        MongoCollection<Document> graph = context.graphDatabase().getCollection(GraphProjectionSchema.COLLECTION);
        graph.insertOne(GraphProjectionDocumentCodec.encode(stored(scope("institution-a", "epoch-a"), "event-a", "proof-a")));
        context.graphDatabase().runCommand(new Document("collMod", GraphProjectionSchema.COLLECTION)
                .append("validator", new Document("$jsonSchema", new Document("bsonType", "object"))));
        Document validatorBefore = collectionValidator(context.graphDatabase());

        assertSchemaMismatch(() -> new MongoGraphProjectionStore(context.writerDatabase(), GraphProjectionSchema.WRITER));

        assertThat(collectionValidator(context.graphDatabase())).isEqualTo(validatorBefore);
        assertThat(graph.countDocuments()).isEqualTo(1);
    }

    @Test
    void bootstrapRefusesNonEmptyDatabaseAndPreservesPreexistingData() {
        Context context = context();
        MongoCollection<Document> sentinel = context.graphDatabase().getCollection("preexisting");
        sentinel.insertOne(new Document("marker", "preserve"));

        assertSchemaMismatch(() -> GraphProjectionSchema.bootstrapEmptyDatabase(context.schemaOwnerDatabase()));

        assertThat(context.graphDatabase().listCollectionNames()).containsExactly("preexisting");
        assertThat(sentinel.find().first()).isEqualTo(new Document("_id", sentinel.find().first().get("_id")).append("marker", "preserve"));
    }

    private static Context bootstrappedContext() {
        Context context = context();
        GraphProjectionSchema.bootstrapEmptyDatabase(context.schemaOwnerDatabase());
        return context;
    }

    private static Context context() {
        String database = "graph_schema_test_" + ++databaseSequence;
        MongoClient schemaOwner = MONGO.schemaOwner(root, database);
        MongoClient writer = MONGO.writer(root, database);
        MongoClient reader = MONGO.reader(root, database);
        return new Context(database, schemaOwner, writer, reader);
    }

    private static void assertValidationRejected(MongoCollection<Document> collection, Document invalid) {
        assertThatThrownBy(() -> collection.insertOne(invalid)).isInstanceOf(MongoWriteException.class)
                .satisfies(error -> assertThat(((MongoWriteException) error).getError().getCode()).isEqualTo(121));
    }

    private static void assertDuplicateKey(MongoCollection<Document> collection, Document conflicting) {
        assertThatThrownBy(() -> collection.insertOne(conflicting)).isInstanceOf(MongoWriteException.class)
                .satisfies(error -> assertThat(((MongoWriteException) error).getError().getCode()).isEqualTo(11000));
    }

    private static Document copy(Document document) { return Document.parse(document.toJson()); }

    private static void assertSchemaMismatch(ThrowingAction action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class)
                .hasMessage("GRAPH_STORE_SCHEMA_INCOMPATIBLE");
    }

    private static void assertUnauthorized(ThrowingAction action) {
        assertThatThrownBy(action::run).isInstanceOf(MongoCommandException.class)
                .satisfies(error -> assertThat(((MongoCommandException) error).getErrorCode()).isEqualTo(13));
    }

    private static List<String> indexNames(MongoDatabase database) {
        List<String> names = new ArrayList<>();
        for (Document index : database.getCollection(GraphProjectionSchema.COLLECTION).listIndexes()) {
            names.add(index.getString("name"));
        }
        return names;
    }

    private static Document collectionValidator(MongoDatabase database) {
        Document metadata = database.listCollections().filter(new Document("name", GraphProjectionSchema.COLLECTION)).first();
        return metadata.get("options", Document.class).get("validator", Document.class);
    }

    private static Set<String> indexNamesInPlan(Object value) {
        Set<String> names = new java.util.HashSet<>();
        collectIndexNames(value, names);
        return names;
    }

    private static void collectIndexNames(Object value, Set<String> names) {
        if (value instanceof Document document) {
            if (document.get("indexName") instanceof String name) { names.add(name); }
            document.values().forEach(child -> collectIndexNames(child, names));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectIndexNames(child, names));
        }
    }

    private static Set<String> stageNamesInPlan(Object value) {
        Set<String> names = new java.util.HashSet<>();
        collectStageNames(value, names);
        return names;
    }

    private static boolean hasBoundedDeviceIndexBounds(Object plan) {
        List<Document> scans = new ArrayList<>();
        collectIndexBounds(plan, scans);
        Set<String> required = Set.of("scope.sourceIssuerToken", "scope.institutionToken", "scope.sourceToken",
                "scope.sourceEpochToken", "scope.identityVersion", "edgeType", "device.token",
                "device.keyVersion", "occurrence.eventTime");
        return !scans.isEmpty() && scans.stream().allMatch(bounds -> required.stream().allMatch(field -> {
            Object range = bounds.get(field);
            if (range == null) { return false; }
            String rendered = String.valueOf(range);
            return !rendered.isBlank() && !rendered.contains("MinKey") && !rendered.contains("MaxKey");
        }));
    }

    private static void collectIndexBounds(Object value, List<Document> bounds) {
        if (value instanceof Document document) {
            if ("IXSCAN".equals(document.getString("stage"))
                    && document.get("indexBounds") instanceof Document indexBounds) {
                bounds.add(indexBounds);
            }
            document.values().forEach(child -> collectIndexBounds(child, bounds));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectIndexBounds(child, bounds));
        }
    }

    private static Document redactSensitivePlanValues(Document plan) {
        Document sanitized = new Document();
        for (var entry : plan.entrySet()) {
            sanitized.put(entry.getKey(), redactSensitivePlanValue(entry.getKey(), entry.getValue()));
        }
        return sanitized;
    }

    private static Object redactSensitivePlanValue(String field, Object value) {
        String normalizedField = field.toLowerCase(java.util.Locale.ROOT);
        if (normalizedField.equals("indexbounds") || normalizedField.endsWith(".token")
                || normalizedField.contains("commitment") || normalizedField.contains("reference")
                || normalizedField.equals("_id")) {
            return "<redacted>";
        }
        if (value instanceof Document nested) { return redactSensitivePlanValues(nested); }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> redactSensitivePlanValue("", item)).toList();
        }
        if (value instanceof String string && string.matches("[A-Fa-f0-9]{64}")) {
            return "<digest-redacted>";
        }
        return value;
    }

    private static void collectStageNames(Object value, Set<String> names) {
        if (value instanceof Document document) {
            if (document.get("stage") instanceof String stage) { names.add(stage); }
            document.values().forEach(child -> collectStageNames(child, names));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectStageNames(child, names));
        }
    }

    private static Document logicalFilter(GraphStoredEdge stored) {
        GraphEvidenceRef evidence = stored.edge().evidence();
        return scopeFilter(evidence.scope()).append("edgeType", stored.edge().type().name())
                .append("occurrence.token", evidence.occurrence().occurrenceToken())
                .append("device.token", evidence.associatedIdentityToken())
                .append("device.keyVersion", evidence.pseudonymKeyVersion());
    }

    private static Document deviceFilter(GraphStoredEdge stored) {
        GraphEvidenceRef evidence = stored.edge().evidence();
        return scopeFilter(evidence.scope()).append("edgeType", stored.edge().type().name())
                .append("device.token", evidence.associatedIdentityToken())
                .append("device.keyVersion", evidence.pseudonymKeyVersion());
    }

    private static Document occurrenceFilter(GraphStoredEdge stored) {
        GraphEvidenceRef evidence = stored.edge().evidence();
        return scopeFilter(evidence.scope()).append("edgeType", stored.edge().type().name())
                .append("occurrence.token", evidence.occurrence().occurrenceToken())
                .append("occurrence.eventTime", java.util.Date.from(evidence.occurrence().eventTime()))
                .append("lifecycle.state", GraphEdgeLifecycle.ACTIVE.name())
                .append("lifecycle.admittedAt", new Document("$lte", java.util.Date.from(EVENT.plusSeconds(10))))
                .append("lifecycle.validUntil", new Document("$gt", java.util.Date.from(EVENT.plusSeconds(10))));
    }

    private static Document scopeFilter(GraphIdentityScope scope) {
        return new Document("scope.sourceIssuerToken", scope.sourceIssuerToken())
                .append("scope.institutionToken", scope.institutionToken()).append("scope.sourceToken", scope.sourceToken())
                .append("scope.sourceEpochToken", scope.sourceEpochToken()).append("scope.identityVersion", scope.identityVersion());
    }

    private static GraphStoredEdge stored(GraphIdentityScope scope, String event, String proof) {
        return storedAt(scope, event, proof, EVENT);
    }

    private static GraphStoredEdge storedAt(GraphIdentityScope scope, String event, String proof, Instant eventTime) {
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token("event:" + event), eventTime);
        return edgeForOccurrence(scope, occurrence, "shared", proof);
    }

    private static GraphStoredEdge edgeForOccurrence(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                                      String device, String proof) {
        Instant eventTime = occurrence.eventTime();
        GraphScoredOccurrenceRef score = new GraphScoredOccurrenceRef(token("score:" + proof),
                eventTime.plusMillis(1), token("fingerprint:" + proof),
                GraphScoredOccurrenceRef.INITIAL_RUN);
        GraphEvidenceRef evidence = new GraphEvidenceRef(scope, occurrence, token("device:" + device), 7,
                token("receipt:" + proof), token("raw:" + proof), token("enriched:" + proof), token("assertion:" + proof),
                token("coverage:" + proof), token("policy:" + proof), score, token("snapshot:" + proof),
                eventTime.minusSeconds(300), eventTime, 1, eventTime.plus(Duration.ofDays(30)));
        GraphEdgeFact edge = new GraphEdgeFact(GraphProjectionVersion.CURRENT, GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.occurrence(occurrence), GraphNodeRef.device(scope, evidence.associatedIdentityToken(), 7), evidence);
        return new GraphStoredEdge(edge, GraphEdgeLifecycle.ACTIVE,
                eventTime.plusSeconds(5).truncatedTo(ChronoUnit.MILLIS), evidence.expiresAt());
    }

    private static GraphIdentityScope scope(String institution, String epoch) {
        return new GraphIdentityScope(token("issuer"), token(institution), token("source"), token(epoch), 2);
    }

    private static String token(String value) { return GraphCanonical.digest("schema-integration-test", value); }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private record Context(String databaseName, MongoClient schemaOwnerClient, MongoClient writerClient, MongoClient readerClient) {
        MongoDatabase graphDatabase() { return root.getDatabase(databaseName); }
        MongoDatabase schemaOwnerDatabase() { return schemaOwnerClient.getDatabase(databaseName); }
        MongoDatabase writerDatabase() { return writerClient.getDatabase(databaseName); }
        MongoDatabase readerDatabase() { return readerClient.getDatabase(databaseName); }
    }

    private static final class StartupCommandProbe implements CommandListener {
        private final AtomicInteger schemaCommands = new AtomicInteger();
        private final AtomicInteger findCommands = new AtomicInteger();

        @Override public void commandStarted(CommandStartedEvent event) {
            switch (event.getCommandName()) {
                case "connectionStatus", "listCollections", "listIndexes" -> schemaCommands.incrementAndGet();
                case "find" -> findCommands.incrementAndGet();
                default -> { }
            }
        }

        @Override public void commandFailed(CommandFailedEvent event) { }

        int schemaCommands() { return schemaCommands.get(); }
        int findCommands() { return findCommands.get(); }
        void reset() { schemaCommands.set(0); findCommands.set(0); }
    }

    private static final class DeviceNeighborBatchProbe implements CommandListener {
        private final List<Integer> batchSizes = new ArrayList<>();

        @Override public void commandStarted(CommandStartedEvent event) {
            if (!"find".equals(event.getCommandName())
                    || !GraphProjectionSchema.COLLECTION.equals(event.getCommand().getString("find").getValue())) {
                return;
            }
            var batchSize = event.getCommand().get("batchSize");
            if (batchSize != null && batchSize.isInt32()) { batchSizes.add(batchSize.asInt32().getValue()); }
        }

        @Override public void commandFailed(CommandFailedEvent event) { }
    }

    private static final class CursorBatchProbe implements CommandListener {
        private final List<Integer> findBatchSizes = new ArrayList<>();
        private final List<Integer> findMaxTimeMillis = new ArrayList<>();
        private final List<Integer> getMoreBatchSizes = new ArrayList<>();

        @Override public void commandStarted(CommandStartedEvent event) {
            var command = event.getCommand();
            if (command.containsKey("find")
                    && GraphProjectionSchema.COLLECTION.equals(command.getString("find").getValue())) {
                findBatchSizes.add(command.getNumber("batchSize").intValue());
                findMaxTimeMillis.add(command.getNumber("maxTimeMS").intValue());
            } else if (command.containsKey("getMore")
                    && GraphProjectionSchema.COLLECTION.equals(command.getString("collection").getValue())) {
                getMoreBatchSizes.add(command.getNumber("batchSize").intValue());
            }
        }

        @Override public void commandFailed(CommandFailedEvent event) { }
    }
}
