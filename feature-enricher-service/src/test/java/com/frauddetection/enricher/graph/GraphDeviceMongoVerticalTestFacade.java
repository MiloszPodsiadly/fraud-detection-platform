package com.frauddetection.enricher.graph;

import com.frauddetection.enricher.device.DeviceGraphReadGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationResolution;
import com.frauddetection.enricher.device.DeviceGraphRelationGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationPort;
import com.frauddetection.enricher.device.VerifiedDeviceGraphRelation;
import com.mongodb.client.MongoClient;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only assembly of the real Device owner bridge and authenticated Mongo Graph adapters. */
public final class GraphDeviceMongoVerticalTestFacade {
    private GraphDeviceMongoVerticalTestFacade() { }

    public static Result persistAndReadAfterAdapterRestart(DeviceGraphRelationPort deviceOwner,
                                                            Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                            DeviceGraphRelationGrant relationGrant,
                                                            DeviceGraphReadGrant deviceReadGrant,
                                                            List<String> ownerOccurrenceReferences,
                                                            String requestingPrincipal,
                                                            Instant admissionDeadline,
                                                            Instant readAt) throws Exception {
        return persistAndReadAfterAdapterRestart(deviceOwner, restartedDeviceOwner, relationGrant, relationGrant,
                deviceReadGrant, ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt, () -> { });
    }

