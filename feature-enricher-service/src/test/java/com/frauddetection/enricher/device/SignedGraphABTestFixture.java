package com.frauddetection.enricher.device;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade;
import com.mongodb.client.MongoClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Test-only signed Source + Device + Kafka + Graph fixture for the scorer adapter proof. */
public final class SignedGraphABTestFixture {
    private SignedGraphABTestFixture() { }

    public static Evidence create() throws Exception {
        PrivateDeviceDiagnosticReaderIntegrationTest.start();
        PrivateDeviceDiagnosticReaderIntegrationTest sourceDevice = new PrivateDeviceDiagnosticReaderIntegrationTest();
        List<MongoClient> graphOwnerClients = new ArrayList<>();
        boolean initialized = false;
        try {
            sourceDevice.setUp();
            initialized = true;
            VerifiedDeviceAdmissionIntegrationTest f = sourceDevice.f;
            Instant priorTime = VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(60);
            Instant rootTime = VerifiedDeviceAdmissionIntegrationTest.NOW;
            var prior = f.event(f.A, "graph-e2e-A-" + UUID.randomUUID(), "graph-e2e-transaction-A",
                    "customer-1", "graph-e2e-device", 1, priorTime);
            var root = f.event(f.A, "graph-e2e-B-" + UUID.randomUUID(), "graph-e2e-transaction-B",
                    "customer-1", "graph-e2e-device", 2, rootTime);

            var admission = f.adapter(f.A);
            var priorTicket = f.stage(admission, prior);
            var rootTicket = f.stage(admission, root);
            var coverage = f.coverage(f.A, 2, f.A.start, rootTime);
            admission.seal(priorTicket, coverage.proof(), coverage.payload());
            admission.seal(rootTicket, coverage.proof(), coverage.payload());
            admission.commit(priorTicket);
            admission.commit(rootTicket);

            var priorScore = sourceDevice.publish(prior, "initial", "graph-e2e-score-A");
            admission.bindScored(priorTicket, sourceDevice.occurrenceReader, priorScore.reference());
            var rootScore = sourceDevice.publish(root, "initial", "graph-e2e-score-B");
            admission.bindScored(rootTicket, sourceDevice.occurrenceReader, rootScore.reference());

            String institutionToken = f.pseudonyms.institution(f.A.institution);
            String sourceCode = DevicePseudonyms.sourceCode("BANK");
            String priorOwnerReference = f.A.epoch().eventId(institutionToken, sourceCode,
                    f.pseudonyms.event(f.A.institution, prior.id()));
            String rootOwnerReference = f.A.epoch().eventId(institutionToken, sourceCode,
                    f.pseudonyms.event(f.A.institution, root.id()));
            var sourceScope = new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch);
            String principal = "graph_e2e_" + UUID.randomUUID().toString().replace("-", "");
            var relationGrant = DeviceGraphRelationGrant.testOnly(
                    DeviceEpoch.digest("test-graph-relation-grant", f.database), sourceScope,
                    "transaction-ingest", 1, VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
            var readGrant = DeviceGraphReadGrant.testOnly(principal, sourceScope, "transaction-ingest", 1,
                    VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));

            String initialDevicePrincipal = principal + "_initial";
            MongoClient initialDeviceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(
                    f.ownerClient, f.database, initialDevicePrincipal);
            graphOwnerClients.add(initialDeviceClient);
            DeviceGraphRelationPort initialOwner = owner(sourceDevice, initialDeviceClient, initialDevicePrincipal);
            AtomicInteger adapterReconstructions = new AtomicInteger();
            Supplier<DeviceGraphRelationPort> restartedOwner = () -> {
                adapterReconstructions.incrementAndGet();
                String sourcePrincipal = principal + "_source_restart";
                String devicePrincipal = principal + "_device_restart";
                MongoClient sourceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.occurrenceReader(
                        f.ownerClient, f.database, sourcePrincipal, List.of());
                MongoClient deviceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(
                        f.ownerClient, f.database, devicePrincipal);
                graphOwnerClients.add(sourceClient);
                graphOwnerClients.add(deviceClient);
                var occurrences = MongoSourceOccurrenceStore.readOnly(sourceClient, f.database, sourcePrincipal,
                        f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK, sourceDevice.occurrenceCipher,
                        new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE,
                                "transaction-ingest"));
                var lineage = new MongoSourceLineage(sourceClient, f.database, sourcePrincipal,
                        MongoSourceLineage.Stage.SCORER, f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
                return DeviceGraphRelationAuthority.testOnly(deviceClient, f.database, devicePrincipal,
                        f.A.epoch(), lineage, f.origins, f.grants, occurrences, f.pseudonyms,
                        VerifiedDeviceAdmissionIntegrationTest.CLOCK);
            };
            Runnable admitSignedSourceContradiction = () -> {
                var conflictingProof = sourceDevice.conflictingSignedProof(root);
                String ownerStatus = com.frauddetection.ingest.source.DeviceTestSourceReceipts
                        .admitConflictingSignedProof(f.ownerClient, f.database,
                                VerifiedDeviceAdmissionIntegrationTest.CLOCK, f.originVerifier, conflictingProof,
                                f.sourceReceiptCapacity);
                if (!"CONFLICT".equals(ownerStatus)) {
                    throw new IllegalStateException("SIGNED_GRAPH_CONTRADICTION_NOT_REJECTED");
                }
            };

            GraphDeviceMongoVerticalTestFacade.Result graph = GraphDeviceMongoVerticalTestFacade
                    .persistAndReadTwoHopAfterAdapterRestart(initialOwner, restartedOwner, relationGrant,
                            readGrant, List.of(priorOwnerReference, rootOwnerReference), principal,
                            rootTime.plusSeconds(5), rootTime.plusSeconds(6), admitSignedSourceContradiction);
            long deviceOccurrences = f.ownerClient.getDatabase(f.database)
                    .getCollection("device_occurrences").countDocuments();
            long scoredOccurrences = f.ownerClient.getDatabase(f.database)
                    .getCollection("source_scored_occurrences").countDocuments();
            return new Evidence(root.enriched(), root.enriched().transactionId(), root.enriched().correlationId(),
                    priorTime, rootTime, rootTime.plusSeconds(6), graph, deviceOccurrences, scoredOccurrences,
                    adapterReconstructions.get(), rootScore.event(), rootScore.wireBytes());
        } finally {
            graphOwnerClients.forEach(MongoClient::close);
            if (initialized) { sourceDevice.cleanUp(); }
            PrivateDeviceDiagnosticReaderIntegrationTest.stop();
        }
    }

    private static DeviceGraphRelationPort owner(PrivateDeviceDiagnosticReaderIntegrationTest sourceDevice,
                                                 MongoClient deviceClient, String principal) {
        VerifiedDeviceAdmissionIntegrationTest f = sourceDevice.f;
        return DeviceGraphRelationAuthority.testOnly(deviceClient, f.database, principal, f.A.epoch(),
                sourceDevice.privateSource, f.origins, f.grants, sourceDevice.occurrenceReader,
                f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
    }

    public record Evidence(TransactionEnrichedEvent rootTransaction, String rootTransactionId,
                           String rootCorrelationId, Instant priorTime, Instant rootTime, Instant readAt,
                           GraphDeviceMongoVerticalTestFacade.Result graph, long deviceOccurrenceRows,
                           long scoredOccurrenceRows, int adapterReconstructions,
                           TransactionScoredEvent rootScoredEvent, byte[] rootScoredWireBytes) {
        public Evidence {
            rootScoredWireBytes = rootScoredWireBytes.clone();
        }

        @Override public byte[] rootScoredWireBytes() { return rootScoredWireBytes.clone(); }
    }
}
