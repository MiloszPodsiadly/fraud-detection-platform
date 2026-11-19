package com.frauddetection.enricher.graph;

import com.frauddetection.enricher.device.DeviceGraphReadGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationPort;
import com.frauddetection.enricher.device.VerifiedDeviceGraphRelation;
import com.mongodb.client.MongoClient;

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
        if (ownerOccurrenceReferences == null || ownerOccurrenceReferences.size() != 2
                || ownerOccurrenceReferences.stream().distinct().count() != 2) {
            throw new IllegalArgumentException("GRAPH_VERTICAL_REQUIRES_TWO_DISTINCT_OWNER_REFERENCES");
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

            MongoGraphProjectionStore restartedReaderStore = new MongoGraphProjectionStore(
                    readerClient.getDatabase(databaseName), GraphProjectionSchema.READER);
            DeviceGraphRelationPort freshDeviceOwner = restartedDeviceOwner.get();
            if (freshDeviceOwner == null || freshDeviceOwner == deviceOwner) {
                throw new AssertionError("REAL_DEVICE_OWNER_ADAPTER_WAS_NOT_RESTARTED");
            }
            GraphRelationAuthority restartedAuthority = authority(freshDeviceOwner, relationGrant, deviceReadGrant,
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
            return new Result(List.copyOf(appendStates), List.copyOf(replayStates), List.copyOf(reads), storedRows);
        } finally {
            mongo.close();
        }
    }

    private static GraphRelationAuthority authority(DeviceGraphRelationPort deviceOwner,
                                                     DeviceGraphRelationGrant relationGrant,
                                                     DeviceGraphReadGrant deviceReadGrant,
                                                     String requestingPrincipal,
                                                     AtomicReference<GraphIdentityScope> ownerScope) {
        return new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String reference) {
                return deviceOwner.admit(reference, relationGrant)
                        .map(relation -> {
                            ownerScope.set(relation.reference().scope());
                            return verified(relation);
                        })
                        .orElseGet(() -> GraphRelationResolution.denied(GraphReadReason.REFERENCE_INVALID));
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                if (!requestingPrincipal.equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                return deviceOwner.resolve(expected, relationGrant, deviceReadGrant, principal)
                        .map(GraphDeviceMongoVerticalTestFacade::verified)
                        .orElseGet(() -> GraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED));
            }
        };
    }

    private static GraphRelationResolution verified(VerifiedDeviceGraphRelation relation) {
        return GraphRelationResolution.verified(relation.reference(), relation.validUntil());
    }

    public record Result(List<String> appendStates, List<String> replayStates, List<GraphReadOutcome> readOutcomes,
                         long persistedRows) { }
}
