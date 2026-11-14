package com.frauddetection.enricher.device;

import com.mongodb.client.MongoClient;
import org.bson.Document;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static com.mongodb.client.model.Filters.*;

/** UNTRUSTED_TEST_ONLY structural ledger fixtures. Never used by the verified admission adapter. */
final class DeviceStoreFixtures {
    static final DeviceEpoch EPOCH = new DeviceEpoch("fixture-source-issuer", "fixture-epoch-1");
    static final DeviceObservationStore.HistoryAuthority TRUST_STRUCTURAL_TEST_INPUTS = new DeviceObservationStore.HistoryAuthority() {
        @Override public boolean current(DeviceObservationStore.Provenance p, int v) { return true; }
        @Override public boolean coverageCurrent(DeviceObservationStore.CoverageAuthority proof, String institution, String source,
                                                 String population, int version, Instant start) { return true; }
    };
    private static final String T = "9".repeat(64);
    private static final Map<String, DeviceObservationStore.CoverageSeal> SEALS = new ConcurrentHashMap<>();
    static DeviceObservationStore.Provenance provenance(String event, String transaction, String payload, Instant time) {
        return new DeviceObservationStore.Provenance(event, payload, transaction, payload, T, "test-only-policy", "test-only-device",
                "test-only-key", "test-only-policy", T, T, time.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 1);
    }
    static void scopeHistoryRow(Document row) {
        String institution = row.getString("institution"), source = row.getString("source"), event = row.getString("sourceEvent");
        row.putAll(EPOCH.document(EPOCH.eventId(institution, source, event), institution, source));
        var provenance = provenance(event, event, T, row.getDate("eventTime").toInstant());
        row.append("provenance", provenance.document()).append("provenanceCommitment", provenance.commitment());
    }
    static DeviceObservationStore.CoverageProof emptyProof() {
        return new DeviceObservationStore.CoverageProof(1, 1, 1, "0".repeat(64), T, T, T, coverageAuthority(Instant.EPOCH));
    }
    static DeviceObservationStore.CoverageAuthority coverageAuthority(Instant time) {
        return new DeviceObservationStore.CoverageAuthority("test-only-coverage", "test-only-key", "test-only-policy", T, T, T, "test-only-seal", time);
    }
    static DeviceObservationStore.CoverageSeal seal(MongoClient client, String database, String institution, String source,
                                                  long through, Instant start, Instant watermark, int version) {
        String key = database + ':' + institution + ':' + through + ':' + start + ':' + watermark + ':' + version;
        return SEALS.computeIfAbsent(key, ignored -> {
            Document progress = client.getDatabase(database).getCollection("device_source_coverage")
                    .find(eq("_id", EPOCH.scopeId(institution, source))).first();
            long previous = progress == null ? 0 : progress.getLong("sealedSequence");
            long generation = progress == null ? 1 : progress.getLong("coverageGeneration") + 1;
            String predecessor = progress == null ? "0".repeat(64) : progress.getString("coverageCommitment");
            long from = Math.min(previous + 1, through);
            var prototype = new DeviceObservationStore.CoverageSeal(institution, source, through, start, watermark, version,
                    new DeviceObservationStore.CoverageProof(1, from, generation, predecessor, T, T, T, coverageAuthority(watermark)));
            List<Document> rows = client.getDatabase(database).getCollection("device_observation_candidates")
                    .find(and(EPOCH.filter(institution, source), gte("sequence", from), lte("sequence", through)))
                    .sort(new Document("sequence", 1)).into(new java.util.ArrayList<>());
            String commitment = DeviceCoverageCommitment.compute(EPOCH, prototype, rows);
            return new DeviceObservationStore.CoverageSeal(institution, source, through, start, watermark, version,
                    new DeviceObservationStore.CoverageProof(1, from, generation, predecessor, commitment,
                            DeviceEpoch.digest("UNTRUSTED_TEST_ONLY_SEAL", commitment), T, coverageAuthority(watermark)));
        });
    }
}