    public static Result persistAndReadAfterAdapterRestart(DeviceGraphRelationPort deviceOwner,
                                                            Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                            DeviceGraphRelationGrant relationGrant,
                                                            DeviceGraphRelationGrant revalidationGrant,
                                                            DeviceGraphReadGrant deviceReadGrant,
                                                            List<String> ownerOccurrenceReferences,
                                                            String requestingPrincipal,
                                                            Instant admissionDeadline,
                                                            Instant readAt,
                                                            Runnable afterGraphWrite) throws Exception {
        if (ownerOccurrenceReferences == null || ownerOccurrenceReferences.isEmpty()
                || ownerOccurrenceReferences.stream().distinct().count() != ownerOccurrenceReferences.size()) {
            throw new IllegalArgumentException("GRAPH_VERTICAL_REQUIRES_DISTINCT_OWNER_REFERENCES");
        }
        GraphProjectionMongoFixture mongo = new GraphProjectionMongoFixture();
        try {
            mongo.start();
            MongoClient root = mongo.root();
            String databaseName = "graph_device_vertical_" + java.util.UUID.randomUUID()
                    .toString().replace("-", "");
            MongoClient schemaOwner = mongo.schemaOwner(root, databaseName);
            GraphProjectionSchema.bootstrapEmptyDatabase(schemaOwner.getDatabase(databaseName));
            MongoClient writerClient = mongo.writer(root, databaseName);
            MongoClient readerClient = mongo.reader(root, databaseName);

            AtomicReference<GraphIdentityScope> ownerScope = new AtomicReference<>();
            GraphRelationAuthority authority = authority(deviceOwner, relationGrant, deviceReadGrant,
                    requestingPrincipal, ownerScope);
            GraphPolicyAuthorizer graphPolicy = (principal, purpose, scope, deadline, now) -> {
                if (!requestingPrincipal.equals(principal) || !GraphEvidenceAdmission.PURPOSE.equals(purpose)
                        || deadline == null || !deadline.isAfter(now)) {
                    return GraphPolicyDecision.DENIED;
                }
                GraphIdentityScope resolved = ownerScope.get();
                return scope == null || resolved != null && resolved.equals(scope)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED;
            };
            Clock admissionClock = Clock.fixed(admissionDeadline.minusSeconds(5), ZoneOffset.UTC);
            GraphEvidenceAdmission admission = new GraphEvidenceAdmission(authority, graphPolicy, admissionClock);
            MongoGraphProjectionStore writer = new MongoGraphProjectionStore(writerClient, databaseName,
                    GraphProjectionSchema.WRITER, admission, admissionClock);
            List<GraphStoredEdge> persisted = new ArrayList<>(ownerOccurrenceReferences.size());
            List<String> appendStates = new ArrayList<>(ownerOccurrenceReferences.size());
            List<String> replayStates = new ArrayList<>(ownerOccurrenceReferences.size());
            for (String ownerReference : ownerOccurrenceReferences) {
                GraphAdmissionOutcome admitted = admission.admit(new GraphAdmissionRequest(ownerReference,
                        requestingPrincipal, admissionDeadline));
                if (admitted.state() != GraphAdmissionState.ADMITTED) {
                    throw new AssertionError("REAL_DEVICE_GRAPH_ADMISSION_FAILED:" + admitted.reason());
                }
                GraphAppendOutcome appended = writer.appendVerified(admitted.relation());
                if (appended.state() != GraphAppendState.APPENDED) {
                    throw new AssertionError("REAL_DEVICE_GRAPH_APPEND_FAILED:" + appended.state());
                }
                GraphAppendOutcome replayed = writer.appendVerified(admitted.relation());
                if (replayed.state() != GraphAppendState.EXISTING) {
                    throw new AssertionError("REAL_DEVICE_GRAPH_REPLAY_FAILED:" + replayed.state());
                }
                persisted.add(appended.storedEdge());
                appendStates.add(appended.state().name());
                replayStates.add(replayed.state().name());
            }
            writerClient.close();
            if (afterGraphWrite != null) { afterGraphWrite.run(); }

            MongoClient replayWriterClient = mongo.existingWriter(databaseName);
            DeviceGraphRelationPort postChangeOwner = restartedDeviceOwner.get();
            if (postChangeOwner == null || postChangeOwner == deviceOwner) {
                throw new AssertionError("REAL_DEVICE_OWNER_ADAPTER_WAS_NOT_RESTARTED_FOR_REPLAY");
            }
            GraphEvidenceAdmission postChangeAdmission = new GraphEvidenceAdmission(
                    authority(postChangeOwner, revalidationGrant, deviceReadGrant, requestingPrincipal, ownerScope),
                    graphPolicy, admissionClock);
            MongoGraphProjectionStore postChangeWriter = new MongoGraphProjectionStore(replayWriterClient, databaseName,
                    GraphProjectionSchema.WRITER, postChangeAdmission, admissionClock);
            List<String> postChangeReplayStates = new ArrayList<>(persisted.size());
            boolean replayPreservedEvidence = true;
            for (int index = 0; index < persisted.size(); index++) {
                GraphStoredEdge original = persisted.get(index);
                GraphAdmissionOutcome reauthorized = postChangeAdmission.admit(new GraphAdmissionRequest(
                        original.edge().evidence().ownerAssertionReference(), requestingPrincipal, admissionDeadline));
                if (reauthorized.state() != GraphAdmissionState.ADMITTED) {
                    throw new AssertionError("POST_CHANGE_OWNER_REAUTHORIZATION_FAILED:" + reauthorized.reason());
                }
                GraphAppendOutcome replay = postChangeWriter.appendVerified(reauthorized.relation());
                postChangeReplayStates.add(replay.state().name());
                replayPreservedEvidence &= replay.state() == GraphAppendState.EXISTING
                        && original.equals(replay.storedEdge());
            }
            replayWriterClient.close();

            MongoGraphProjectionStore restartedReaderStore = new MongoGraphProjectionStore(
                    readerClient.getDatabase(databaseName), GraphProjectionSchema.READER);
            DeviceGraphRelationPort freshDeviceOwner = postChangeOwner;
            GraphRelationAuthority restartedAuthority = authority(freshDeviceOwner, revalidationGrant, deviceReadGrant,
                    requestingPrincipal, ownerScope);
            GraphEvidenceRef evidence = persisted.getFirst().edge().evidence();
            Instant grantExpiresAt = persisted.stream().map(row -> row.edge().evidence().expiresAt())
                    .min(Instant::compareTo).orElseThrow();
            GraphPrivateReadGrant graphGrant = GraphPrivateReadGrant.testOnly(requestingPrincipal,
                    evidence.scope(), 1, grantExpiresAt);
            GraphPrivateReadGrantVerifier graphGrantVerifier = (grant, principal, scope, now) ->
                    grant.matches(principal, scope, now)
                            ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED;
            Clock readClock = Clock.fixed(readAt, ZoneOffset.UTC);
            GraphProjectionReader restartedReader = new GraphProjectionReader(restartedReaderStore, restartedAuthority,
                    graphGrantVerifier, GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(new byte[32]), readClock);
            List<GraphReadOutcome> reads = new ArrayList<>(persisted.size());
            for (GraphStoredEdge row : persisted) {
                GraphOccurrenceRef occurrence = row.edge().evidence().occurrence();
                reads.add(restartedReader.readAuthorizedOccurrenceNeighbors(requestingPrincipal, graphGrant,
                        evidence.scope(), occurrence, occurrence.eventTime().minus(Duration.ofHours(1)),
                        occurrence.eventTime().plusMillis(1), 10, null));
            }
            GraphNodeRef device = persisted.getFirst().edge().to();
            long storedRows = restartedReaderStore.findNeighbors(device, GraphQueryLimits.DEFAULT.maxEdges()).size();
            return new Result(List.copyOf(appendStates), List.copyOf(replayStates),
                    List.copyOf(postChangeReplayStates), replayPreservedEvidence, List.copyOf(reads), storedRows);
        } finally {
            mongo.close();
        }
    }

