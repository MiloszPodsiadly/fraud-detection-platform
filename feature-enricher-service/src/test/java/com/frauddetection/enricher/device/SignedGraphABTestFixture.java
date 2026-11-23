package com.frauddetection.enricher.device;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceReceiptIdentity;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade;
import com.frauddetection.enricher.graph.GraphOccurrenceRef;
import com.mongodb.client.MongoClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Test-only signed Source + Device + Kafka + Graph fixture for owner-side authority proof. */
public final class SignedGraphABTestFixture {
    private SignedGraphABTestFixture() { }

    static Evidence create() throws Exception {
        return run(null);
    }

    /** Invokes the scorer while the signed Source, Device, Kafka and authenticated Graph session stays live. */
    public static Evidence withLiveSession(Consumer<LiveSession> scorer) throws Exception {
        if (scorer == null) { throw new IllegalArgumentException("SIGNED_GRAPH_SCORER_CALLBACK_REQUIRED"); }
        return run(scorer);
    }

    /** Test-only dense hub with independently signed, separately scored owner occurrences. */
    public static Evidence withLiveDenseSession(int priorCount, Consumer<LiveSession> scorer) throws Exception {
        if (priorCount < 1 || priorCount > 100 || scorer == null) {
            throw new IllegalArgumentException("SIGNED_GRAPH_DENSE_FIXTURE_ARGUMENT_INVALID");
        }
        return run(scorer, priorCount);
    }

    private static Evidence run(Consumer<LiveSession> scorer) throws Exception {
        return run(scorer, 1);
    }

