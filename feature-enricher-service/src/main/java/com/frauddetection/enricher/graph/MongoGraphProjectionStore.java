package com.frauddetection.enricher.graph;

import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

/** Private Mongo projection adapter. DDL is verified at startup and never repaired here. */
final class MongoGraphProjectionStore implements GraphProjectionStore, GraphProjectionDao {
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private static final int MAX_COMMIT_ACK_ATTEMPTS = 3;
    private static final int MAX_DEVICE_HUBS_PER_QUERY = 16;
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY)
            .readPreference(ReadPreference.primary()).build();

    private final MongoClient client;
    private final MongoDatabase database;
    private final String authenticatedPrincipal;
    private final GraphEvidenceAdmission admission;
    private final Clock clock;
    private final GraphTransactionCommitter transactionCommitter;

    /** Read adapter constructor retained for schema verification and read-only consumers. */
    MongoGraphProjectionStore(MongoDatabase database, String authenticatedPrincipal) {
        this(null, Objects.requireNonNull(database, "GRAPH_DATABASE_REQUIRED"), authenticatedPrincipal,
                null, Clock.systemUTC(), ClientSession::commitTransaction);
    }

    MongoGraphProjectionStore(MongoClient client, String databaseName, String authenticatedPrincipal,
                              GraphEvidenceAdmission admission, Clock clock) {
        this(client, Objects.requireNonNull(client, "GRAPH_MONGO_CLIENT_REQUIRED").getDatabase(databaseName),
                authenticatedPrincipal, admission, clock, ClientSession::commitTransaction);
    }

    MongoGraphProjectionStore(MongoClient client, String databaseName, String authenticatedPrincipal,
                              GraphEvidenceAdmission admission, Clock clock,
                              GraphTransactionCommitter transactionCommitter) {
        this(client, Objects.requireNonNull(client, "GRAPH_MONGO_CLIENT_REQUIRED").getDatabase(databaseName),
                authenticatedPrincipal, admission, clock, transactionCommitter);
    }

    private MongoGraphProjectionStore(MongoClient client, MongoDatabase database, String authenticatedPrincipal,
                                      GraphEvidenceAdmission admission, Clock clock,
                                      GraphTransactionCommitter transactionCommitter) {
        this.client = client;
        this.database = database.withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY)
                .withReadPreference(ReadPreference.primary());
        if (!GraphProjectionSchema.WRITER.equals(authenticatedPrincipal)
                && !GraphProjectionSchema.READER.equals(authenticatedPrincipal)) {
            throw new IllegalArgumentException("GRAPH_STORE_PRINCIPAL_INVALID");
        }
        this.authenticatedPrincipal = authenticatedPrincipal;
        this.admission = admission;
        this.clock = Objects.requireNonNull(clock, "GRAPH_CLOCK_REQUIRED");
        this.transactionCommitter = Objects.requireNonNull(transactionCommitter, "GRAPH_COMMITTER_REQUIRED");
        GraphProjectionSchema.verify(this.database, authenticatedPrincipal);
    }

    @Override public GraphAppendOutcome appendVerified(GraphEvidenceAdmission.AdmittedRelation admitted) {
        if (!GraphProjectionSchema.WRITER.equals(authenticatedPrincipal) || client == null || admission == null) {
            return GraphAppendOutcome.denied(GraphReadReason.NOT_SUPPORTED);
        }
        GraphAdmissionOutcome preflight = admission.revalidateForPersistence(admitted);
        if (preflight.state() != GraphAdmissionState.ADMITTED) { return appendFailure(preflight); }

        MongoException lastRetryable = null;
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return transactAppend(admitted);
            } catch (MongoException failure) {
                if (failure.getCode() == 11000 || failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    lastRetryable = failure;
                    if (attempt + 1 < MAX_TRANSACTION_ATTEMPTS) { continue; }
                }
                throw failure;
            }
        }
        throw lastRetryable == null ? new MongoException("GRAPH_TRANSACTION_RETRY_EXHAUSTED") : lastRetryable;
    }

    @Override public GraphAppendOutcome fenceWithinAdmissionOperation(GraphEvidenceAdmission.AdmittedRelation admitted) {
        if (!GraphProjectionSchema.WRITER.equals(authenticatedPrincipal) || client == null || admission == null) {
            return GraphAppendOutcome.denied(GraphReadReason.NOT_SUPPORTED);
        }
        if (admitted == null) { return GraphAppendOutcome.denied(GraphReadReason.REFERENCE_INVALID); }
        MongoException lastRetryable = null;
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return transactAdmissionWindowFence(admitted);
            } catch (MongoException failure) {
                if (failure.getCode() == 11000 || failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    lastRetryable = failure;
                    if (attempt + 1 < MAX_TRANSACTION_ATTEMPTS) { continue; }
                }
                throw failure;
            }
        }
        throw lastRetryable == null ? new MongoException("GRAPH_TRANSACTION_RETRY_EXHAUSTED") : lastRetryable;
    }

    @Override public GraphAppendOutcome reconcileLifecycle(GraphLifecycleReconciliationRequest request) {
        if (!GraphProjectionSchema.WRITER.equals(authenticatedPrincipal) || client == null || admission == null) {
            return GraphAppendOutcome.denied(GraphReadReason.NOT_SUPPORTED);
        }
        GraphLifecycleFenceOutcome preflight = admission.preflightReconciliation(request);
        if (preflight.state() != GraphLifecycleFenceOutcome.State.NO_CHANGE) {
            return reconciliationFailure(preflight);
        }
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return transactLifecycleReconciliation(request);
            } catch (MongoException failure) {
                if (failure.getCode() == 11000 || failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    if (attempt + 1 < MAX_TRANSACTION_ATTEMPTS) { continue; }
                }
                return GraphAppendOutcome.unavailable(GraphReadReason.STORE_UNAVAILABLE);
            } catch (IllegalStateException invalidStoredRow) {
                if ("GRAPH_STORE_DOCUMENT_INVALID".equals(invalidStoredRow.getMessage())) {
                    return GraphAppendOutcome.unavailable(GraphReadReason.STORE_UNAVAILABLE);
                }
                throw invalidStoredRow;
            }
        }
        return GraphAppendOutcome.unavailable(GraphReadReason.STORE_UNAVAILABLE);
    }

    @Override public Optional<GraphStoredEdge> findLogicalRelation(GraphEdgeFact candidate) {
        Objects.requireNonNull(candidate, "GRAPH_CANDIDATE_REQUIRED");
        Document filter = logicalRelationFilter(candidate);
        Document found = collection().find(filter).limit(1).first();
        return found == null ? Optional.empty() : Optional.of(GraphProjectionDocumentCodec.decode(found));
    }

    @Override public List<GraphStoredEdge> findNeighbors(GraphNodeRef node, int limit) {
        Objects.requireNonNull(node, "GRAPH_NODE_REQUIRED");
        if (limit < 1 || limit > GraphPolicyLimits.HARD_MAX_EDGES) {
            throw new IllegalArgumentException("GRAPH_POLICY_LIMIT_INVALID");
        }
        Document filter = scopeFilter(node.scope()).append("edgeType", GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name());
        if (node.kind() == GraphNodeKind.TRANSACTION_OCCURRENCE) {
            filter.put("occurrence.token", node.identityToken());
        } else if (node.kind() == GraphNodeKind.DEVICE_PSEUDONYM) {
            filter.put("device.token", node.identityToken());
            filter.put("device.keyVersion", node.pseudonymKeyVersion());
        } else {
            throw new IllegalArgumentException("GRAPH_EDGE_TYPE_UNSUPPORTED");
        }
        var find = collection().find(filter).limit(limit);
        if (node.kind() == GraphNodeKind.DEVICE_PSEUDONYM) {
            find.sort(ascending("occurrence.eventTime", "occurrence.token"));
        }
        List<GraphStoredEdge> result = new ArrayList<>(limit);
        for (Document document : find) { result.add(GraphProjectionDocumentCodec.decode(document)); }
        return List.copyOf(result);
    }

    @Override public List<GraphStoredEdge> findOccurrenceNeighbors(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                                                                    Instant asOf, int limit, int maxPageSize,
                                                                    java.time.Duration maxTime) {
        Objects.requireNonNull(scope, "GRAPH_SCOPE_REQUIRED");
        Objects.requireNonNull(occurrence, "GRAPH_OCCURRENCE_REQUIRED");
        if (!scope.equals(occurrence.scope())) { throw new IllegalArgumentException("GRAPH_QUERY_SCOPE_INVALID"); }
        GraphValidation.millisecondInstant(asOf);
        Objects.requireNonNull(maxTime, "GRAPH_QUERY_TIME_REQUIRED");
        if (!GraphProjectionSchema.READER.equals(authenticatedPrincipal)
                || limit < 1 || limit > GraphQueryLimits.DEFAULT.maxEdges() + 1
                || maxPageSize < 1 || maxPageSize > GraphQueryLimits.DEFAULT.maxPageSize()
                || maxTime.isZero() || maxTime.isNegative() || maxTime.toMillis() < 1
                || maxTime.compareTo(java.time.Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("GRAPH_QUERY_LIMIT_INVALID");
        }
        Document filter = scopeFilter(scope)
                .append("edgeType", GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())
                .append("occurrence.token", occurrence.occurrenceToken())
                .append("occurrence.eventTime", java.util.Date.from(occurrence.eventTime()))
                .append("lifecycle.state", GraphEdgeLifecycle.ACTIVE.name())
                .append("lifecycle.admittedAt", new Document("$lte", java.util.Date.from(asOf)))
                .append("lifecycle.validUntil", new Document("$gt", java.util.Date.from(asOf)));
        var find = collection().find(filter).sort(ascending("_id")).limit(limit)
                .batchSize(Math.min(limit, maxPageSize))
                .maxTime(maxTime.toMillis(), TimeUnit.MILLISECONDS);
        List<GraphStoredEdge> result = new ArrayList<>(Math.min(limit, maxPageSize));
        for (Document document : find) { result.add(GraphProjectionDocumentCodec.decode(document)); }
        return List.copyOf(result);
    }

    @Override public List<GraphStoredEdge> findDeviceOccurrenceNeighbors(GraphIdentityScope scope,
                                                                           List<GraphNodeRef> deviceNodes,
                                                                           Instant fromInclusive,
                                                                           Instant untilExclusive,
                                                                           Instant asOf,
                                                                           int limit,
                                                                           int maxPageSize,
                                                                           java.time.Duration maxTime) {
        if (!GraphProjectionSchema.READER.equals(authenticatedPrincipal)) {
            throw new IllegalArgumentException("GRAPH_QUERY_PRINCIPAL_INVALID");
        }
        Objects.requireNonNull(maxTime, "GRAPH_QUERY_TIME_REQUIRED");
        if (limit < 1 || limit > GraphQueryLimits.DEFAULT.maxEdges() + 1
                || maxPageSize < 1 || maxPageSize > GraphQueryLimits.DEFAULT.maxPageSize()
                || maxTime.isZero() || maxTime.isNegative() || maxTime.toMillis() < 1
                || maxTime.compareTo(java.time.Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("GRAPH_QUERY_LIMIT_INVALID");
        }
        deviceNeighborFilter(scope, deviceNodes, fromInclusive, untilExclusive, asOf);
        long started = System.nanoTime();
        List<GraphStoredEdge> result = new ArrayList<>(Math.min(limit, maxPageSize));
        for (List<GraphNodeRef> hubChunk : deviceNeighborChunks(deviceNodes)) {
            int remaining = limit - result.size();
            if (remaining == 0) { break; }
            long remainingMillis = remainingQueryMillis(started, maxTime);
            Document filter = deviceNeighborFilter(scope, hubChunk, fromInclusive, untilExclusive, asOf);
            var find = collection().find(filter).sort(deviceNeighborSort())
                    .hintString("graph_device_neighbors").limit(remaining)
                    .batchSize(Math.min(remaining, maxPageSize))
                    .maxTime(remainingMillis, TimeUnit.MILLISECONDS);
            for (Document document : find) {
                result.add(GraphProjectionDocumentCodec.decode(document));
                if (result.size() == limit) { break; }
            }
        }
        result.sort(Comparator
                .comparing((GraphStoredEdge row) -> row.edge().to().identityToken())
                .thenComparingInt(row -> row.edge().to().pseudonymKeyVersion())
                .thenComparing(row -> row.edge().evidence().occurrence().eventTime())
                .thenComparing(row -> row.edge().evidence().occurrence().occurrenceToken()));
        return List.copyOf(result);
    }

    static List<List<GraphNodeRef>> deviceNeighborChunks(List<GraphNodeRef> deviceNodes) {
        Objects.requireNonNull(deviceNodes, "GRAPH_DEVICE_NODES_REQUIRED");
        if (deviceNodes.isEmpty() || deviceNodes.size() > GraphQueryLimits.DEFAULT.maxNodes()) {
            throw new IllegalArgumentException("GRAPH_DEVICE_QUERY_RANGE_INVALID");
        }
        List<List<GraphNodeRef>> chunks = new ArrayList<>((deviceNodes.size() + MAX_DEVICE_HUBS_PER_QUERY - 1)
                / MAX_DEVICE_HUBS_PER_QUERY);
        for (int start = 0; start < deviceNodes.size(); start += MAX_DEVICE_HUBS_PER_QUERY) {
            chunks.add(List.copyOf(deviceNodes.subList(start,
                    Math.min(start + MAX_DEVICE_HUBS_PER_QUERY, deviceNodes.size()))));
        }
        return List.copyOf(chunks);
    }

    private static long remainingQueryMillis(long started, java.time.Duration budget) {
        long remainingNanos = budget.toNanos() - (System.nanoTime() - started);
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (remainingMillis < 1) { throw new IllegalStateException("GRAPH_QUERY_DEADLINE_EXCEEDED"); }
        return remainingMillis;
    }

    static Document deviceNeighborFilter(GraphIdentityScope scope, List<GraphNodeRef> deviceNodes,
                                         Instant fromInclusive, Instant untilExclusive, Instant asOf) {
        Objects.requireNonNull(scope, "GRAPH_SCOPE_REQUIRED");
        Objects.requireNonNull(deviceNodes, "GRAPH_DEVICE_NODES_REQUIRED");
        Objects.requireNonNull(fromInclusive, "GRAPH_FROM_REQUIRED");
        Objects.requireNonNull(untilExclusive, "GRAPH_UNTIL_REQUIRED");
        Objects.requireNonNull(asOf, "GRAPH_AS_OF_REQUIRED");
        GraphValidation.millisecondInstant(fromInclusive);
        GraphValidation.millisecondInstant(untilExclusive);
        GraphValidation.millisecondInstant(asOf);
        if (deviceNodes.isEmpty() || deviceNodes.size() > GraphQueryLimits.DEFAULT.maxNodes()
                || !untilExclusive.isAfter(fromInclusive) || untilExclusive.isAfter(asOf)) {
            throw new IllegalArgumentException("GRAPH_DEVICE_QUERY_RANGE_INVALID");
        }
        List<Document> hubs = new ArrayList<>(deviceNodes.size());
        java.util.Set<String> unique = new java.util.HashSet<>();
        for (GraphNodeRef node : deviceNodes) {
            if (node == null || node.kind() != GraphNodeKind.DEVICE_PSEUDONYM || !scope.equals(node.scope())
                    || !unique.add(node.canonicalId())) {
                throw new IllegalArgumentException("GRAPH_DEVICE_QUERY_HUB_INVALID");
            }
            hubs.add(new Document("device.token", node.identityToken())
                    .append("device.keyVersion", node.pseudonymKeyVersion()));
        }
        Document filter = scopeFilter(scope)
                .append("schemaVersion", GraphProjectionSchema.SCHEMA_VERSION)
                .append("edgeType", GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())
                .append("occurrence.eventTime", new Document("$gte", java.util.Date.from(fromInclusive))
                        .append("$lt", java.util.Date.from(untilExclusive)))
                .append("lifecycle.state", GraphEdgeLifecycle.ACTIVE.name())
                .append("lifecycle.admittedAt", new Document("$lte", java.util.Date.from(asOf)))
                .append("lifecycle.validUntil", new Document("$gt", java.util.Date.from(asOf)));
        filter.append("$or", hubs);
        return filter;
    }

    static org.bson.conversions.Bson deviceNeighborSort() {
        return ascending("device.token", "device.keyVersion", "occurrence.eventTime", "occurrence.token");
    }

    private GraphAppendOutcome transactAppend(GraphEvidenceAdmission.AdmittedRelation initiallyAdmitted) {
        TransactionMutation mutation;
        MongoException unknownCommit = null;
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TRANSACTION);
            try {
                mutation = mutateWithinTransaction(session, initiallyAdmitted);
                if (!mutation.wrote()) {
                    abort(session);
                    return mutation.outcome();
                }
            } catch (RuntimeException failure) {
                abort(session);
                throw failure;
            }
            try {
                commitWithUnknownResultRetry(session);
            } catch (MongoException failure) {
                if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    abort(session);
                    throw failure;
                }
                unknownCommit = failure;
            }
        }
        if (unknownCommit == null) { return mutation.outcome(); }
        Optional<GraphStoredEdge> committed = findLogicalRelation(mutation.expectedEdge());
        if (mutation.matchesCommittedMutation(committed)) { return GraphAppendOutcome.recovered(committed.orElseThrow()); }
        throw unknownCommit;
    }

    private GraphAppendOutcome transactAdmissionWindowFence(GraphEvidenceAdmission.AdmittedRelation admitted) {
        FenceMutation mutation;
        MongoException unknownCommit = null;
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TRANSACTION);
            try {
                mutation = mutateAdmissionWindowFence(session, admitted);
                if (!mutation.wrote()) {
                    abort(session);
                    return mutation.outcome();
                }
            } catch (RuntimeException failure) {
                abort(session);
                throw failure;
            }
            try {
                commitWithUnknownResultRetry(session);
            } catch (MongoException failure) {
                if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    abort(session);
                    throw failure;
                }
                unknownCommit = failure;
            }
        }
        if (unknownCommit == null) { return mutation.outcome(); }
        Optional<GraphStoredEdge> committed = findLogicalRelation(admitted.edge());
        if (mutation.matchesCommittedMutation(committed)) { return GraphAppendOutcome.recovered(committed.orElseThrow()); }
        throw unknownCommit;
    }

    private GraphAppendOutcome transactLifecycleReconciliation(GraphLifecycleReconciliationRequest request) {
        FenceMutation mutation;
        MongoException unknownCommit = null;
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TRANSACTION);
            try {
                mutation = mutateLifecycleReconciliation(session, request);
                if (!mutation.wrote()) {
                    abort(session);
                    return mutation.outcome();
                }
                if (!request.operationDeadline().isAfter(clock.instant())) {
                    abort(session);
                    return GraphAppendOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED);
                }
            } catch (RuntimeException failure) {
                abort(session);
                throw failure;
            }
            try {
                commitWithUnknownResultRetry(session);
            } catch (MongoException failure) {
                if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    abort(session);
                    throw failure;
                }
                unknownCommit = failure;
            }
        }
        if (unknownCommit == null) { return mutation.outcome(); }
        Optional<GraphStoredEdge> committed = findReconciliationTarget(request);
        if (mutation.matchesCommittedMutation(committed)) { return GraphAppendOutcome.recovered(committed.orElseThrow()); }
        return GraphAppendOutcome.unavailable(GraphReadReason.STORE_UNAVAILABLE);
    }

    private FenceMutation mutateLifecycleReconciliation(ClientSession session,
                                                         GraphLifecycleReconciliationRequest request) {
        MongoCollection<Document> edges = collection();
        Document currentDocument = edges.find(session, reconciliationFilter(request)).limit(1).first();
        if (currentDocument == null) {
            return FenceMutation.unchanged(GraphAppendOutcome.unavailable(GraphReadReason.REFERENCE_INVALID));
        }
        GraphStoredEdge current = GraphProjectionDocumentCodec.decode(currentDocument);
        if (current.lifecycle() == GraphEdgeLifecycle.CONFLICT || current.lifecycle() == GraphEdgeLifecycle.REVOKED) {
            return FenceMutation.unchanged(GraphAppendOutcome.fenced(current));
        }

        GraphLifecycleFenceOutcome decision = admission.resolveReconciliationFence(request, current);
        if (decision.state() != GraphLifecycleFenceOutcome.State.FENCE
                && decision.state() != GraphLifecycleFenceOutcome.State.NO_CHANGE) {
            return FenceMutation.unchanged(reconciliationFailure(decision));
        }
        if (decision.state() == GraphLifecycleFenceOutcome.State.NO_CHANGE) {
            return FenceMutation.unchanged(GraphAppendOutcome.existing(current));
        }
        if (!request.operationDeadline().isAfter(clock.instant())) {
            return FenceMutation.unchanged(GraphAppendOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED));
        }

        GraphEdgeLifecycle target = decision.fence().lifecycle();
        long updated = edges.updateOne(session,
                and(eq("_id", current.edge().canonicalId()), eq("lifecycle.state", GraphEdgeLifecycle.ACTIVE.name())),
                new Document("$set", new Document("lifecycle.state", target.name()))).getModifiedCount();
        if (updated != 1) { throw transientWriteConflict(); }
        GraphStoredEdge fenced = new GraphStoredEdge(current.edge(), target, current.admittedAt(), current.validUntil());
        if (!request.operationDeadline().isAfter(clock.instant())) {
            return FenceMutation.unchanged(GraphAppendOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED));
        }
        return FenceMutation.changed(GraphAppendOutcome.fenced(fenced), fenced);
    }

    private Optional<GraphStoredEdge> findReconciliationTarget(GraphLifecycleReconciliationRequest request) {
        Document found = collection().find(reconciliationFilter(request)).limit(1).first();
        return found == null ? Optional.empty() : Optional.of(GraphProjectionDocumentCodec.decode(found));
    }

    private static Document reconciliationFilter(GraphLifecycleReconciliationRequest request) {
        return scopeFilter(request.scope())
                .append("edgeType", GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())
                .append("occurrence.token", request.occurrence().occurrenceToken())
                .append("occurrence.eventTime", java.util.Date.from(request.occurrence().eventTime()))
                .append("device.token", request.associatedIdentityToken())
                .append("device.keyVersion", request.pseudonymKeyVersion());
    }

    private static GraphAppendOutcome reconciliationFailure(GraphLifecycleFenceOutcome outcome) {
        return switch (outcome.state()) {
            case DENIED -> GraphAppendOutcome.denied(outcome.reason());
            case PENDING -> GraphAppendOutcome.pending(outcome.reason());
            case UNAVAILABLE -> GraphAppendOutcome.unavailable(outcome.reason());
            case FENCE, NO_CHANGE -> throw new IllegalArgumentException("GRAPH_RECONCILIATION_OUTCOME_INVALID");
        };
    }

    private FenceMutation mutateAdmissionWindowFence(ClientSession session,
                                                      GraphEvidenceAdmission.AdmittedRelation admitted) {
        GraphLifecycleFenceOutcome decision = admission.resolveAdmissionWindowFence(admitted);
        if (decision.state() != GraphLifecycleFenceOutcome.State.FENCE
                && decision.state() != GraphLifecycleFenceOutcome.State.NO_CHANGE) {
            GraphAppendOutcome failed = switch (decision.state()) {
                case DENIED -> GraphAppendOutcome.denied(decision.reason());
                case PENDING -> GraphAppendOutcome.pending(decision.reason());
                case UNAVAILABLE -> GraphAppendOutcome.unavailable(decision.reason());
                case FENCE, NO_CHANGE -> throw new IllegalArgumentException("GRAPH_FENCE_FAILURE_INVALID");
            };
            return FenceMutation.unchanged(failed);
        }
        MongoCollection<Document> edges = collection();
        Document currentDocument = edges.find(session, logicalRelationFilter(admitted.edge())).limit(1).first();
        if (currentDocument == null) {
            return FenceMutation.unchanged(GraphAppendOutcome.unavailable(GraphReadReason.REFERENCE_INVALID));
        }
        GraphStoredEdge current = GraphProjectionDocumentCodec.decode(currentDocument);
        if (current.lifecycle() == GraphEdgeLifecycle.CONFLICT || current.lifecycle() == GraphEdgeLifecycle.REVOKED) {
            return FenceMutation.unchanged(GraphAppendOutcome.fenced(current));
        }
        GraphEdgeLifecycle target = decision.state() == GraphLifecycleFenceOutcome.State.FENCE
                ? decision.fence().lifecycle()
                : current.edge().equals(admitted.edge()) ? null : GraphEdgeLifecycle.CONFLICT;
        if (target == null) { return FenceMutation.unchanged(GraphAppendOutcome.existing(current)); }
        long updated = edges.updateOne(session,
                and(eq("_id", current.edge().canonicalId()), eq("lifecycle.state", GraphEdgeLifecycle.ACTIVE.name())),
                new Document("$set", new Document("lifecycle.state", target.name()))).getModifiedCount();
        if (updated != 1) { throw transientWriteConflict(); }
        GraphStoredEdge fenced = new GraphStoredEdge(current.edge(), target, current.admittedAt(), current.validUntil());
        return FenceMutation.changed(GraphAppendOutcome.fenced(fenced), fenced);
    }

    private TransactionMutation mutateWithinTransaction(ClientSession session,
                                                         GraphEvidenceAdmission.AdmittedRelation admitted) {
        GraphAdmissionOutcome current = admission.revalidateForPersistence(admitted);
        if (current.state() != GraphAdmissionState.ADMITTED) {
            return TransactionMutation.unchanged(appendFailure(current));
        }
        GraphEvidenceAdmission.AdmittedRelation refreshed = current.relation();
        GraphStoredEdge candidate = new GraphStoredEdge(refreshed.edge(), GraphEdgeLifecycle.ACTIVE,
                clock.instant().truncatedTo(ChronoUnit.MILLIS), refreshed.validUntil());
        MongoCollection<Document> edges = collection();
        Document existingDocument = edges.find(session, logicalRelationFilter(candidate.edge())).limit(1).first();
        if (existingDocument != null) {
            GraphStoredEdge existing = GraphProjectionDocumentCodec.decode(existingDocument);
            return classifyExisting(session, edges, candidate, existing);
        }
        edges.insertOne(session, GraphProjectionDocumentCodec.encode(candidate));
        return TransactionMutation.changed(GraphAppendOutcome.appended(candidate), candidate.edge());
    }

    private TransactionMutation classifyExisting(ClientSession session, MongoCollection<Document> edges,
                                                   GraphStoredEdge candidate, GraphStoredEdge existing) {
        if (!candidate.edge().equals(existing.edge())) {
            if (existing.lifecycle() == GraphEdgeLifecycle.REVOKED) {
                return TransactionMutation.unchanged(GraphAppendOutcome.denied(GraphReadReason.AUTHORITY_REVOKED));
            }
            if (existing.lifecycle() == GraphEdgeLifecycle.CONFLICT) {
                return TransactionMutation.unchanged(GraphAppendOutcome.conflict(existing));
            }
            long updated = edges.updateOne(session,
                    and(eq("_id", existing.edge().canonicalId()), eq("lifecycle.state", GraphEdgeLifecycle.ACTIVE.name())),
                    new Document("$set", new Document("lifecycle.state", GraphEdgeLifecycle.CONFLICT.name()))).getModifiedCount();
            if (updated != 1) { throw transientWriteConflict(); }
            GraphStoredEdge conflicted = new GraphStoredEdge(existing.edge(), GraphEdgeLifecycle.CONFLICT,
                    existing.admittedAt(), existing.validUntil());
            return TransactionMutation.changed(GraphAppendOutcome.conflict(conflicted), existing.edge());
        }
        if (existing.lifecycle() == GraphEdgeLifecycle.CONFLICT) {
            return TransactionMutation.unchanged(GraphAppendOutcome.conflict(existing));
        }
        if (existing.lifecycle() == GraphEdgeLifecycle.REVOKED) {
            return TransactionMutation.unchanged(GraphAppendOutcome.denied(GraphReadReason.AUTHORITY_REVOKED));
        }
        if (!existing.validUntil().isAfter(clock.instant())) {
            return TransactionMutation.unchanged(GraphAppendOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED));
        }
        return TransactionMutation.unchanged(GraphAppendOutcome.existing(existing));
    }

    private static GraphAppendOutcome appendFailure(GraphAdmissionOutcome failure) {
        return switch (failure.state()) {
            case ADMITTED -> throw new IllegalArgumentException("GRAPH_ADMISSION_FAILURE_INVALID");
            case DENIED -> GraphAppendOutcome.denied(failure.reason());
            case CONFLICT -> GraphAppendOutcome.conflict(null);
            case PENDING -> GraphAppendOutcome.pending(failure.reason());
            case UNAVAILABLE -> GraphAppendOutcome.unavailable(failure.reason());
        };
    }

    static void commitWithUnknownResultRetry(ClientSession session, GraphTransactionCommitter committer) {
        for (int attempt = 0; attempt < MAX_COMMIT_ACK_ATTEMPTS; attempt++) {
            try {
                committer.commit(session);
                return;
            } catch (MongoException failure) {
                if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                        || attempt + 1 == MAX_COMMIT_ACK_ATTEMPTS) {
                    throw failure;
                }
            }
        }
    }

    private void commitWithUnknownResultRetry(ClientSession session) {
        commitWithUnknownResultRetry(session, transactionCommitter);
    }

    private MongoCollection<Document> collection() { return database.getCollection(GraphProjectionSchema.COLLECTION); }

    private static Document logicalRelationFilter(GraphEdgeFact candidate) {
        GraphEvidenceRef evidence = candidate.evidence();
        return scopeFilter(evidence.scope()).append("edgeType", candidate.type().name())
                .append("occurrence.token", evidence.occurrence().occurrenceToken())
                .append("device.token", evidence.associatedIdentityToken())
                .append("device.keyVersion", evidence.pseudonymKeyVersion());
    }

    private static Document scopeFilter(GraphIdentityScope scope) {
        return new Document("scope.sourceIssuerToken", scope.sourceIssuerToken())
                .append("scope.institutionToken", scope.institutionToken()).append("scope.sourceToken", scope.sourceToken())
                .append("scope.sourceEpochToken", scope.sourceEpochToken()).append("scope.identityVersion", scope.identityVersion());
    }

    private static void abort(ClientSession session) {
        try { if (session.hasActiveTransaction()) { session.abortTransaction(); } }
        catch (MongoException ignored) { /* The original write outcome remains visible to the caller. */ }
    }

    private static MongoException transientWriteConflict() {
        MongoException conflict = new MongoException(112, "GRAPH_WRITE_CONFLICT");
        conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return conflict;
    }

    private record TransactionMutation(GraphAppendOutcome outcome, boolean wrote, GraphEdgeFact expectedEdge,
                                       String expectedStoredId) {
        private static TransactionMutation unchanged(GraphAppendOutcome outcome) {
            return new TransactionMutation(outcome, false, null, null);
        }

        private static TransactionMutation changed(GraphAppendOutcome outcome, GraphEdgeFact expectedEdge) {
            return new TransactionMutation(outcome, true, expectedEdge, expectedEdge.canonicalId());
        }

        private boolean matchesCommittedMutation(Optional<GraphStoredEdge> stored) {
            return stored.isPresent() && expectedStoredId.equals(stored.orElseThrow().edge().canonicalId())
                    && expectedEdge.equals(stored.orElseThrow().edge())
                    && (outcome.state() == GraphAppendState.CONFLICT
                    ? stored.orElseThrow().lifecycle() == GraphEdgeLifecycle.CONFLICT
                    : stored.orElseThrow().lifecycle() == GraphEdgeLifecycle.ACTIVE
                    || stored.orElseThrow().lifecycle() == GraphEdgeLifecycle.CONFLICT);
        }
    }

    private record FenceMutation(GraphAppendOutcome outcome, boolean wrote, GraphStoredEdge expectedStored) {
        private static FenceMutation unchanged(GraphAppendOutcome outcome) {
            return new FenceMutation(outcome, false, null);
        }

        private static FenceMutation changed(GraphAppendOutcome outcome, GraphStoredEdge expectedStored) {
            return new FenceMutation(outcome, true, expectedStored);
        }

        private boolean matchesCommittedMutation(Optional<GraphStoredEdge> stored) {
            return stored.isPresent() && expectedStored.edge().equals(stored.orElseThrow().edge())
                    && expectedStored.lifecycle() == stored.orElseThrow().lifecycle();
        }
    }
}
