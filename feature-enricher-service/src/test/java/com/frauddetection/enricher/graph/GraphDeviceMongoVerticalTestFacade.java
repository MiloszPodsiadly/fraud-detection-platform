package com.frauddetection.enricher.graph;

import com.frauddetection.enricher.device.DeviceGraphReadGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationResolution;
import com.frauddetection.enricher.device.DeviceGraphRelationGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationPort;
import com.frauddetection.enricher.device.VerifiedDeviceGraphRelation;
import com.mongodb.client.MongoClient;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

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

    public static Result persistAndReadTwoHopAfterAdapterRestart(DeviceGraphRelationPort deviceOwner,
                                                                  Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                                  DeviceGraphRelationGrant relationGrant,
                                                                  DeviceGraphReadGrant deviceReadGrant,
                                                                  List<String> ownerOccurrenceReferences,
                                                                  String requestingPrincipal,
                                                                  Instant admissionDeadline,
                                                                  Instant readAt) throws Exception {
        return persistAndReadTwoHopAfterAdapterRestart(deviceOwner, restartedDeviceOwner, relationGrant,
                deviceReadGrant, ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt, () -> { });
    }

    public static Result persistAndReadTwoHopAfterAdapterRestart(DeviceGraphRelationPort deviceOwner,
                                                                  Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                                  DeviceGraphRelationGrant relationGrant,
                                                                  DeviceGraphReadGrant deviceReadGrant,
                                                                  List<String> ownerOccurrenceReferences,
                                                                  String requestingPrincipal,
                                                                  Instant admissionDeadline,
                                                                  Instant readAt,
                                                                  Runnable afterRecoveryBeforeFinalRead) throws Exception {
        return persistAndRead(deviceOwner, restartedDeviceOwner, relationGrant, relationGrant, deviceReadGrant,
                ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt, () -> { }, true,
                afterRecoveryBeforeFinalRead, null, null, true, GraphQueryLimits.DEFAULT);
    }

    /** Keeps the authenticated Graph reader and signed owner clients live through the callback. */
    public static Result persistAndReadTwoHopInLiveSession(DeviceGraphRelationPort deviceOwner,
                                                            Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                            DeviceGraphRelationGrant relationGrant,
                                                            DeviceGraphReadGrant deviceReadGrant,
                                                            List<String> ownerOccurrenceReferences,
                                                            String requestingPrincipal,
                                                            Instant admissionDeadline,
                                                            Instant readAt,
                                                            Consumer<LiveTwoHopReadSession> liveSessionCallback,
                                                            Runnable admitSignedConflict) throws Exception {
        return persistAndReadTwoHopInLiveSession(deviceOwner, restartedDeviceOwner, relationGrant, deviceReadGrant,
                ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt, liveSessionCallback,
                admitSignedConflict, true, GraphQueryLimits.DEFAULT);
    }

    /** Dense signed-owner workload skips per-row setup reads so callback counters describe only the measured pass. */
    public static Result persistAndReadTwoHopDenseInLiveSession(DeviceGraphRelationPort deviceOwner,
                                                                  Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                                  DeviceGraphRelationGrant relationGrant,
                                                                  DeviceGraphReadGrant deviceReadGrant,
                                                                  List<String> ownerOccurrenceReferences,
                                                                  String requestingPrincipal,
                                                                  Instant admissionDeadline,
                                                                  Instant readAt,
                                                                  Consumer<LiveTwoHopReadSession> liveSessionCallback,
                                                                  Runnable admitSignedConflict) throws Exception {
        return persistAndReadTwoHopDenseInLiveSession(deviceOwner, restartedDeviceOwner, relationGrant,
                deviceReadGrant, ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt,
                liveSessionCallback, admitSignedConflict, GraphQueryLimits.DEFAULT);
    }

    /** Test-only deadline override lets the signed scorer return a typed Graph cutoff before its outer deadline. */
    public static Result persistAndReadTwoHopDenseInLiveSession(DeviceGraphRelationPort deviceOwner,
                                                                  Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                                  DeviceGraphRelationGrant relationGrant,
                                                                  DeviceGraphReadGrant deviceReadGrant,
                                                                  List<String> ownerOccurrenceReferences,
                                                                  String requestingPrincipal,
                                                                  Instant admissionDeadline,
                                                                  Instant readAt,
                                                                  Consumer<LiveTwoHopReadSession> liveSessionCallback,
                                                                  Runnable admitSignedConflict,
                                                                  GraphQueryLimits readLimits) throws Exception {
        return persistAndReadTwoHopInLiveSession(deviceOwner, restartedDeviceOwner, relationGrant, deviceReadGrant,
                ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt, liveSessionCallback,
                admitSignedConflict, false, readLimits);
    }

    private static Result persistAndReadTwoHopInLiveSession(DeviceGraphRelationPort deviceOwner,
                                                              Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                                              DeviceGraphRelationGrant relationGrant,
                                                              DeviceGraphReadGrant deviceReadGrant,
                                                              List<String> ownerOccurrenceReferences,
                                                              String requestingPrincipal,
                                                              Instant admissionDeadline,
                                                              Instant readAt,
                                                              Consumer<LiveTwoHopReadSession> liveSessionCallback,
                                                              Runnable admitSignedConflict,
                                                              boolean readOccurrenceNeighbors,
                                                              GraphQueryLimits readLimits) throws Exception {
        return persistAndRead(deviceOwner, restartedDeviceOwner, relationGrant, relationGrant, deviceReadGrant,
                ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt, () -> { }, true,
                () -> { }, liveSessionCallback, admitSignedConflict, readOccurrenceNeighbors, readLimits);
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
        return persistAndRead(deviceOwner, restartedDeviceOwner, relationGrant, revalidationGrant, deviceReadGrant,
                ownerOccurrenceReferences, requestingPrincipal, admissionDeadline, readAt, afterGraphWrite, false,
                () -> { }, null, null, true, GraphQueryLimits.DEFAULT);
    }

    private static Result persistAndRead(DeviceGraphRelationPort deviceOwner,
                                         Supplier<DeviceGraphRelationPort> restartedDeviceOwner,
                                         DeviceGraphRelationGrant relationGrant,
                                         DeviceGraphRelationGrant revalidationGrant,
                                         DeviceGraphReadGrant deviceReadGrant,
                                         List<String> ownerOccurrenceReferences,
                                         String requestingPrincipal,
                                         Instant admissionDeadline,
                                         Instant readAt,
                                         Runnable afterGraphWrite,
                                         boolean readTwoHop,
                                         Runnable afterRecoveryBeforeFinalRead,
                                         Consumer<LiveTwoHopReadSession> liveSessionCallback,
                                         Runnable admitSignedConflict,
                                         boolean readOccurrenceNeighbors,
                                         GraphQueryLimits readLimits) throws Exception {
        if (ownerOccurrenceReferences == null || ownerOccurrenceReferences.isEmpty()
                || ownerOccurrenceReferences.stream().distinct().count() != ownerOccurrenceReferences.size()
                || readLimits == null) {
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
            GraphCommandProbe graphCommands = new GraphCommandProbe();
            mongo.reader(root, databaseName).close(); // Creates the least-privilege reader principal before monitoring it.
            MongoClient readerClient = mongo.monitoredReader(databaseName, graphCommands);

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
            AtomicBoolean ownerAvailable = new AtomicBoolean(true);
            DeviceGraphRelationPort boundedReadOwner = withTransientOutage(freshDeviceOwner, ownerAvailable);
            AtomicInteger ownerRevalidations = new AtomicInteger();
            OwnerReadProbe ownerProbe = new OwnerReadProbe(persisted.getLast().edge().evidence().occurrence(),
                    expectedOwnerPassReferences(persisted));
            GraphRelationAuthority restartedAuthority = authority(() -> boundedReadOwner, revalidationGrant, deviceReadGrant,
                    requestingPrincipal, ownerScope, ownerRevalidations, ownerProbe);
            GraphEvidenceRef evidence = persisted.getFirst().edge().evidence();
            Instant grantExpiresAt = persisted.stream().map(row -> row.edge().evidence().expiresAt())
                    .min(Instant::compareTo).orElseThrow();
            GraphPrivateReadGrant graphGrant = GraphPrivateReadGrant.testOnly(requestingPrincipal,
                    evidence.scope(), 1, grantExpiresAt);
            GraphPrivateReadGrantVerifier graphGrantVerifier = (grant, principal, scope, now) ->
                    grant.matches(principal, scope, now)
                            ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED;
            AtomicReference<Instant> readClockNow = new AtomicReference<>(readAt);
            Clock readClock = new MutableTestClock(readClockNow);
            GraphProjectionReader restartedReader = new GraphProjectionReader(restartedReaderStore, restartedAuthority,
                    graphGrantVerifier, readLimits, new GraphReadCursorCodec(new byte[32]), readClock);
            List<GraphReadOutcome> reads = new ArrayList<>(persisted.size());
            if (readOccurrenceNeighbors) {
                for (GraphStoredEdge row : persisted) {
                    GraphOccurrenceRef occurrence = row.edge().evidence().occurrence();
                    reads.add(restartedReader.readAuthorizedOccurrenceNeighbors(requestingPrincipal, graphGrant,
                            evidence.scope(), occurrence, occurrence.eventTime().minus(Duration.ofHours(1)),
                            occurrence.eventTime().plusMillis(1), 10, null));
                }
            }
            TwoHopRead twoHopRead = null;
            TwoHopRead outageRead = null;
            TwoHopRead recoveredRead = null;
            TwoHopRead conflictRead = null;
            List<OwnerCheck> initialTwoHopOwnerChecks = List.of();
            if (readTwoHop) {
                GraphOccurrenceRef rootOccurrence = persisted.getLast().edge().evidence().occurrence();
                ownerProbe.beginRead();
                try {
                    twoHopRead = TwoHopRead.from(restartedReader.readAuthorizedOccurrenceDeviceWitnesses(
                            requestingPrincipal, graphGrant, evidence.scope(), rootOccurrence));
                } finally {
                    ownerProbe.endRead();
                }
                initialTwoHopOwnerChecks = ownerProbe.snapshot().readTraces().getFirst();
                ownerAvailable.set(false);
                outageRead = TwoHopRead.from(restartedReader.readAuthorizedOccurrenceDeviceWitnesses(
                        requestingPrincipal, graphGrant, evidence.scope(), rootOccurrence));
                ownerAvailable.set(true);
                recoveredRead = TwoHopRead.from(restartedReader.readAuthorizedOccurrenceDeviceWitnesses(
                        requestingPrincipal, graphGrant, evidence.scope(), rootOccurrence));
                if (liveSessionCallback != null) {
                    LiveTwoHopReadSession session = new LiveTwoHopReadSession(restartedReader, requestingPrincipal,
                            graphGrant, evidence.scope(), rootOccurrence, ownerRevalidations, admitSignedConflict,
                            graphCommands, ownerProbe, readClockNow);
                    liveSessionCallback.accept(session);
                    session.admitSignedConflictIfNeeded();
                } else {
                    afterRecoveryBeforeFinalRead.run();
                    if (admitSignedConflict != null) { admitSignedConflict.run(); }
                }
                conflictRead = TwoHopRead.from(restartedReader.readAuthorizedOccurrenceDeviceWitnesses(
                        requestingPrincipal, graphGrant, evidence.scope(), rootOccurrence));
            }
            GraphNodeRef device = persisted.getFirst().edge().to();
            long storedRows = restartedReaderStore.findNeighbors(device, GraphQueryLimits.DEFAULT.maxEdges()).size();
            return new Result(List.copyOf(appendStates), List.copyOf(replayStates),
                    List.copyOf(postChangeReplayStates), replayPreservedEvidence, List.copyOf(reads), storedRows,
                    twoHopRead, initialTwoHopOwnerChecks, outageRead, recoveredRead, conflictRead);
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
        return authority(currentDeviceOwner, relationGrant, deviceReadGrant, requestingPrincipal, ownerScope,
                new AtomicInteger());
    }

    private static GraphRelationAuthority authority(Supplier<DeviceGraphRelationPort> currentDeviceOwner,
                                                     DeviceGraphRelationGrant relationGrant,
                                                     DeviceGraphReadGrant deviceReadGrant,
                                                     String requestingPrincipal,
                                                     AtomicReference<GraphIdentityScope> ownerScope,
                                                     AtomicInteger ownerRevalidations) {
        return authority(currentDeviceOwner, relationGrant, deviceReadGrant, requestingPrincipal, ownerScope,
                ownerRevalidations, new OwnerReadProbe(null));
    }

    private static List<String> expectedOwnerPassReferences(List<GraphStoredEdge> persisted) {
        if (persisted.isEmpty()) { return List.of(); }
        GraphStoredEdge root = persisted.getLast();
        List<GraphStoredEdge> priors = new ArrayList<>(persisted.subList(0, persisted.size() - 1));
        priors.sort(Comparator.comparing((GraphStoredEdge row) -> row.edge().to().identityToken())
                .thenComparingInt(row -> row.edge().to().pseudonymKeyVersion())
                .thenComparing(row -> row.edge().evidence().occurrence().eventTime())
                .thenComparing(row -> row.edge().evidence().occurrence().occurrenceToken()));
        List<String> references = new ArrayList<>(persisted.size());
        references.add(root.edge().evidence().ownerAssertionReference());
        priors.stream().map(row -> row.edge().evidence().ownerAssertionReference()).forEach(references::add);
        return List.copyOf(references);
    }

    private static GraphRelationAuthority authority(Supplier<DeviceGraphRelationPort> currentDeviceOwner,
                                                     DeviceGraphRelationGrant relationGrant,
                                                     DeviceGraphReadGrant deviceReadGrant,
                                                     String requestingPrincipal,
                                                     AtomicReference<GraphIdentityScope> ownerScope,
                                                     AtomicInteger ownerRevalidations,
                                                     OwnerReadProbe ownerProbe) {
        return new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String reference) {
                return graphResolution(currentDeviceOwner.get().admit(reference, relationGrant), ownerScope);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                ownerRevalidations.incrementAndGet();
                if (!requestingPrincipal.equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                GraphRelationResolution result = graphResolution(currentDeviceOwner.get().resolve(expected,
                        relationGrant, deviceReadGrant, principal), ownerScope);
                ownerProbe.record(expected, result);
                return result;
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal,
                                                                  Duration remainingBudget) {
                ownerRevalidations.incrementAndGet();
                if (!requestingPrincipal.equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                GraphRelationResolution result = graphResolution(currentDeviceOwner.get().resolve(expected,
                        relationGrant, deviceReadGrant, principal, remainingBudget), ownerScope);
                ownerProbe.record(expected, result);
                return result;
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

    private static DeviceGraphRelationPort withTransientOutage(DeviceGraphRelationPort delegate,
                                                               AtomicBoolean available) {
        return new DeviceGraphRelationPort() {
            @Override public DeviceGraphRelationResolution admit(String ownerReference,
                                                                 DeviceGraphRelationGrant grant) {
                return delegate.admit(ownerReference, grant);
            }

            @Override public DeviceGraphRelationResolution resolve(GraphEvidenceRef reference,
                                                                    DeviceGraphRelationGrant relationGrant,
                                                                    DeviceGraphReadGrant readGrant,
                                                                    String principal) {
                return delegate.resolve(reference, relationGrant, readGrant, principal);
            }

            @Override public DeviceGraphRelationResolution resolve(GraphEvidenceRef reference,
                                                                    DeviceGraphRelationGrant relationGrant,
                                                                    DeviceGraphReadGrant readGrant,
                                                                    String principal, Duration remainingBudget) {
                return available.get() ? delegate.resolve(reference, relationGrant, readGrant, principal,
                        remainingBudget) : DeviceGraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
            }
        };
    }

    public record Result(List<String> appendStates, List<String> replayStates, List<String> postChangeReplayStates,
                         boolean replayPreservedEvidence, List<GraphReadOutcome> readOutcomes, long persistedRows,
                         TwoHopRead twoHopRead, List<OwnerCheck> initialTwoHopOwnerChecks,
                         TwoHopRead outageRead, TwoHopRead recoveredRead,
                         TwoHopRead conflictRead) { }

    public record TwoHopRead(String state, String reason, Instant rootTime, Instant priorTime,
                             int pseudonymKeyVersion, Instant graphAssociationAdmittedAt,
                             Instant diagnosticReadAt, GraphOccurrenceRef rootOccurrence,
                             GraphOccurrenceRef priorOccurrence, String stableWitnessCommitment) {
        static TwoHopRead from(GraphDeviceWitnessOutcome outcome) {
            var witness = outcome.witness().orElse(null);
            return new TwoHopRead(outcome.state().name(), outcome.reason().name(),
                    witness == null ? null : witness.root().eventTime(),
                    witness == null ? null : witness.prior().eventTime(),
                    witness == null ? 0 : witness.pseudonymKeyVersion(),
                    witness == null ? null : witness.graphAssociationAdmittedAt(),
                    witness == null ? null : witness.diagnosticReadAt(),
                    witness == null ? null : witness.root(), witness == null ? null : witness.prior(),
                    witness == null ? null : witness.stableWitnessCommitment());
        }

        @Override public String toString() {
            return "TwoHopRead[" + state + "," + reason + ",witness=redacted]";
        }
    }

    public record GraphReadMetrics(long findCommands, long getMoreCommands, long documentsReturned) { }

    public record OwnerCheck(String ownerAssertionReference, boolean root, String state, String reason) {
        @Override public String toString() {
            return "OwnerCheck[root=" + root + ",state=" + state + ",reason=" + reason + ",reference=redacted]";
        }
    }

    public record OwnerValidationMetrics(List<List<OwnerCheck>> readTraces,
                                         List<String> expectedPassReferences) {
        public OwnerValidationMetrics {
            readTraces = readTraces.stream().map(List::copyOf).toList();
            expectedPassReferences = List.copyOf(expectedPassReferences);
        }

        public long totalChecks() {
            return readTraces.stream().mapToLong(List::size).sum();
        }
    }

    /** Test-only authorized reader. Its lifetime is bounded by the fixture callback. */
    public static final class LiveTwoHopReadSession {
        private final GraphProjectionReader reader;
        private final String principal;
        private final GraphPrivateReadGrant grant;
        private final GraphIdentityScope scope;
        private final GraphOccurrenceRef rootOccurrence;
        private final AtomicInteger ownerRevalidations;
        private final Runnable signedConflictAdmission;
        private final GraphCommandProbe graphCommands;
        private final OwnerReadProbe ownerProbe;
        private final AtomicReference<Instant> readClockNow;
        private final AtomicBoolean conflictAdmitted = new AtomicBoolean();

        private LiveTwoHopReadSession(GraphProjectionReader reader, String principal,
                                      GraphPrivateReadGrant grant, GraphIdentityScope scope,
                                      GraphOccurrenceRef rootOccurrence, AtomicInteger ownerRevalidations,
                                      Runnable signedConflictAdmission, GraphCommandProbe graphCommands,
                                      OwnerReadProbe ownerProbe, AtomicReference<Instant> readClockNow) {
            this.reader = reader;
            this.principal = principal;
            this.grant = grant;
            this.scope = scope;
            this.rootOccurrence = rootOccurrence;
            this.ownerRevalidations = ownerRevalidations;
            this.signedConflictAdmission = signedConflictAdmission;
            this.graphCommands = graphCommands;
            this.ownerProbe = ownerProbe;
            this.readClockNow = readClockNow;
        }

        public TwoHopRead readCurrent() { return readAs(principal); }

        public TwoHopRead readAs(String requestingPrincipal) {
            ownerProbe.beginRead();
            try {
                return TwoHopRead.from(reader.readAuthorizedOccurrenceDeviceWitnesses(
                        requestingPrincipal, grant, scope, rootOccurrence));
            } finally {
                ownerProbe.endRead();
            }
        }

        public GraphOccurrenceRef rootOccurrence() { return rootOccurrence; }

        public void advanceReaderClockBy(Duration amount) {
            if (amount == null || amount.isNegative() || amount.isZero()
                    || amount.toNanos() % Duration.ofMillis(1).toNanos() != 0) {
                throw new IllegalArgumentException("GRAPH_TEST_CLOCK_ADVANCE_INVALID");
            }
            readClockNow.updateAndGet(current -> current.plus(amount));
        }

        public int ownerRevalidationCount() { return ownerRevalidations.get(); }

        public void resetMeasurementMetrics() {
            ownerRevalidations.set(0);
            graphCommands.reset();
            ownerProbe.reset();
        }

        public GraphReadMetrics graphReadMetrics() { return graphCommands.snapshot(); }
        public OwnerValidationMetrics ownerValidationMetrics() { return ownerProbe.snapshot(); }
        public boolean awaitReadDrain(Duration timeout) {
            try {
                return ownerProbe.awaitIdle(timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        public void admitSignedConflict() {
            if (!conflictAdmitted.compareAndSet(false, true)) {
                throw new IllegalStateException("SIGNED_GRAPH_CONFLICT_ALREADY_ADMITTED");
            }
            if (signedConflictAdmission == null) {
                throw new IllegalStateException("SIGNED_GRAPH_CONFLICT_ADMISSION_REQUIRED");
            }
            signedConflictAdmission.run();
        }

        private void admitSignedConflictIfNeeded() {
            if (!conflictAdmitted.get()) { admitSignedConflict(); }
        }
    }

    private static final class MutableTestClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableTestClock(AtomicReference<Instant> now) { this.now = now; }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) { throw new IllegalArgumentException("GRAPH_TEST_CLOCK_ZONE_INVALID"); }
            return this;
        }
        @Override public Instant instant() { return now.get(); }
    }

    private static final class OwnerReadProbe {
        private static final int MAX_CAPTURED_READS = 16;
        private static final int MAX_CHECKS_PER_READ = 2 * GraphQueryLimits.DEFAULT.maxEdges();
        private final GraphOccurrenceRef root;
        private final List<String> expectedPassReferences;
        private final List<List<OwnerCheck>> readTraces = new ArrayList<>();
        private List<OwnerCheck> activeRead;

        OwnerReadProbe(GraphOccurrenceRef root) { this(root, List.of()); }

        OwnerReadProbe(GraphOccurrenceRef root, List<String> expectedPassReferences) {
            this.root = root;
            this.expectedPassReferences = List.copyOf(expectedPassReferences);
        }

        synchronized void beginRead() {
            if (activeRead != null || readTraces.size() >= MAX_CAPTURED_READS) {
                throw new IllegalStateException("TEST_OWNER_TRACE_BOUND_EXCEEDED");
            }
            activeRead = new ArrayList<>();
        }

        synchronized void record(GraphEvidenceRef evidence, GraphRelationResolution result) {
            if (activeRead == null) { return; }
            if (activeRead.size() >= MAX_CHECKS_PER_READ) {
                throw new IllegalStateException("TEST_OWNER_TRACE_BOUND_EXCEEDED");
            }
            activeRead.add(new OwnerCheck(evidence.ownerAssertionReference(),
                    root != null && root.equals(evidence.occurrence()), result.state().name(), result.reason().name()));
        }

        synchronized void endRead() {
            if (activeRead == null) { throw new IllegalStateException("TEST_OWNER_TRACE_READ_NOT_STARTED"); }
            readTraces.add(List.copyOf(activeRead));
            activeRead = null;
            notifyAll();
        }

        synchronized void reset() {
            if (activeRead != null) { throw new IllegalStateException("TEST_OWNER_TRACE_READ_STILL_ACTIVE"); }
            readTraces.clear();
        }

        synchronized OwnerValidationMetrics snapshot() {
            return new OwnerValidationMetrics(List.copyOf(readTraces), expectedPassReferences);
        }

        synchronized boolean awaitIdle(Duration timeout) throws InterruptedException {
            if (timeout == null || timeout.isNegative()) {
                throw new IllegalArgumentException("TEST_OWNER_TRACE_TIMEOUT_INVALID");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (activeRead != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { return false; }
                long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining);
                int nanos = (int) (remaining - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
                wait(millis, nanos);
            }
            return true;
        }
    }

    private static final class GraphCommandProbe implements CommandListener {
        private final AtomicInteger finds = new AtomicInteger();
        private final AtomicInteger getMores = new AtomicInteger();
        private final LongAdder documents = new LongAdder();

        @Override public void commandStarted(CommandStartedEvent event) {
            if ("find".equals(event.getCommandName())) { finds.incrementAndGet(); }
            if ("getMore".equals(event.getCommandName())) { getMores.incrementAndGet(); }
        }

        @Override public void commandSucceeded(CommandSucceededEvent event) {
            var cursorValue = event.getResponse().get("cursor");
            if (cursorValue == null || !cursorValue.isDocument()) { return; }
            var cursor = cursorValue.asDocument();
            var batch = cursor.get("firstBatch");
            if (batch == null) { batch = cursor.get("nextBatch"); }
            if (batch != null && batch.isArray()) { documents.add(batch.asArray().size()); }
        }

        void reset() {
            finds.set(0);
            getMores.set(0);
            documents.reset();
        }

        GraphReadMetrics snapshot() { return new GraphReadMetrics(finds.get(), getMores.get(), documents.sum()); }
    }

    public record LifecycleResult(String appendState, String reconciliationState, GraphReadReason reason,
                                  String lifecycleAfterFirstReconciliation, String recoveryState,
                                  GraphReadReason recoveryReason, String lifecycle, long persistedRows,
                                  String replayAdmissionState, String readStateAfterFence, int edgesAfterFence,
                                  boolean immutableEvidencePreserved) { }
}