    private static Evidence run(Consumer<LiveSession> scorer, int priorCount) throws Exception {
        PrivateDeviceDiagnosticReaderIntegrationTest.start();
        PrivateDeviceDiagnosticReaderIntegrationTest sourceDevice = new PrivateDeviceDiagnosticReaderIntegrationTest();
        List<MongoClient> graphOwnerClients = new ArrayList<>();
        boolean initialized = false;
        try {
            sourceDevice.setUp();
            initialized = true;
            VerifiedDeviceAdmissionIntegrationTest f = sourceDevice.f;
            Instant rootTime = VerifiedDeviceAdmissionIntegrationTest.NOW;
            List<VerifiedDeviceAdmissionIntegrationTest.Event> priorEvents = new ArrayList<>(priorCount);
            for (int index = 0; index < priorCount; index++) {
                priorEvents.add(f.event(f.A, "graph-e2e-prior-" + UUID.randomUUID(),
                        "graph-e2e-prior-transaction-" + index, "customer-1", "graph-e2e-device",
                        index + 1L, rootTime.minusSeconds(priorCount - index)));
            }
            var prior = priorEvents.getFirst();
            var root = f.event(f.A, "graph-e2e-root-" + UUID.randomUUID(), "graph-e2e-transaction-root",
                    "customer-1", "graph-e2e-device", priorCount + 1L, rootTime);

            var admission = f.adapter(f.A);
            List<VerifiedDeviceAdmission.Ticket> priorTickets = priorEvents.stream()
                    .map(event -> f.stage(admission, event)).toList();
            var rootTicket = f.stage(admission, root);
            var coverage = f.coverage(f.A, priorCount + 1L, f.A.start, rootTime);
            for (var priorTicket : priorTickets) { admission.seal(priorTicket, coverage.proof(), coverage.payload()); }
            admission.seal(rootTicket, coverage.proof(), coverage.payload());
            for (var priorTicket : priorTickets) { admission.commit(priorTicket); }
            admission.commit(rootTicket);

            for (int index = 0; index < priorEvents.size(); index++) {
                var priorScore = sourceDevice.publish(priorEvents.get(index), "initial", "graph-e2e-score-prior-" + index);
                admission.bindScored(priorTickets.get(index), sourceDevice.occurrenceReader, priorScore.reference());
            }
            var rootScore = sourceDevice.publish(root, "initial", "graph-e2e-score-root");
            admission.bindScored(rootTicket, sourceDevice.occurrenceReader, rootScore.reference());

            String institutionToken = f.pseudonyms.institution(f.A.institution);
            String sourceCode = DevicePseudonyms.sourceCode("BANK");
            List<String> ownerReferences = new ArrayList<>(priorCount + 1);
            for (var priorEvent : priorEvents) {
                ownerReferences.add(f.A.epoch().eventId(institutionToken, sourceCode,
                        f.pseudonyms.event(f.A.institution, priorEvent.id())));
            }
            String rootOwnerReference = f.A.epoch().eventId(institutionToken, sourceCode,
                    f.pseudonyms.event(f.A.institution, root.id()));
            ownerReferences.add(rootOwnerReference);
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

            SourceLineage priorLineage = sourceDevice.privateSource.enrichedBinding(
                    SourceReceiptIdentity.identity(prior.origin().scope()), SourceEventCommitment.raw(prior.raw()),
                    SourceEventCommitment.enriched(prior.enriched()));
            SourceLineage rootLineage = sourceDevice.privateSource.enrichedBinding(
                    SourceReceiptIdentity.identity(root.origin().scope()), SourceEventCommitment.raw(root.raw()),
                    SourceEventCommitment.enriched(root.enriched()));
            if (priorLineage.state() != SourceLineage.State.VERIFIED
                    || rootLineage.state() != SourceLineage.State.VERIFIED) {
                throw new AssertionError("SIGNED_GRAPH_SOURCE_LINEAGE_NOT_VERIFIED");
            }
            SourceLineage alternateRootLineage = scorer != null && priorCount == 1
                    ? independentlySignedLineage(root) : rootLineage;
            Consumer<GraphDeviceMongoVerticalTestFacade.LiveTwoHopReadSession> liveCallback = scorer == null ? null
                    : graphSession -> scorer.accept(new LiveSession(prior.enriched(), priorLineage, root.enriched(),
                    rootLineage, alternateRootLineage, prior.raw().transactionTimestamp(), rootTime, rootScore.event(),
                    rootScore.wireBytes(), graphSession, f.MONGO));
            GraphDeviceMongoVerticalTestFacade.Result graph = priorCount > 1
                    ? GraphDeviceMongoVerticalTestFacade.persistAndReadTwoHopDenseInLiveSession(initialOwner,
                    restartedOwner, relationGrant, readGrant, ownerReferences, principal,
                    rootTime.plusSeconds(5), rootTime.plusSeconds(6), liveCallback, admitSignedSourceContradiction)
                    : GraphDeviceMongoVerticalTestFacade.persistAndReadTwoHopInLiveSession(initialOwner,
                    restartedOwner, relationGrant, readGrant, ownerReferences, principal,
                    rootTime.plusSeconds(5), rootTime.plusSeconds(6), liveCallback, admitSignedSourceContradiction);
            long deviceOccurrences = f.ownerClient.getDatabase(f.database)
                    .getCollection("device_occurrences").countDocuments();
            long scoredOccurrences = f.ownerClient.getDatabase(f.database)
                    .getCollection("source_scored_occurrences").countDocuments();
            return new Evidence(root.enriched(), root.enriched().transactionId(), root.enriched().correlationId(),
                    prior.raw().transactionTimestamp(), rootTime, rootTime.plusSeconds(6), graph, deviceOccurrences, scoredOccurrences,
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

    private static SourceLineage independentlySignedLineage(VerifiedDeviceAdmissionIntegrationTest.Event event) {
        PrivateDeviceDiagnosticReaderIntegrationTest alternate = new PrivateDeviceDiagnosticReaderIntegrationTest();
        MongoClient enricherClient = null;
        try {
            alternate.setUp();
            VerifiedDeviceAdmissionIntegrationTest f = alternate.f;
            var alternateKey = VerifiedDeviceAdmissionIntegrationTest.key();
            var trustedAlternate = f.A.trusted("source-issuer-alt", "origin-alt",
                    DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest", alternateKey,
                    "origin-policy-alt");
            List<com.frauddetection.common.events.source.SourceVerificationProperties.TrustedKey> trusted =
                    new ArrayList<>(f.origins.keys());
            trusted.add(trustedAlternate);
            var verifier = new SourceSignatureVerifier(
                    new com.frauddetection.common.events.source.SourceVerificationProperties(trusted),
                    VerifiedDeviceAdmissionIntegrationTest.CLOCK);
            var owner = new MongoSourceLineage(f.ownerClient, f.database, "source_owner",
                    MongoSourceLineage.Stage.INGEST, verifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
            var scope = VerifiedDeviceAdmissionIntegrationTest.scope(trustedAlternate, event.id(),
                    event.raw().transactionTimestamp(), event.sourcePayload());
            byte[] proof = VerifiedDeviceAdmissionIntegrationTest.sign(scope, event.sourcePayload(), alternateKey);
            var verified = (SourceSignatureVerifier.Verified) verifier.verify(proof, event.sourcePayload(),
                    new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE,
                            "transaction-ingest"));
            com.frauddetection.ingest.source.DeviceTestSourceReceipts.admit(f.ownerClient, f.database,
                    VerifiedDeviceAdmissionIntegrationTest.CLOCK, verifier, owner, verified, event.raw());

            String alternateEnricherPrincipal = "enricher_alt";
            enricherClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.enricher(
                    f.ownerClient, f.database, alternateEnricherPrincipal);
            var publicationCipher = new com.frauddetection.common.events.source.SourceEnrichedPublicationCipher(
                    "test-only-key", Map.of("test-only-key", java.util.Base64.getEncoder().encodeToString(
                    VerifiedDeviceAdmissionIntegrationTest.randomKey())));
            var enricher = new MongoSourceLineage(enricherClient, f.database, alternateEnricherPrincipal,
                    MongoSourceLineage.Stage.ENRICHER, verifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK,
                    publicationCipher);
            var publication = enricher.prepareEnrichedPublication(event.raw(), event.enriched());
            if (publication.authority().state() != SourceLineage.State.VERIFIED) {
                throw new AssertionError("SIGNED_GRAPH_ALTERNATE_SOURCE_ENRICHMENT_NOT_VERIFIED");
            }
            var claim = enricher.claimEnrichedPublication(event.raw(), publication.event());
            if (enricher.markEnrichedHistoryApplied(claim).state() != SourceLineage.State.VERIFIED) {
                throw new AssertionError("SIGNED_GRAPH_ALTERNATE_SOURCE_HISTORY_NOT_VERIFIED");
            }
            SourceLineage lineage = new MongoSourceLineage(f.sourceReaderClient, f.database, "scorer",
                    MongoSourceLineage.Stage.SCORER, verifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK)
                    .enrichedBinding(SourceReceiptIdentity.identity(scope), SourceEventCommitment.raw(event.raw()),
                            SourceEventCommitment.enriched(event.enriched()));
            if (lineage.state() != SourceLineage.State.VERIFIED || lineage.source().equals(event.origin().scope())
                    || !lineage.eventCommitment().equals(SourceEventCommitment.enriched(event.enriched()))) {
                throw new AssertionError("SIGNED_GRAPH_ALTERNATE_SOURCE_LINEAGE_NOT_INDEPENDENT");
            }
            return lineage;
        } finally {
            if (enricherClient != null) { enricherClient.close(); }
            alternate.cleanUp();
        }
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

    /** Test-JAR-only view of the exact signed B occurrence and its still-live authorized Graph reader. */
    public static final class LiveSession {
        private final TransactionEnrichedEvent priorTransaction;
        private final SourceLineage priorLineage;
        private final TransactionEnrichedEvent rootTransaction;
        private final SourceLineage rootLineage;
        private final SourceLineage alternateRootLineage;
        private final Instant priorTime;
        private final Instant rootTime;
        private final TransactionScoredEvent rootScoredEvent;
        private final byte[] rootScoredWireBytes;
        private final GraphDeviceMongoVerticalTestFacade.LiveTwoHopReadSession graph;
        private final DeviceAuthenticatedMongo ownerMongo;

        private LiveSession(TransactionEnrichedEvent priorTransaction, SourceLineage priorLineage,
                            TransactionEnrichedEvent rootTransaction, SourceLineage rootLineage,
                            SourceLineage alternateRootLineage, Instant priorTime, Instant rootTime,
                            TransactionScoredEvent rootScoredEvent,
                            byte[] rootScoredWireBytes,
                            GraphDeviceMongoVerticalTestFacade.LiveTwoHopReadSession graph,
                            DeviceAuthenticatedMongo ownerMongo) {
            this.priorTransaction = priorTransaction;
            this.priorLineage = priorLineage;
            this.rootTransaction = rootTransaction;
            this.rootLineage = rootLineage;
            this.alternateRootLineage = alternateRootLineage;
            this.priorTime = priorTime;
            this.rootTime = rootTime;
            this.rootScoredEvent = rootScoredEvent;
            this.rootScoredWireBytes = rootScoredWireBytes.clone();
            this.graph = graph;
            this.ownerMongo = ownerMongo;
        }

        public TransactionEnrichedEvent priorTransaction() { return priorTransaction; }
        public SourceLineage priorLineage() { return priorLineage; }
        public TransactionEnrichedEvent rootTransaction() { return rootTransaction; }
        public SourceLineage rootLineage() { return rootLineage; }
        public SourceLineage alternateRootLineage() { return alternateRootLineage; }
        public Instant priorTime() { return priorTime; }
        public Instant rootTime() { return rootTime; }
        public TransactionScoredEvent rootScoredEvent() { return rootScoredEvent; }
        public byte[] rootScoredWireBytes() { return rootScoredWireBytes.clone(); }
        public GraphOccurrenceRef rootOccurrence() { return graph.rootOccurrence(); }
        public GraphDeviceMongoVerticalTestFacade.LiveTwoHopReadSession graph() { return graph; }
        public void resetReadMetrics() {
            graph.resetMeasurementMetrics();
            ownerMongo.resetReadMetrics();
        }
        public Map<String, Long> ownerReadOperationCounts() { return ownerMongo.readOperationCounts(); }
        public Map<String, Long> ownerReadDocumentCounts() { return ownerMongo.readDocumentCounts(); }

        @Override public String toString() { return "SignedGraphABLiveSession[redacted]"; }
    }
}