    public static LifecycleResult appendThenReconcileAfterOwnerChange(
            Supplier<DeviceGraphRelationPort> currentDeviceOwner,
            DeviceGraphRelationGrant relationGrant,
            DeviceGraphReadGrant admissionReadGrant,
            DeviceGraphReadGrant reconciliationReadGrant,
            String ownerOccurrenceReference,
            String requestingPrincipal,
            Instant admissionDeadline,
            Instant reconciliationTime,
            Runnable afterGraphAppend,
            Runnable afterFirstReconciliation) throws Exception {
        GraphProjectionMongoFixture mongo = new GraphProjectionMongoFixture();
        try {
            mongo.start();
            MongoClient root = mongo.root();
            String databaseName = "graph_device_reconcile_" + java.util.UUID.randomUUID()
                    .toString().replace("-", "");
            MongoClient schemaOwner = mongo.schemaOwner(root, databaseName);
            GraphProjectionSchema.bootstrapEmptyDatabase(schemaOwner.getDatabase(databaseName));
            MongoClient writerClient = mongo.writer(root, databaseName);
            MongoClient readerClient = mongo.reader(root, databaseName);
            AtomicReference<GraphIdentityScope> ownerScope = new AtomicReference<>();
            GraphRelationAuthority relationAuthority = authority(currentDeviceOwner, relationGrant, admissionReadGrant,
                    requestingPrincipal, ownerScope);
            GraphPolicyAuthorizer graphPolicy = (principal, purpose, scope, deadline, now) -> {
                if (!requestingPrincipal.equals(principal) || !GraphEvidenceAdmission.PURPOSE.equals(purpose)
                        || deadline == null || !deadline.isAfter(now)) {
                    return GraphPolicyDecision.DENIED;
                }
                GraphIdentityScope resolved = ownerScope.get();
                return scope == null || resolved != null && resolved.equals(scope)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED;
            };
            Clock admissionClock = Clock.fixed(admissionDeadline.minusSeconds(5), ZoneOffset.UTC);
            GraphEvidenceAdmission admission = new GraphEvidenceAdmission(relationAuthority, graphPolicy, admissionClock);
            MongoGraphProjectionStore writer = new MongoGraphProjectionStore(writerClient, databaseName,
                    GraphProjectionSchema.WRITER, admission, admissionClock);
            GraphAdmissionOutcome admitted = admission.admit(new GraphAdmissionRequest(ownerOccurrenceReference,
                    requestingPrincipal, admissionDeadline));
            if (admitted.state() != GraphAdmissionState.ADMITTED) {
                throw new AssertionError("REAL_DEVICE_GRAPH_ADMISSION_FAILED:" + admitted.reason());
            }
            GraphAppendOutcome appended = writer.appendVerified(admitted.relation());
            if (appended.state() != GraphAppendState.APPENDED) {
                throw new AssertionError("REAL_DEVICE_GRAPH_APPEND_FAILED:" + appended.state());
            }
            if (afterGraphAppend != null) { afterGraphAppend.run(); }

            GraphEvidenceRef evidence = appended.storedEdge().edge().evidence();
            GraphPolicyAuthorizer reconciliationPolicy = (principal, purpose, scope, deadline, now) -> {
                if (!GraphLifecycleReconciliationService.SERVER_PRINCIPAL.equals(principal)
                        || !GraphLifecycleReconciliationRequest.PURPOSE.equals(purpose)
                        || deadline == null || !deadline.isAfter(now)) {
                    return GraphPolicyDecision.DENIED;
                }
                GraphIdentityScope authorized = ownerScope.get();
                return authorized != null && authorized.equals(scope)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED;
            };
            GraphRelationAuthority reconciliationAuthority = authority(currentDeviceOwner, relationGrant,
                    reconciliationReadGrant, GraphLifecycleReconciliationService.SERVER_PRINCIPAL, ownerScope);
            Clock reconciliationClock = Clock.fixed(reconciliationTime, ZoneOffset.UTC);
            GraphEvidenceAdmission reconciliationAdmission = new GraphEvidenceAdmission(
                    reconciliationAuthority, reconciliationPolicy, reconciliationClock);
            MongoClient reconciliationWriterClient = mongo.existingWriter(databaseName);
            MongoGraphProjectionStore reconciliationWriter = new MongoGraphProjectionStore(reconciliationWriterClient,
                    databaseName, GraphProjectionSchema.WRITER, reconciliationAdmission, reconciliationClock);
            GraphLifecycleReconciliationService reconciler = new GraphLifecycleReconciliationService(
                    reconciliationWriter, evidence.scope());
            GraphAppendOutcome reconciled = reconciler.reconcile(evidence.occurrence(),
                    evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion(), reconciliationTime.plusSeconds(5));
            Document afterFirst = root.getDatabase(databaseName).getCollection(GraphProjectionSchema.COLLECTION).find().first();
            String lifecycleAfterFirst = afterFirst == null ? null
                    : afterFirst.get("lifecycle", Document.class).getString("state");
            reconciliationWriterClient.close();
            if (afterFirstReconciliation != null) { afterFirstReconciliation.run(); }

            Instant recoveryTime = reconciliationTime.plusSeconds(1);
            Clock recoveryClock = Clock.fixed(recoveryTime, ZoneOffset.UTC);
            GraphEvidenceAdmission recoveryAdmission = new GraphEvidenceAdmission(
                    reconciliationAuthority, reconciliationPolicy, recoveryClock);
            MongoClient recoveryWriterClient = mongo.existingWriter(databaseName);
            MongoGraphProjectionStore recoveryWriter = new MongoGraphProjectionStore(recoveryWriterClient,
                    databaseName, GraphProjectionSchema.WRITER, recoveryAdmission, recoveryClock);
            GraphLifecycleReconciliationService recoveryReconciler = new GraphLifecycleReconciliationService(
                    recoveryWriter, evidence.scope());
            GraphAppendOutcome recovered = recoveryReconciler.reconcile(evidence.occurrence(),
                    evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion(), recoveryTime.plusSeconds(5));
            recoveryWriterClient.close();

            GraphEvidenceAdmission replayAdmission = new GraphEvidenceAdmission(
                    authority(currentDeviceOwner, relationGrant, admissionReadGrant, requestingPrincipal, ownerScope),
                    graphPolicy, recoveryClock);
            GraphAdmissionOutcome replay = replayAdmission.admit(new GraphAdmissionRequest(ownerOccurrenceReference,
                    requestingPrincipal, recoveryTime.plusSeconds(5)));
            MongoGraphProjectionStore restartedReaderStore = new MongoGraphProjectionStore(
                    readerClient.getDatabase(databaseName), GraphProjectionSchema.READER);
            GraphPrivateReadGrant graphGrant = GraphPrivateReadGrant.testOnly(requestingPrincipal,
                    evidence.scope(), 1, evidence.expiresAt());
            GraphProjectionReader restartedReader = new GraphProjectionReader(restartedReaderStore,
                    authority(currentDeviceOwner, relationGrant, admissionReadGrant, requestingPrincipal, ownerScope),
                    (grant, principal, scope, now) -> grant.matches(principal, scope, now)
                            ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED,
                    GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(new byte[32]),
                    Clock.fixed(recoveryTime.plusSeconds(1), ZoneOffset.UTC));
            GraphReadOutcome readAfterFence = restartedReader.readAuthorizedOccurrenceNeighbors(requestingPrincipal,
                    graphGrant, evidence.scope(), evidence.occurrence(), evidence.occurrence().eventTime().minus(Duration.ofHours(1)),
                    evidence.occurrence().eventTime().plusMillis(1), GraphQueryLimits.DEFAULT.maxPageSize(), null);
            Document row = root.getDatabase(databaseName).getCollection(GraphProjectionSchema.COLLECTION).find().first();
            GraphStoredEdge stored = row == null ? null : GraphProjectionDocumentCodec.decode(row);
            return new LifecycleResult(appended.state().name(), reconciled.state().name(), reconciled.reason(),
                    lifecycleAfterFirst, recovered.state().name(), recovered.reason(),
                    stored == null ? null : stored.lifecycle().name(),
                    root.getDatabase(databaseName).getCollection(GraphProjectionSchema.COLLECTION).countDocuments(),
                    replay.state().name(), readAfterFence.state().name(), readAfterFence.edges().size(),
                    stored != null && evidence.equals(stored.edge().evidence()));
        } finally {
            mongo.close();
        }
    }

