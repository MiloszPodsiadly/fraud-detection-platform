package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.SourceRawPublicationCipher;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.mapper.TransactionRequestMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Child JVM used to stop immediately after the majority-durable acceptance transaction commits. */
public final class SourceRawPublicationCrashWorker {
    private SourceRawPublicationCrashWorker() { }

    public static void main(String[] args) {
        if (args.length != 4) { System.exit(2); }
        String database = args[0];
        String uri = args[1];
        String encodedProof = args[2];
        byte[] payload = Base64.getDecoder().decode(args[3]);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Clock clock = Clock.fixed(TestSourceIssuer.NOW, ZoneOffset.UTC);
        SourceSignatureVerifier verifier = new SourceSignatureVerifier(
                new SourceVerificationProperties(java.util.List.of(TestSourceIssuer.policy(0, false))), clock);
        var proof = Base64.getUrlDecoder().decode(encodedProof);
        var verified = verifier.verify(proof, payload, new SourceSignatureVerifier.Context(
                AttestedTransactionIngestService.PURPOSE, AttestedTransactionIngestService.AUDIENCE));
        if (!(verified instanceof SourceSignatureVerifier.Verified)) { System.exit(3); }
        var evidence = (SourceSignatureVerifier.Verified) verified;
        try (MongoClient client = MongoClients.create(MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .timeout(5, TimeUnit.SECONDS).build())) {
            var limits = new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100);
            new MongoSourceReceiptStore(client, database, clock, limits, verifier);
            var lineage = new MongoSourceLineage(client, database, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, clock);
            var properties = new SourceRawPublicationProperties(true, "raw-key-1", Map.of("raw-key-1", key),
                    Duration.ofSeconds(1), Duration.ofSeconds(30), 10, 8);
            var receipts = new MongoSourceReceiptStore(client, database, clock, limits, verifier, lineage,
                    new SourceRawPublicationCipher("raw-key-1", properties.encryptionKeys()), properties);
            IngestTransactionRequest request = JsonMapper.builder().findAndAddModules().build()
                    .readValue(new String(payload, java.nio.charset.StandardCharsets.UTF_8), IngestTransactionRequest.class);
            var candidate = new TransactionRequestMapper().toEvent(request, "crash-worker-correlation");
            if (receipts.acceptRaw(evidence, candidate).status() != SourceReceiptStore.Status.ADMITTED) { System.exit(4); }
            Runtime.getRuntime().halt(0);
        } catch (RuntimeException failed) {
            System.exit(5);
        }
    }
}