    private static GraphRelationAuthority authority(DeviceGraphRelationPort deviceOwner,
                                                     DeviceGraphRelationGrant relationGrant,
                                                     DeviceGraphReadGrant deviceReadGrant,
                                                     String requestingPrincipal,
                                                     AtomicReference<GraphIdentityScope> ownerScope) {
        return authority(() -> deviceOwner, relationGrant, deviceReadGrant, requestingPrincipal, ownerScope);
    }

    private static GraphRelationAuthority authority(Supplier<DeviceGraphRelationPort> currentDeviceOwner,
                                                     DeviceGraphRelationGrant relationGrant,
                                                     DeviceGraphReadGrant deviceReadGrant,
                                                     String requestingPrincipal,
                                                     AtomicReference<GraphIdentityScope> ownerScope) {
        return new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String reference) {
                return graphResolution(currentDeviceOwner.get().admit(reference, relationGrant), ownerScope);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                if (!requestingPrincipal.equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                return graphResolution(currentDeviceOwner.get().resolve(expected, relationGrant,
                        deviceReadGrant, principal), ownerScope);
            }
        };
    }

    private static GraphRelationResolution graphResolution(DeviceGraphRelationResolution result,
                                                          AtomicReference<GraphIdentityScope> ownerScope) {
        if (result == null) { return GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        return switch (result.state()) {
            case VERIFIED -> {
                VerifiedDeviceGraphRelation relation = result.relation().orElseThrow();
                ownerScope.set(relation.reference().scope());
                yield GraphRelationResolution.verified(relation.reference(), relation.validUntil());
            }
            case PENDING -> GraphRelationResolution.pending(result.reason());
            case UNAVAILABLE -> GraphRelationResolution.unavailable(result.reason());
            case DENIED -> GraphRelationResolution.denied(result.reason());
            case CONFLICT -> GraphRelationResolution.conflict();
        };
    }

    public record Result(List<String> appendStates, List<String> replayStates, List<String> postChangeReplayStates,
                         boolean replayPreservedEvidence, List<GraphReadOutcome> readOutcomes, long persistedRows) { }

    public record LifecycleResult(String appendState, String reconciliationState, GraphReadReason reason,
                                  String lifecycleAfterFirstReconciliation, String recoveryState,
                                  GraphReadReason recoveryReason, String lifecycle, long persistedRows,
                                  String replayAdmissionState, String readStateAfterFence, int edgesAfterFence,
                                  boolean immutableEvidencePreserved) { }
}
